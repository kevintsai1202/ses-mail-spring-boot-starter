package io.github.kevintsai1202.sesmail.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SqsEventPoller：長輪詢收訊息、交給 handler；成功才刪除，失敗保留給 SQS 重試 */
class SqsEventPollerTest {

    private static final String QUEUE = "https://sqs.ap-northeast-1.amazonaws.com/123456789012/ses-events";

    private SqsClient sqs;
    private SnsWebhookHandler handler;
    /** 記錄退避暫停的毫秒數 */
    private List<Long> pauses;
    private SqsEventPoller poller;

    @BeforeEach
    void setUp() {
        sqs = mock(SqsClient.class);
        handler = mock(SnsWebhookHandler.class);
        pauses = new ArrayList<>();
        poller = new SqsEventPoller(sqs, QUEUE, handler, pauses::add);
    }

    /** 組一則 SQS 訊息 */
    private static Message message(String body, String receipt) {
        return Message.builder().body(body).receiptHandle(receipt).messageId(receipt).build();
    }

    /** 回傳指定訊息的收取結果 */
    private void receive(Message... messages) {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenReturn(ReceiveMessageResponse.builder().messages(messages).build());
    }

    /** 長輪詢參數：一次最多 10 則、等 20 秒 */
    @Test
    void usesLongPolling() {
        receive();

        poller.pollOnce();

        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqs).receiveMessage(captor.capture());
        assertEquals(QUEUE, captor.getValue().queueUrl());
        assertEquals(20, captor.getValue().waitTimeSeconds());
        assertEquals(10, captor.getValue().maxNumberOfMessages());
    }

    /** 處理成功（NOTIFIED／IGNORED／CONFIRMED）：刪除訊息 */
    @Test
    void deletesHandledMessages() {
        receive(message("a", "r-a"), message("b", "r-b"), message("c", "r-c"));
        when(handler.handle("a")).thenReturn(SnsWebhookHandler.Result.NOTIFIED);
        when(handler.handle("b")).thenReturn(SnsWebhookHandler.Result.IGNORED);
        when(handler.handle("c")).thenReturn(SnsWebhookHandler.Result.CONFIRMED);

        assertEquals(3, poller.pollOnce());

        ArgumentCaptor<DeleteMessageRequest> captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs, times(3)).deleteMessage(captor.capture());
        assertEquals(List.of("r-a", "r-b", "r-c"),
            captor.getAllValues().stream().map(DeleteMessageRequest::receiptHandle).toList());
    }

    /** 處理失敗或被拒：不刪除，等可見逾時後 SQS 重送，超過次數進死信佇列 */
    @Test
    void keepsFailedAndRejectedMessages() {
        receive(message("f", "r-f"), message("x", "r-x"));
        when(handler.handle("f")).thenReturn(SnsWebhookHandler.Result.FAILED);
        when(handler.handle("x")).thenReturn(SnsWebhookHandler.Result.REJECTED);

        assertEquals(0, poller.pollOnce());

        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    /** handler 意外拋例外：同樣不刪除，也不中斷這一批的其他訊息 */
    @Test
    void handlerExceptionKeepsMessage() {
        receive(message("boom", "r-1"), message("ok", "r-2"));
        when(handler.handle("boom")).thenThrow(new IllegalStateException("bug"));
        when(handler.handle("ok")).thenReturn(SnsWebhookHandler.Result.NOTIFIED);

        assertEquals(1, poller.pollOnce());

        ArgumentCaptor<DeleteMessageRequest> captor = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqs).deleteMessage(captor.capture());
        assertEquals("r-2", captor.getValue().receiptHandle());
    }

    /** 收取失敗（網路、權限）：退避 30 秒，不空轉 */
    @Test
    void backsOffOnReceiveError() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
            .thenThrow(SdkClientException.create("network"));

        assertEquals(0, poller.pollOnce());

        assertEquals(List.of(30_000L), pauses);
    }

    /** 迴圈中拋出意外錯誤（例如 Error）不能讓拉取執行緒悄悄停掉：退避後繼續下一輪 */
    @Test
    void loopSurvivesUnexpectedError() throws Exception {
        java.util.concurrent.CountDownLatch secondPoll = new java.util.concurrent.CountDownLatch(2);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            secondPoll.countDown();
            if (secondPoll.getCount() == 1) {
                throw new AssertionError("unexpected");
            }
            return ReceiveMessageResponse.builder().build();
        });
        SqsEventPoller looping = new SqsEventPoller(sqs, QUEUE, handler, millis -> { });

        looping.start();
        try {
            assertEquals(true, secondPoll.await(5, java.util.concurrent.TimeUnit.SECONDS), "第一輪出錯後必須繼續拉取");
            assertEquals(true, looping.isRunning());
        } finally {
            looping.stop();
        }
    }
}
