package io.github.kevintsai1202.sesmail.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.messagemanager.sns.model.SnsMessage;
import software.amazon.awssdk.messagemanager.sns.model.SnsNotification;
import software.amazon.awssdk.messagemanager.sns.model.SnsSubscriptionConfirmation;
import software.amazon.awssdk.messagemanager.sns.model.SnsUnsubscribeConfirmation;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SnsWebhookHandler：驗簽、Topic 白名單、訂閱確認、事件分派 */
class SnsWebhookHandlerTest {

    /** 允許的 topic */
    private static final String TOPIC = "arn:aws:sns:ap-northeast-1:123456789012:ses-events";

    private SnsMessageVerifier verifier;
    private SubscriptionConfirmer confirmer;
    private SesEventListener listener;
    private SnsWebhookHandler handler;

    @BeforeEach
    void setUp() {
        verifier = mock(SnsMessageVerifier.class);
        confirmer = mock(SubscriptionConfirmer.class);
        listener = mock(SesEventListener.class);
        handler = new SnsWebhookHandler(verifier, Set.of(TOPIC), "ap-northeast-1", confirmer,
            new SesEventParser(), () -> List.of(listener));
    }

    /** 組一則 SNS 通知 */
    private static SnsNotification notification(String topic, String message) {
        return SnsNotification.builder().topicArn(topic).message(message).messageId("sns-1")
            .timestamp(Instant.now()).signature(SdkBytes.fromUtf8String("sig")).build();
    }

    /** 組一則訂閱確認 */
    private static SnsSubscriptionConfirmation confirmation(String topic, String subscribeUrl) {
        return SnsSubscriptionConfirmation.builder().topicArn(topic).message("confirm").messageId("sns-2")
            .subscribeUrl(URI.create(subscribeUrl)).token("t").timestamp(Instant.now())
            .signature(SdkBytes.fromUtf8String("sig")).build();
    }

    /** 簽章驗證失敗：拒絕，不碰 listener */
    @Test
    void invalidSignatureIsRejected() {
        when(verifier.verify("body")).thenThrow(new IllegalArgumentException("bad signature"));

        assertEquals(SnsWebhookHandler.Result.REJECTED, handler.handle("body"));
        verify(listener, never()).onBounce(any());
    }

    /** 不在白名單的 topic：拒絕（擋掉別人用自己的 topic 灌訊息進來） */
    @Test
    void unknownTopicIsRejected() {
        SnsMessage msg = notification("arn:aws:sns:ap-northeast-1:999999999999:other", "{}");
        when(verifier.verify("body")).thenReturn(msg);

        assertEquals(SnsWebhookHandler.Result.REJECTED, handler.handle("body"));
    }

    /** 白名單 topic 的訂閱確認：呼叫 SubscribeURL 完成訂閱 */
    @Test
    void subscriptionConfirmationIsConfirmed() {
        String url = "https://sns.ap-northeast-1.amazonaws.com/?Action=ConfirmSubscription&Token=t";
        when(verifier.verify("body")).thenReturn(confirmation(TOPIC, url));

        assertEquals(SnsWebhookHandler.Result.CONFIRMED, handler.handle("body"));
        verify(confirmer).confirm(URI.create(url));
    }

    /** SubscribeURL 不是該區域的 SNS 主機：拒絕，不去打這個網址（避免被拿來發出任意請求） */
    @Test
    void subscribeUrlOnForeignHostIsRejected() {
        when(verifier.verify("body")).thenReturn(confirmation(TOPIC, "https://evil.example.com/confirm"));

        assertEquals(SnsWebhookHandler.Result.REJECTED, handler.handle("body"));
        verify(confirmer, never()).confirm(any());
    }

    /** 退信通知：分派給 listener */
    @Test
    void bounceIsDispatched() {
        String event = """
            {"eventType":"Bounce","bounce":{"bounceType":"Permanent","bounceSubType":"General",
             "bouncedRecipients":[{"emailAddress":"a@example.com"}]},"mail":{"messageId":"m-1"}}
            """;
        when(verifier.verify("body")).thenReturn(notification(TOPIC, event));

        assertEquals(SnsWebhookHandler.Result.NOTIFIED, handler.handle("body"));
        verify(listener).onBounce(new SesBounce("Permanent", "General", List.of("a@example.com"), "m-1"));
    }

    /** 檢舉通知：分派給 listener；listener 拋例外仍回 NOTIFIED（交給 SNS 重送沒有幫助） */
    @Test
    void complaintIsDispatchedEvenIfListenerFails() {
        String event = """
            {"eventType":"Complaint","complaint":{"complainedRecipients":[{"emailAddress":"c@example.com"}],
             "complaintFeedbackType":"abuse"},"mail":{"messageId":"m-2"}}
            """;
        when(verifier.verify("body")).thenReturn(notification(TOPIC, event));
        SesComplaint expected = new SesComplaint(List.of("c@example.com"), "abuse", "m-2");
        doThrow(new IllegalStateException("db down")).when(listener).onComplaint(expected);

        assertEquals(SnsWebhookHandler.Result.NOTIFIED, handler.handle("body"));
        verify(listener).onComplaint(expected);
    }

    /** 送達等不處理的事件、取消訂閱確認：忽略 */
    @Test
    void otherMessagesAreIgnored() {
        when(verifier.verify("delivery")).thenReturn(notification(TOPIC, "{\"eventType\":\"Delivery\"}"));
        assertEquals(SnsWebhookHandler.Result.IGNORED, handler.handle("delivery"));

        when(verifier.verify("unsub")).thenReturn(SnsUnsubscribeConfirmation.builder().topicArn(TOPIC)
            .message("bye").messageId("sns-3").subscribeUrl(URI.create("https://sns.ap-northeast-1.amazonaws.com/"))
            .token("t").timestamp(Instant.now()).signature(SdkBytes.fromUtf8String("sig")).build());
        assertEquals(SnsWebhookHandler.Result.IGNORED, handler.handle("unsub"));
    }
}
