package io.github.kevintsai1202.sesmail.events;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SesEventParser：把 SNS 通知裡的 SES 事件 JSON 轉成退信／檢舉事件 */
class SesEventParserTest {

    private final SesEventParser parser = new SesEventParser();

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

    /** 送達、開信等其他事件：不處理 */
    @Test
    void ignoresOtherEventTypes() {
        assertTrue(parser.parse("{\"eventType\":\"Delivery\",\"mail\":{\"messageId\":\"m\"}}").isEmpty());
    }

    /** 壞掉的 JSON：不拋例外，回空（不讓 SNS 一直重送一則永遠解析不了的訊息） */
    @Test
    void malformedJsonIsIgnored() {
        assertTrue(parser.parse("not json").isEmpty());
        assertTrue(parser.parse("{\"eventType\":\"Bounce\"}").isEmpty());
    }
}
