package io.github.kevintsai1202.sesmail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.LimitExceededException;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 待寄佇列的派送器：定時撈出到期的信，逐封認領、送出、記錄結果、通知 {@link DeliveryListener}。
 *
 * <p><b>規則</b></p>
 * <ul>
 *   <li>先以條件式 UPDATE 認領（PENDING → SENDING），認領不到就跳過——那一列剛被取消或已被處理。</li>
 *   <li>節流、網路錯誤、5xx 是暫時性的：退回 PENDING，依 30 秒 × 2^(n−1) 退避，
 *       達 {@link #MAX_ATTEMPTS} 次才放棄。其他錯誤（收件位址被拒、網域未驗證、帳號停權）重試也沒用，直接失敗。</li>
 *   <li>每封之間依速率暫停（沙箱每秒 1 封）。一輪撈滿 {@link #BATCH_LIMIT} 封時不等派送間隔，直接撈下一輪，
 *       積壓時實際速度接近每秒上限。</li>
 *   <li>設定了 HTML 保留期時，每小時把超過保留期的已寄出、已取消信件 HTML 清成空字串（整列保留）。</li>
 *   <li>listener 拋出的例外只記 log，不中斷派送。</li>
 *   <li>程序中斷後卡在 SENDING 的列<b>絕不自動重寄</b>：無法得知 SES 收到了沒，
 *       重複寄出一期電子報比漏寄一封更糟，只定期回報數量交給人工確認。</li>
 * </ul>
 *
 * <p>自帶單執行緒排程器（{@link SmartLifecycle}），不需要應用程式開 {@code @EnableScheduling}，
 * 也不會占用應用程式其他排程工作的執行緒。停止時不再開始新一輪，並等待進行中的那封寄完。</p>
 */
public class ScheduledMailDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMailDispatcher.class);

    /** 暫時性錯誤最多嘗試次數（含第一次） */
    public static final int MAX_ATTEMPTS = 5;
    /** 每輪最多處理幾封 */
    static final int BATCH_LIMIT = 200;
    /** 第一次重試的等待時間，之後每次加倍 */
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    /** SENDING 超過這段時間沒變化，視為程序中斷留下的列 */
    private static final Duration STUCK_AFTER = Duration.ofMinutes(10);
    /** 卡住檢查的間隔 */
    private static final Duration STUCK_CHECK_INTERVAL = Duration.ofMinutes(30);
    /** 清理舊 HTML 的間隔 */
    private static final Duration HTML_PURGE_INTERVAL = Duration.ofHours(1);
    /** 停止時等待進行中寄送的上限 */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);
    /** 錯誤訊息寫進資料庫前截斷的長度 */
    private static final int ERROR_MAX = 500;

    /** 暫停指定毫秒；正式環境是 Thread.sleep，測試改成記錄 */
    @FunctionalInterface
    public interface Sleeper {
        void pause(long millis) throws InterruptedException;
    }

    private final ScheduledMailQueue queue;
    private final SesMailClient client;
    /** 每次通知時才取 listener 清單，應用程式的 listener bean 晚建立也拿得到 */
    private final Supplier<List<DeliveryListener>> listeners;
    /** 每封之間暫停的毫秒數（由每秒上限換算） */
    private final long pauseMillis;
    /** 派送間隔 */
    private final Duration interval;
    /** 已寄出信件 HTML 的保留期；null 或 0 表示不清 */
    private final Duration htmlRetention;
    private final Clock clock;
    private final Sleeper sleeper;

    /** 排程器；null 表示未啟動 */
    private volatile ScheduledExecutorService executor;
    /** 停止中：進行中的那一輪寄完目前這封就結束 */
    private volatile boolean stopping;

    /** 不清理舊 HTML 的派送器 */
    public ScheduledMailDispatcher(ScheduledMailQueue queue, SesMailClient client,
                                   Supplier<List<DeliveryListener>> listeners, double maxSendRate,
                                   Duration interval, Clock clock, Sleeper sleeper) {
        this(queue, client, listeners, maxSendRate, interval, null, clock, sleeper);
    }

    /**
     * @param maxSendRate   每秒最多送幾封；應不高於 SES 帳號的 MaxSendRate
     * @param interval      多久掃一次佇列
     * @param htmlRetention 已寄出、已取消信件的 HTML 保留多久；null 或 0 表示不清
     */
    public ScheduledMailDispatcher(ScheduledMailQueue queue, SesMailClient client,
                                   Supplier<List<DeliveryListener>> listeners, double maxSendRate,
                                   Duration interval, Duration htmlRetention, Clock clock, Sleeper sleeper) {
        if (maxSendRate <= 0) {
            throw new IllegalArgumentException("sesmail.max-send-rate 必須大於 0");
        }
        this.queue = queue;
        this.client = client;
        this.listeners = listeners;
        this.pauseMillis = (long) Math.ceil(1000.0 / maxSendRate);
        this.interval = interval;
        this.htmlRetention = htmlRetention;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** 啟動專用排程器：定期派送與卡住檢查 */
    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        stopping = false;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ses-mail-dispatcher");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::reportStuck, Duration.ofMinutes(1).toMillis(),
            STUCK_CHECK_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        if (purgeEnabled()) {
            executor.scheduleWithFixedDelay(this::purgeTick, Duration.ofMinutes(5).toMillis(),
                HTML_PURGE_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** 停止：不再開始新一輪，等待進行中的寄送（最多 30 秒） */
    @Override
    public synchronized void stop() {
        ScheduledExecutorService current = executor;
        if (current == null) {
            return;
        }
        stopping = true;
        current.shutdown();
        try {
            if (!current.awaitTermination(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    /** 排程進入點：例外不往外拋，否則排程器會靜默停止後續執行 */
    private void tick() {
        try {
            drain();
        } catch (RuntimeException e) {
            log.error("SES 待寄佇列派送失敗：{}", e.getMessage(), e);
        }
    }

    /**
     * 連續派送：一輪撈滿 {@link #BATCH_LIMIT} 封就接著撈下一輪，直到撈不滿或正在停止。
     * 是否繼續看「撈到幾封」而不是「寄出幾封」：撈滿代表可能還有積壓。
     */
    void drain() {
        // 暫停被中斷時那一輪會提早結束；這時也要停，否則會不經暫停連續送出
        while (!stopping && dispatchRound().fetched() >= BATCH_LIMIT && !Thread.currentThread().isInterrupted()) {
            // 繼續下一輪；速率仍由每封之間的暫停控制
        }
    }

    /**
     * 派送一輪：撈出到期的信，逐封認領並送出。
     *
     * @return 本輪成功交給 SES 的封數
     */
    public int dispatchDue() {
        return dispatchRound().sent();
    }

    /**
     * 一輪的結果。
     *
     * @param fetched 撈到的封數（撈滿代表可能還有積壓）
     * @param sent    成功交給 SES 的封數
     */
    private record Round(int fetched, int sent) {
    }

    /** 派送一輪的實作 */
    private Round dispatchRound() {
        OffsetDateTime now = now();
        List<Long> ids = queue.findDueIds(now, BATCH_LIMIT);
        int sent = 0;
        for (Long id : ids) {
            if (stopping) {
                break;
            }
            if (queue.claim(id) != 1) {
                continue; // 剛被取消，或已被處理
            }
            QueuedMail mail = queue.find(id).orElse(null);
            if (mail == null) {
                continue;
            }
            try {
                String messageId = client.deliver(mail.recipient(), mail.subject(), mail.html(), mail.unsubscribeUrl());
                queue.markSent(id, messageId);
                sent++;
                notifyListeners(l -> l.onSent(mail.providerRef(), mail.recipient(), messageId));
            } catch (RuntimeException e) {
                handleFailure(mail, e, now);
            }
            if (!pause()) {
                break;
            }
        }
        if (sent > 0) {
            log.info("SES 待寄佇列：本輪送出 {} 封（撈到 {} 封）", sent, ids.size());
        }
        return new Round(ids.size(), sent);
    }

    /** 是否有設定 HTML 保留期 */
    private boolean purgeEnabled() {
        return htmlRetention != null && !htmlRetention.isZero() && !htmlRetention.isNegative();
    }

    /**
     * 清掉超過保留期的已寄出、已取消信件 HTML。
     *
     * @return 清掉的列數；未設定保留期時為 0
     */
    public int purgeHtml() {
        if (!purgeEnabled()) {
            return 0;
        }
        int cleared = queue.clearHtml(now().minus(htmlRetention));
        if (cleared > 0) {
            log.info("SES 待寄佇列：清掉 {} 封超過 {} 天的已寄出信件 HTML", cleared, htmlRetention.toDays());
        }
        return cleared;
    }

    /** 清理的排程進入點：例外不往外拋，否則排程器會靜默停止後續執行 */
    void purgeTick() {
        try {
            purgeHtml();
        } catch (RuntimeException e) {
            log.warn("清理已寄出信件 HTML 失敗：{}", e.getMessage());
        }
    }

    /**
     * 回報卡在 SENDING 的列數；有的話記警告，不自動重寄。
     *
     * @return 卡住的列數
     */
    public long reportStuck() {
        try {
            long stuck = queue.countStuck(now().minus(STUCK_AFTER));
            if (stuck > 0) {
                log.warn("SES 待寄佇列有 {} 封卡在 SENDING 超過 {} 分鐘（可能是程序中斷），不會自動重寄，請人工確認",
                    stuck, STUCK_AFTER.toMinutes());
            }
            return stuck;
        } catch (RuntimeException e) {
            log.warn("檢查卡住的待寄信失敗：{}", e.getMessage());
            return -1;
        }
    }

    /** 依錯誤種類決定退回重試或標為失敗 */
    private void handleFailure(QueuedMail mail, RuntimeException e, OffsetDateTime now) {
        String error = truncate(e.getClass().getSimpleName() + ": " + e.getMessage());
        if (isTransient(e) && mail.attempts() < MAX_ATTEMPTS) {
            Duration backoff = BASE_BACKOFF.multipliedBy(1L << Math.max(0, mail.attempts() - 1));
            queue.retryLater(mail.id(), error, now.plus(backoff));
            log.warn("SES 寄信暫時失敗，{} 秒後重試 id={} attempts={}：{}",
                backoff.toSeconds(), mail.id(), mail.attempts(), error);
            return;
        }
        queue.markFailed(mail.id(), error);
        log.warn("SES 寄信失敗，不再重試 id={} attempts={}：{}", mail.id(), mail.attempts(), error);
        notifyListeners(l -> l.onFailed(mail.providerRef(), mail.recipient(), error));
    }

    /** 逐一通知 listener；個別 listener 的例外只記 log */
    private void notifyListeners(Consumer<DeliveryListener> action) {
        for (DeliveryListener listener : listeners.get()) {
            try {
                action.accept(listener);
            } catch (RuntimeException e) {
                log.warn("DeliveryListener {} 處理失敗：{}", listener.getClass().getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * 節流、網路錯誤與 SES 端 5xx 視為暫時性，其餘重試無用。
     *
     * <p>節流除了 SDK 有對應類別的例外，也包含 SDK 認得的節流錯誤碼：超過每秒上限時 SES 文件寫的是
     * {@code ThrottlingException}（4xx），SDK 沒有對應類別，只能靠 {@link AwsServiceException#isThrottlingException()} 辨識。</p>
     */
    static boolean isTransient(RuntimeException e) {
        if (e instanceof TooManyRequestsException || e instanceof LimitExceededException) {
            return true;
        }
        if (e instanceof AwsServiceException service) {
            return service.isThrottlingException() || service.statusCode() >= 500;
        }
        return e instanceof SdkClientException;
    }

    /** 依速率暫停；被中斷時恢復中斷旗標並結束本輪 */
    private boolean pause() {
        try {
            sleeper.pause(pauseMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 目前時間（UTC） */
    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** 錯誤訊息截斷，避免整份 HTML 或堆疊塞進資料庫 */
    private static String truncate(String s) {
        return s == null || s.length() <= ERROR_MAX ? s : s.substring(0, ERROR_MAX);
    }
}
