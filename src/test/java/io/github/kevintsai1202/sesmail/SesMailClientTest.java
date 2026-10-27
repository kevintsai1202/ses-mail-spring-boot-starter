package io.github.kevintsai1202.sesmail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SesMailClient：交易信同步寄出，排程與批次群發寫入待寄佇列 */
class SesMailClientTest {

    /** 固定的「現在」，用來驗證立即群發的預定時間 */
    private static final Instant NOW = Instant.parse("2026-10-27T08:00:00Z");
    private static final OffsetDateTime NOW_ODT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private SesV2Client ses;
    private ScheduledMailQueue queue;
    private SesMailClient client;

    @BeforeEach
    void setUp() {
        ses = mock(SesV2Client.class);
        queue = mock(ScheduledMailQueue.class);
        client = new SesMailClient(ses, queue,
            "凱文大叔 <noreply@springai.world>", "kevin@springai.world", "newsletter",
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 單封寄送：主旨與內文都指定 UTF-8，寄件人經 RFC 2047 編碼，回傳 SES 的 MessageId */
    @Test
    void sendUsesUtf8AndEncodedSender() {
        when(ses.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(SendEmailResponse.builder().messageId("ses-msg-1").build());

        String id = client.send("reader@example.com", "第 030 期：Vibe Coding 之後", "<p>你好</p>");

        assertEquals("ses-msg-1", id);
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(captor.capture());
        SendEmailRequest request = captor.getValue();
        assertEquals(MailAddressEncoder.encode("凱文大叔 <noreply@springai.world>"), request.fromEmailAddress());
        assertEquals(List.of("reader@example.com"), request.destination().toAddresses());
        assertEquals(List.of("kevin@springai.world"), request.replyToAddresses());
        assertEquals("newsletter", request.configurationSetName());
        assertEquals("UTF-8", request.content().simple().subject().charset());
        assertEquals("第 030 期：Vibe Coding 之後", request.content().simple().subject().data());
        assertEquals("UTF-8", request.content().simple().body().html().charset());
        assertEquals("<p>你好</p>", request.content().simple().body().html().data());
    }

    /** 未設定回信地址與 configuration set 時，請求不帶這兩個欄位 */
    @Test
    void sendOmitsOptionalFields() {
        SesMailClient plain = new SesMailClient(ses, queue, "noreply@springai.world", "", null,
            Clock.fixed(NOW, ZoneOffset.UTC));
        when(ses.sendEmail(any(SendEmailRequest.class)))
            .thenReturn(SendEmailResponse.builder().messageId("ses-msg-2").build());

        plain.send("reader@example.com", "s", "<p>h</p>");

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(captor.capture());
        assertTrue(captor.getValue().replyToAddresses().isEmpty());
        assertNull(captor.getValue().configurationSetName());
    }

    /** 排程：只寫入佇列、不呼叫 SES，回傳可取消的 ses-sched-{id} */
    @Test
    void scheduleEnqueuesAndReturnsCancellableRef() {
        Instant at = Instant.parse("2026-10-28T01:00:00Z");
        OutgoingMail mail = new OutgoingMail("reader@example.com", "s", "<p>h</p>");
        when(queue.insert(null, mail, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))).thenReturn(42L);

        assertEquals("ses-sched-42", client.schedule(mail, at));
        verify(ses, never()).sendEmail(any(SendEmailRequest.class));
    }

    /** 批次群發：整批以同一個 ses-batch-* 參照寫入佇列、預定時間為現在，不同步呼叫 SES */
    @Test
    void enqueueBatchUsesSharedRef() {
        List<OutgoingMail> mails = List.of(
            new OutgoingMail("a@example.com", "s", "<p>a</p>"),
            new OutgoingMail("b@example.com", "s", "<p>b</p>"));

        String ref = client.enqueueBatch(mails);

        assertTrue(ref.startsWith(QueuedMail.BATCH_PREFIX), ref);
        verify(queue).insertAll(ref, mails, NOW_ODT);
        verify(ses, never()).sendEmail(any(SendEmailRequest.class));
    }

    /** 空批次直接拒絕，避免產生一個沒有任何信的參照 */
    @Test
    void enqueueBatchRejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> client.enqueueBatch(List.of()));
    }

    /** 取消：只認 ses-sched-*；其他服務的 id 或格式錯誤一律回 false，不碰資料庫 */
    @Test
    void cancelIgnoresForeignRefs() {
        assertFalse(client.cancel("zsend-abc"));
        assertFalse(client.cancel("ses-sched-notanumber"));
        assertFalse(client.cancel(null));
        verify(queue, never()).cancel(anyLong());
    }

    /** 取消自己的排程：只有條件式 UPDATE 改到一列（仍是 PENDING）才算成功 */
    @Test
    void cancelOwnScheduleOnlyWhilePending() {
        when(queue.cancel(42L)).thenReturn(1);
        when(queue.cancel(43L)).thenReturn(0);

        assertTrue(client.cancel("ses-sched-42"));
        assertFalse(client.cancel("ses-sched-43"));
        verify(queue, never()).find(eq(42L));
    }
}
