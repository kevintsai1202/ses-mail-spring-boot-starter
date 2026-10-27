package io.github.kevintsai1202.sesmail;

/**
 * SES 額度快照。
 *
 * @param status      帳號狀態：sandbox（尚未離開沙箱）／paused（寄送被暫停）／其餘為 enforcementStatus 小寫（healthy…）
 * @param max24h      24 小時內可寄上限
 * @param sent24h     過去 24 小時已寄
 * @param pending     本機佇列中尚未送出的封數
 * @param maxSendRate SES 帳號每秒上限
 */
public record SesQuotaSnapshot(String status, long max24h, long sent24h, long pending, double maxSendRate) {

    /**
     * 實際還能排進來的封數 = 上限 − 已寄 − 佇列待寄，不為負。
     *
     * <p>一定要扣佇列：SES 的已寄量看不到還在佇列裡的信，不扣的話，
     * 群發剛入列、額度一查還是群發前的數字，下一批會被放行而超量。</p>
     */
    public long remaining() {
        return Math.max(0, max24h - sent24h - pending);
    }
}
