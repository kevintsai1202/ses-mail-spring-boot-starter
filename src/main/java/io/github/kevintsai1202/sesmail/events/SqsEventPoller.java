package io.github.kevintsai1202.sesmail.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 從 SQS 拉取 SNS 轉來的 SES 事件（SNS → SQS → 應用程式），交給 {@link SnsWebhookHandler} 處理。
 *
 * <p>比 HTTPS 推送可靠：部署或重啟期間的事件留在佇列裡等程式回來；處理失敗（{@code FAILED}）或被拒
 * （{@code REJECTED}）不刪除，可見逾時後 SQS 重送，超過佇列設定的次數進死信佇列。
 * 只有 NOTIFIED／IGNORED／CONFIRMED 才刪除。</p>
 *
 * <p>自帶單執行緒（{@link SmartLifecycle}）持續長輪詢（每次最多等 20 秒、10 則）。收取失敗時退避 30 秒，不空轉。
 * 停止時等目前這輪結束（最多 25 秒，長輪詢最長 20 秒）。</p>
 */
public class SqsEventPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SqsEventPoller.class);

    /** 長輪詢等待秒數（SQS 上限 20） */
    static final int WAIT_SECONDS = 20;
    /** 一次最多收幾則（SQS 上限 10） */
    static final int MAX_MESSAGES = 10;
    /** 收取失敗後的退避時間 */
    private static final Duration ERROR_BACKOFF = Duration.ofSeconds(30);
    /** 停止時等待進行中那一輪的上限 */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(25);

    /** 暫停指定毫秒；正式環境是 Thread.sleep，測試改成記錄 */
    @FunctionalInterface
    public interface Sleeper {
        void pause(long millis) throws InterruptedException;
    }

    private final SqsClient sqs;
    private final String queueUrl;
    private final SnsWebhookHandler handler;
    private final Sleeper sleeper;

    /** 執行緒；null 表示未啟動 */
    private volatile ExecutorService executor;
    /** 是否繼續拉取 */
    private volatile boolean running;
    /** 是否隨 Spring context 自動啟動（測試或手動控制時可關閉） */
    private boolean autoStartup = true;

    /**
     * @param queueUrl 訂閱了 SES 事件 topic 的 SQS 佇列網址
     */
    public SqsEventPoller(SqsClient sqs, String queueUrl, SnsWebhookHandler handler, Sleeper sleeper) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.handler = handler;
        this.sleeper = sleeper;
    }

    /** 啟動專用執行緒持續拉取 */
    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        running = true;
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ses-mail-sqs-poller");
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> {
            while (running) {
                try {
                    pollOnce();
                } catch (Throwable t) {
                    // 任何意外（含 Error）都不能讓迴圈結束：否則 isRunning() 仍為 true，卻再也不會拉取
                    log.error("SQS 拉取迴圈發生未預期錯誤，退避後繼續：{}", t.getMessage(), t);
                    backoff();
                }
            }
        });
        log.info("開始從 SQS 拉取 SES 事件：{}", queueUrl);
    }

    /** 停止：不再開始新一輪，等目前這輪結束 */
    @Override
    public synchronized void stop() {
        ExecutorService current = executor;
        if (current == null) {
            return;
        }
        running = false;
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

    @Override
    public boolean isAutoStartup() {
        return autoStartup;
    }

    /** 設定是否隨 Spring context 自動啟動 */
    public void setAutoStartup(boolean autoStartup) {
        this.autoStartup = autoStartup;
    }

    /**
     * 拉取一輪並處理。
     *
     * @return 本輪處理成功並刪除的則數
     */
    public int pollOnce() {
        List<Message> messages;
        try {
            messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .waitTimeSeconds(WAIT_SECONDS)
                .maxNumberOfMessages(MAX_MESSAGES)
                .build()).messages();
        } catch (RuntimeException e) {
            log.warn("從 SQS 收取 SES 事件失敗，{} 秒後再試：{}", ERROR_BACKOFF.toSeconds(), e.getMessage());
            backoff();
            return 0;
        }
        int deleted = 0;
        for (Message message : messages) {
            if (handle(message)) {
                deleted++;
            }
        }
        return deleted;
    }

    /** 處理一則；成功才刪除，回傳是否已刪除 */
    private boolean handle(Message message) {
        SnsWebhookHandler.Result result;
        try {
            result = handler.handle(message.body());
        } catch (RuntimeException e) {
            log.warn("處理 SQS 訊息 {} 時發生例外，保留稍後重試：{}", message.messageId(), e.getMessage(), e);
            return false;
        }
        if (result == SnsWebhookHandler.Result.FAILED || result == SnsWebhookHandler.Result.REJECTED) {
            log.warn("SQS 訊息 {} 處理結果 {}，保留稍後重試（多次失敗會進死信佇列）", message.messageId(), result);
            return false;
        }
        try {
            sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
            return true;
        } catch (RuntimeException e) {
            // 刪除失敗會被重送一次，listener 是冪等的，不影響正確性
            log.warn("刪除 SQS 訊息 {} 失敗，之後會重送：{}", message.messageId(), e.getMessage());
            return false;
        }
    }

    /** 收取失敗後退避；被中斷時恢復旗標 */
    private void backoff() {
        try {
            sleeper.pause(ERROR_BACKOFF.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
