package io.github.kevintsai1202.sesmail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ScheduledMailDispatcher：撈到期待寄信、依速率送出、依錯誤種類決定重試或失敗、通知 listener */
class ScheduledMailDispatcherTest {

    /** 固定的「現在」 */
    private static final Instant NOW = Instant.parse("2026-10-27T08:00:00Z");
    private static final OffsetDateTime NOW_ODT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private ScheduledMailQueue queue;
    private SesMailClient client;
    private DeliveryListener listener;
    /** 記錄派送器每次暫停的毫秒數，驗證速率限制 */
    private List<Long> pauses;
    private ScheduledMailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        queue = mock(ScheduledMailQueue.class);
        client = mock(SesMailClient.class);
        listener = mock(DeliveryListener.class);
        pauses = new ArrayList<>();
        dispatcher = new ScheduledMailDispatcher(queue, client, () -> List.of(listener), 5.0,
            Duration.ofSeconds(10), Clock.fixed(NOW, ZoneOffset.UTC), pauses::add);
    }

    /** 建一筆已被認領（attempts 已 +1）的待寄信 */
    private QueuedMail claimedRow(long id, String batchRef, int attempts) {
        return claimedRow(id, batchRef, attempts, null);
    }

    /** 同上，帶退訂網址 */
    private QueuedMail claimedRow(long id, String batchRef, int attempts, String unsubscribeUrl) {
        QueuedMail row = new QueuedMail(id, batchRef, "reader" + id + "@example.com",
            "主旨", "<p>內文</p>", unsubscribeUrl, QueuedMail.STATUS_SENDING, attempts, null, NOW_ODT);
        when(queue.find(id)).thenReturn(Optional.of(row));
        return row;
    }

    /** 成功：認領 → 寄出 → 記下 MessageId → 通知 onSent */
    @Test
    void sendsDueMailAndNotifies() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(1L));
        when(queue.claim(1L)).thenReturn(1);
        claimedRow(1L, null, 1);
        when(client.deliver("reader1@example.com", "主旨", "<p>內文</p>", null)).thenReturn("ses-msg-1");

        assertEquals(1, dispatcher.dispatchDue());

        verify(queue).markSent(1L, "ses-msg-1");
        verify(listener).onSent("ses-sched-1", "reader1@example.com", "ses-msg-1");
    }

    /** 認領失敗（剛被取消或已被處理）：不寄、不改狀態 */
    @Test
    void skipsRowThatCannotBeClaimed() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(2L));
        when(queue.claim(2L)).thenReturn(0);

        assertEquals(0, dispatcher.dispatchDue());

        verify(client, never()).deliver(anyString(), anyString(), anyString());
        verify(queue, never()).markSent(anyLong(), anyString());
    }

    /** SES 節流：退回待寄，第一次重試延後 30 秒，不通知失敗 */
    @Test
    void throttlingIsRetriedLater() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(3L));
        when(queue.claim(3L)).thenReturn(1);
        claimedRow(3L, null, 1);
        when(client.deliver(anyString(), anyString(), anyString(), any()))
            .thenThrow(TooManyRequestsException.builder().message("slow down").build());

        dispatcher.dispatchDue();

        verify(queue).retryLater(eq(3L), anyString(), eq(NOW_ODT.plusSeconds(30)));
        verify(queue, never()).markFailed(anyLong(), anyString());
        verify(listener, never()).onFailed(anyString(), anyString(), anyString());
    }

    /** 建一個 SDK 沒有對應類別的 SES 錯誤（例如超過速率時文件寫的 ThrottlingException） */
    private static SesV2Exception unmodeledError(int status, String errorCode, String message) {
        return (SesV2Exception) SesV2Exception.builder().statusCode(status)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).errorMessage(message).build())
            .build();
    }

    /** 超過每秒上限：SES 文件寫的錯誤碼是 ThrottlingException（4xx），也要退回待寄重試，不能當永久失敗 */
    @Test
    void unmodeledThrottlingErrorIsRetriedLater() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(11L));
        when(queue.claim(11L)).thenReturn(1);
        claimedRow(11L, null, 1);
        when(client.deliver(anyString(), anyString(), anyString(), any()))
            .thenThrow(unmodeledError(400, "ThrottlingException", "Maximum sending rate exceeded"));

        dispatcher.dispatchDue();

        verify(queue).retryLater(eq(11L), anyString(), eq(NOW_ODT.plusSeconds(30)));
        verify(queue, never()).markFailed(anyLong(), anyString());
        verify(listener, never()).onFailed(anyString(), anyString(), anyString());
    }

    /** 其他沒有對應類別的 4xx 錯誤仍是永久失敗：不因為放寬節流判斷而一律重試 */
    @Test
    void unmodeledClientErrorFailsImmediately() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(12L));
        when(queue.claim(12L)).thenReturn(1);
        claimedRow(12L, null, 1);
        when(client.deliver(anyString(), anyString(), anyString(), any()))
            .thenThrow(unmodeledError(400, "BadRequestException", "Invalid request"));

        dispatcher.dispatchDue();

        verify(queue).markFailed(eq(12L), anyString());
        verify(queue, never()).retryLater(anyLong(), anyString(), any());
        verify(listener).onFailed(eq("ses-sched-12"), eq("reader12@example.com"), anyString());
    }

    /** 網路錯誤也是暫時性的：第二次失敗退避加倍為 60 秒 */
    @Test
    void networkErrorBacksOffExponentially() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(4L));
        when(queue.claim(4L)).thenReturn(1);
        claimedRow(4L, null, 2);
        when(client.deliver(anyString(), anyString(), anyString(), any()))
            .thenThrow(SdkClientException.create("connection reset"));

        dispatcher.dispatchDue();

        verify(queue).retryLater(eq(4L), anyString(), eq(NOW_ODT.plusSeconds(60)));
    }

    /** 暫時性錯誤但已達重試上限：標為失敗並通知 onFailed */
    @Test
    void transientErrorGivesUpAfterMaxAttempts() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(5L));
        when(queue.claim(5L)).thenReturn(1);
        claimedRow(5L, null, ScheduledMailDispatcher.MAX_ATTEMPTS);
        when(client.deliver(anyString(), anyString(), anyString(), any()))
            .thenThrow(TooManyRequestsException.builder().message("slow down").build());

        dispatcher.dispatchDue();

        verify(queue).markFailed(eq(5L), anyString());
        verify(queue, never()).retryLater(anyLong(), anyString(), any());
        verify(listener).onFailed(eq("ses-sched-5"), eq("reader5@example.com"), anyString());
    }

    /** 被 SES 拒絕：不重試，直接失敗；批次信用整批參照通知 */
    @Test
    void rejectedMailFailsImmediately() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(6L));
        when(queue.claim(6L)).thenReturn(1);
        claimedRow(6L, "ses-batch-abc", 1);
        when(client.deliver(anyString(), anyString(), anyString(), any()))
            .thenThrow(MessageRejectedException.builder().message("Email address is not verified").build());

        dispatcher.dispatchDue();

        verify(queue).markFailed(eq(6L), anyString());
        verify(listener).onFailed(eq("ses-batch-abc"), eq("reader6@example.com"), anyString());
    }

    /** listener 自己拋例外不得中斷派送迴圈：下一封照寄 */
    @Test
    void listenerExceptionDoesNotStopLoop() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(7L, 8L));
        when(queue.claim(anyLong())).thenReturn(1);
        claimedRow(7L, null, 1);
        claimedRow(8L, null, 1);
        when(client.deliver(anyString(), anyString(), anyString(), any())).thenReturn("m");
        doThrow(new IllegalStateException("listener bug"))
            .when(listener).onSent(eq("ses-sched-7"), anyString(), anyString());

        assertEquals(2, dispatcher.dispatchDue());

        verify(queue).markSent(8L, "m");
    }

    /** 速率限制：每秒 5 封 → 每封之間暫停 200 毫秒 */
    @Test
    void pausesBetweenSendsToRespectRate() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(9L, 10L));
        when(queue.claim(anyLong())).thenReturn(1);
        claimedRow(9L, null, 1);
        claimedRow(10L, null, 1);
        when(client.deliver(anyString(), anyString(), anyString(), any())).thenReturn("m");

        assertEquals(2, dispatcher.dispatchDue());

        assertEquals(List.of(200L, 200L), pauses);
    }

    /** 程序中斷後卡在 SENDING 的列只回報數量，絕不自動重寄 */
    @Test
    void stuckRowsAreReportedNotResent() {
        when(queue.countStuck(NOW_ODT.minus(Duration.ofMinutes(10)))).thenReturn(3L);

        assertEquals(3L, dispatcher.reportStuck());

        verify(queue, never()).retryLater(anyLong(), anyString(), any());
        verify(client, never()).deliver(anyString(), anyString(), anyString());
    }

    /** 佇列裡的退訂網址要交給 deliver，才會變成一鍵退訂標頭 */
    @Test
    void passesUnsubscribeUrlToDeliver() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(13L));
        when(queue.claim(13L)).thenReturn(1);
        claimedRow(13L, "ses-batch-u", 1, "https://example.com/u?t=13");
        when(client.deliver(anyString(), anyString(), anyString(), any())).thenReturn("m");

        dispatcher.dispatchDue();

        verify(client).deliver("reader13@example.com", "主旨", "<p>內文</p>", "https://example.com/u?t=13");
    }

    /** 一輪撈滿 BATCH_LIMIT 封：不等派送間隔，直接再撈下一輪；撈不滿才停 */
    @Test
    void drainContinuesWhileRoundsAreFull() {
        List<Long> full = java.util.stream.LongStream.rangeClosed(1, ScheduledMailDispatcher.BATCH_LIMIT)
            .boxed().toList();
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(full, List.of(9001L), List.of());
        when(queue.claim(anyLong())).thenReturn(0);

        dispatcher.drain();

        verify(queue, org.mockito.Mockito.times(2)).findDueIds(eq(NOW_ODT), anyInt());
    }

    /** 撈不滿一輪：只跑一輪，下一輪等派送間隔 */
    @Test
    void drainStopsAfterPartialRound() {
        when(queue.findDueIds(eq(NOW_ODT), anyInt())).thenReturn(List.of(14L), List.of(15L));
        when(queue.claim(anyLong())).thenReturn(0);

        dispatcher.drain();

        verify(queue, org.mockito.Mockito.times(1)).findDueIds(eq(NOW_ODT), anyInt());
    }

    /** 清 HTML：保留期 7 天 → 清掉「現在 − 7 天」之前更新的已寄出與已取消信 */
    @Test
    void purgesHtmlOlderThanRetention() {
        ScheduledMailDispatcher withRetention = new ScheduledMailDispatcher(queue, client, () -> List.of(listener), 5.0,
            Duration.ofSeconds(10), Duration.ofDays(7), Clock.fixed(NOW, ZoneOffset.UTC), pauses::add);
        when(queue.clearHtml(NOW_ODT.minusDays(7))).thenReturn(3);

        assertEquals(3, withRetention.purgeHtml());
    }

    /** 保留期為 0 或未設定：不清 */
    @Test
    void purgeDisabledWithoutRetention() {
        ScheduledMailDispatcher zero = new ScheduledMailDispatcher(queue, client, () -> List.of(listener), 5.0,
            Duration.ofSeconds(10), Duration.ZERO, Clock.fixed(NOW, ZoneOffset.UTC), pauses::add);

        assertEquals(0, zero.purgeHtml());
        assertEquals(0, dispatcher.purgeHtml());
        verify(queue, never()).clearHtml(any());
    }

    /** 清理失敗不得往外拋：排程器碰到例外會靜默停掉之後的每一次執行 */
    @Test
    void purgeTickSwallowsErrors() {
        ScheduledMailDispatcher withRetention = new ScheduledMailDispatcher(queue, client, () -> List.of(listener), 5.0,
            Duration.ofSeconds(10), Duration.ofDays(7), Clock.fixed(NOW, ZoneOffset.UTC), pauses::add);
        when(queue.clearHtml(any())).thenThrow(new IllegalStateException("db down"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(withRetention::purgeTick);
    }

    /** 速率必須大於 0 */
    @Test
    void rejectsNonPositiveRate() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new ScheduledMailDispatcher(queue, client, List::of, 0,
                Duration.ofSeconds(10), Clock.systemUTC(), millis -> { }));
    }
}
