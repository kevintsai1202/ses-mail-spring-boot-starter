package io.github.kevintsai1202.sesmail;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 透過 Amazon SES（v2 API）寄信的主要入口。
 *
 * <p>SES 沒有排程與取消 API，這裡用本機待寄佇列補上：</p>
 * <ul>
 *   <li>{@link #send}：交易信（登入、確認），同步呼叫 SES，讀者正在等。</li>
 *   <li>{@link #enqueueBatch}：群發寫入佇列、預定時間為現在，由 {@link ScheduledMailDispatcher} 依速率送出。
 *       數百封逐封同步呼叫 SES 要數十秒，放在 HTTP 請求裡會逾時。</li>
 *   <li>{@link #schedule}：寫入佇列，回傳 {@code ses-sched-{id}}，到期送出。</li>
 *   <li>{@link #cancel}：只認自己發的 {@code ses-sched-*}，且只能取消還沒被派送器認領的信。</li>
 * </ul>
 */
public class SesMailClient {

    private final SesV2Client ses;
    private final ScheduledMailQueue queue;
    /** 寄件人（已做 RFC 2047 編碼） */
    private final String from;
    /** 回信地址，可空白 */
    private final String replyTo;
    /** SES configuration set 名稱，可空白（接退信事件時使用） */
    private final String configurationSet;
    /** 時鐘，測試可固定 */
    private final Clock clock;

    /** 注入 SES 客戶端、佇列與寄件設定 */
    public SesMailClient(SesV2Client ses, ScheduledMailQueue queue, String from, String replyTo,
                         String configurationSet, Clock clock) {
        this.ses = ses;
        this.queue = queue;
        this.from = MailAddressEncoder.encode(from);
        this.replyTo = replyTo;
        this.configurationSet = configurationSet;
        this.clock = clock;
    }

    /** 交易信：同步寄出，回傳 SES MessageId */
    public String send(String to, String subject, String html) {
        return deliver(to, subject, html);
    }

    /**
     * 群發：整批以同一個參照寫入佇列（一條 INSERT，全有或全無），回傳 ses-batch-*。
     * 單次上限 5000 封，超過請由呼叫端切批。
     */
    public String enqueueBatch(List<OutgoingMail> mails) {
        if (mails == null || mails.isEmpty()) {
            throw new IllegalArgumentException("批次群發至少要有一封信");
        }
        String ref = QueuedMail.BATCH_PREFIX + UUID.randomUUID();
        queue.insertAll(ref, mails, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return ref;
    }

    /** 排程單封：寫入佇列，回傳可取消的 ses-sched-{id} */
    public String schedule(OutgoingMail mail, Instant sendAt) {
        long id = queue.insert(null, mail, OffsetDateTime.ofInstant(sendAt, ZoneOffset.UTC));
        return QueuedMail.SCHEDULE_PREFIX + id;
    }

    /**
     * 取消排程：不是 ses-sched-* 的參照（例如其他寄信服務留下的 id）一律回 false；
     * 只靠條件式 UPDATE 判斷是否還沒被派送器認領，不先讀列（先讀再改會與派送器競爭）。
     */
    public boolean cancel(String ref) {
        if (ref == null || !ref.startsWith(QueuedMail.SCHEDULE_PREFIX)) {
            return false;
        }
        long id;
        try {
            id = Long.parseLong(ref.substring(QueuedMail.SCHEDULE_PREFIX.length()));
        } catch (NumberFormatException e) {
            return false;
        }
        return queue.cancel(id) == 1;
    }

    /**
     * 實際呼叫 SES 寄出一封信，回傳 MessageId。供交易信與派送器共用。
     *
     * <p>主旨與內文都明確指定 UTF-8：SES 的 Content 預設是 7-bit ASCII，不指定的話中文會變亂碼。</p>
     */
    public String deliver(String to, String subject, String html) {
        SendEmailRequest.Builder request = SendEmailRequest.builder()
            .fromEmailAddress(from)
            .destination(Destination.builder().toAddresses(to).build())
            .content(EmailContent.builder()
                .simple(Message.builder()
                    .subject(utf8(subject))
                    .body(Body.builder().html(utf8(html)).build())
                    .build())
                .build());
        if (hasText(replyTo)) {
            request.replyToAddresses(replyTo);
        }
        if (hasText(configurationSet)) {
            request.configurationSetName(configurationSet);
        }
        return ses.sendEmail(request.build()).messageId();
    }

    /** 包成指定 UTF-8 的 SES Content */
    private static Content utf8(String data) {
        return Content.builder().charset("UTF-8").data(data).build();
    }

    /** 非 null 且非空白 */
    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
