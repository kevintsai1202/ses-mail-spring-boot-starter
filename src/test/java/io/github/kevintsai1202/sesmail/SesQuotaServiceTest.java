package io.github.kevintsai1202.sesmail;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.SendQuota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SesQuotaService：把 SES GetAccount 與佇列待寄數整理成額度快照 */
class SesQuotaServiceTest {

    /** 組一份 GetAccount 回應 */
    private static GetAccountResponse account(boolean production, boolean sendingEnabled,
                                              String enforcement, double max, double sent) {
        return GetAccountResponse.builder()
            .productionAccessEnabled(production)
            .sendingEnabled(sendingEnabled)
            .enforcementStatus(enforcement)
            .sendQuota(SendQuota.builder().max24HourSend(max).sentLast24Hours(sent).maxSendRate(14.0).build())
            .build();
    }

    /** 正式環境：狀態取 enforcementStatus（小寫），待寄數來自佇列，剩餘量扣掉待寄 */
    @Test
    void productionAccount() {
        SesV2Client ses = mock(SesV2Client.class);
        ScheduledMailQueue queue = mock(ScheduledMailQueue.class);
        when(ses.getAccount(any(GetAccountRequest.class))).thenReturn(account(true, true, "HEALTHY", 50000, 1200));
        when(queue.countPending()).thenReturn(310L);

        SesQuotaSnapshot s = new SesQuotaService(ses, queue).snapshot();

        assertEquals(new SesQuotaSnapshot("healthy", 50000, 1200, 310, 14.0), s);
        assertEquals(48490, s.remaining());
    }

    /** 還在沙箱：狀態標 sandbox */
    @Test
    void sandboxAccount() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.getAccount(any(GetAccountRequest.class))).thenReturn(account(false, true, "HEALTHY", 200, 3));

        assertEquals("sandbox", new SesQuotaService(ses, mock(ScheduledMailQueue.class)).snapshot().status());
    }

    /** 寄送被暫停：狀態標 paused */
    @Test
    void pausedAccount() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.getAccount(any(GetAccountRequest.class))).thenReturn(account(true, false, "SHUTDOWN", 50000, 0));

        assertEquals("paused", new SesQuotaService(ses, mock(ScheduledMailQueue.class)).snapshot().status());
    }

    /** max24HourSend 為 -1 代表不限；已寄加待寄超過上限時剩餘量不為負 */
    @Test
    void unlimitedAndNeverNegative() {
        SesV2Client ses = mock(SesV2Client.class);
        ScheduledMailQueue queue = mock(ScheduledMailQueue.class);
        when(ses.getAccount(any(GetAccountRequest.class))).thenReturn(account(true, true, "HEALTHY", -1, 0));
        assertEquals(SesQuotaService.UNLIMITED, new SesQuotaService(ses, queue).snapshot().max24h());

        assertEquals(0, new SesQuotaSnapshot("healthy", 200, 180, 50, 1).remaining());
    }
}
