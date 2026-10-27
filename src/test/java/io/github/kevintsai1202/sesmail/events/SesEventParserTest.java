package io.github.kevintsai1202.sesmail.events;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SesEventParser：把 SNS 通知裡的 SES 事件 JSON 轉成退信／檢舉／送達事件，並把 MessageId 對回呼叫端參照 */
class SesEventParserTest {

    /** 參照解析器：查不到時沿用 MessageId（交易信的情況） */
    private final SesEventParser parser = new SesEventParser(messageId -> messageId);

    /** configuration set 事件發佈格式（eventType）的永久退信 */
    @Test
    void parsesPermanentBounceFromEventPublishing() {
        String json = """
            {"eventType":"Bounce",
             "bounce":{"bounceType":"Permanent","bounceSubType":"General",
               "bouncedRecipients":[{"emailAddress":"a@example.com","status":"5.1.1"},
                                    {"emailAddress":"b@example.com","status":"5.1.1"}],
               "timestamp":"2026-10-27T10:00:00.000Z","feedbackId":"f-1"},
             "mail":{"timestamp":"2026-10-27T09:59:00.000Z","messageId":"msg-1","source":"noreply@example.com"}}
            """;

        Optional<SesEvent> event = parser.parse(json);

        SesBounce bounce = (SesBounce) event.orElseThrow();
        assertTrue(bounce.permanent());
        assertEquals("Permanent", bounce.bounceType());
        assertEquals("General", bounce.bounceSubType());
        assertEquals(List.of("a@example.com", "b@example.com"), bounce.recipients());
        assertEquals("msg-1", bounce.messageId());
        assertEquals("msg-1", bounce.ref());
    }

    /** 身分層級通知格式（notificationType）的暫時退信 */
    @Test
    void parsesTransientBounceFromIdentityNotification() {
        String json = """
            {"notificationType":"Bounce",
             "bounce":{"bounceType":"Transient","bounceSubType":"MailboxFull",
               "bouncedRecipients":[{"emailAddress":"full@example.com"}]},
             "mail":{"messageId":"msg-2"}}
            """;

        SesBounce bounce = (SesBounce) parser.parse(json).orElseThrow();

        assertFalse(bounce.permanent());
        assertEquals(List.of("full@example.com"), bounce.recipients());
    }

    /** 檢舉 */
    @Test
    void parsesComplaint() {
        String json = """
            {"eventType":"Complaint",
             "complaint":{"complainedRecipients":[{"emailAddress":"c@example.com"}],
               "complaintFeedbackType":"abuse","feedbackId":"f-3"},
             "mail":{"messageId":"msg-3"}}
            """;

        SesComplaint complaint = (SesComplaint) parser.parse(json).orElseThrow();

        assertEquals(List.of("c@example.com"), complaint.recipients());
        assertEquals("abuse", complaint.feedbackType());
        assertEquals("msg-3", complaint.messageId());
    }

    /** 送達：delivery.recipients 是字串陣列，不是 {emailAddress} 物件陣列（SES 實際格式） */
    @Test
    void parsesDeliveryWithStringRecipients() {
        String json = """
            {"eventType":"Delivery",
             "mail":{"timestamp":"2026-10-27T10:00:00.000Z","messageId":"msg-4","destination":["d@example.com"]},
             "delivery":{"timestamp":"2026-10-27T10:00:02.000Z","processingTimeMillis":2000,
               "recipients":["d@example.com"],"smtpResponse":"250 2.0.0 OK","reportingMTA":"a8-1.smtp-out.amazonses.com"}}
            """;

        SesDelivery delivery = (SesDelivery) parser.parse(json).orElseThrow();

        assertEquals(List.of("d@example.com"), delivery.recipients());
        assertEquals("msg-4", delivery.messageId());
    }

    /** 參照解析：佇列寄出的信，MessageId 會被對回 ses-batch-*／ses-sched-* */
    @Test
    void resolvesRefFromMessageId() {
        SesEventParser resolving = new SesEventParser(id -> "msg-5".equals(id) ? "ses-batch-abc" : id);
        String json = """
            {"eventType":"Delivery","mail":{"messageId":"msg-5"},"delivery":{"recipients":["e@example.com"]}}
            """;

        assertEquals("ses-batch-abc", resolving.parse(json).orElseThrow().ref());
    }

    /** 參照解析失敗（例如資料庫斷線）必須往外拋，讓呼叫端保留訊息稍後重試 */
    @Test
    void resolverFailurePropagates() {
        SesEventParser failing = new SesEventParser(id -> { throw new IllegalStateException("db down"); });
        String json = """
            {"eventType":"Delivery","mail":{"messageId":"m"},"delivery":{"recipients":["e@example.com"]}}
            """;

        assertThrows(IllegalStateException.class, () -> failing.parse(json));
    }

    /** 開信、點擊等其他事件：不處理 */
    @Test
    void ignoresOtherEventTypes() {
        assertTrue(parser.parse("{\"eventType\":\"Open\",\"mail\":{\"messageId\":\"m\"}}").isEmpty());
    }

    /** 壞掉的 JSON：不拋例外，回空（不讓訊息一直重送一則永遠解析不了的內容） */
    @Test
    void malformedJsonIsIgnored() {
        assertTrue(parser.parse("not json").isEmpty());
        assertTrue(parser.parse("{\"eventType\":\"Bounce\"}").isEmpty());
    }
}
