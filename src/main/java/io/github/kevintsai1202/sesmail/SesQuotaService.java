package io.github.kevintsai1202.sesmail;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.SendQuota;

import java.util.Locale;

/**
 * 查 SES 帳號額度，並附上本機佇列裡還沒送出的封數。
 *
 * <p>每次呼叫都會打一次 SES API，需要的話請在應用程式端快取。
 * 保留多少額度給交易信之類的策略由應用程式決定，這裡只回傳事實。</p>
 */
public class SesQuotaService {

    /** max24HourSend 為 -1（不限）時採用的數字：夠大、又不會在相減時溢位 */
    public static final long UNLIMITED = 1_000_000_000L;

    private final SesV2Client ses;
    private final ScheduledMailQueue queue;

    /** 注入 SES 客戶端與待寄佇列 */
    public SesQuotaService(SesV2Client ses, ScheduledMailQueue queue) {
        this.ses = ses;
        this.queue = queue;
    }

    /** 呼叫 GetAccount 並組成快照；失敗直接拋出，由呼叫端決定退路 */
    public SesQuotaSnapshot snapshot() {
        GetAccountResponse account = ses.getAccount(GetAccountRequest.builder().build());
        SendQuota quota = account.sendQuota();
        long max = quota == null || quota.max24HourSend() == null ? 0 : quota.max24HourSend().longValue();
        long sent = quota == null || quota.sentLast24Hours() == null ? 0 : quota.sentLast24Hours().longValue();
        double rate = quota == null || quota.maxSendRate() == null ? 0 : quota.maxSendRate();
        return new SesQuotaSnapshot(status(account), max < 0 ? UNLIMITED : max, sent, queue.countPending(), rate);
    }

    /** 沙箱與暫停優先顯示，其餘用 AWS 的 enforcementStatus（HEALTHY／PROBATION／SHUTDOWN） */
    private static String status(GetAccountResponse account) {
        if (!Boolean.TRUE.equals(account.productionAccessEnabled())) {
            return "sandbox";
        }
        if (!Boolean.TRUE.equals(account.sendingEnabled())) {
            return "paused";
        }
        String enforcement = account.enforcementStatus();
        return enforcement == null ? "unknown" : enforcement.toLowerCase(Locale.ROOT);
    }
}
