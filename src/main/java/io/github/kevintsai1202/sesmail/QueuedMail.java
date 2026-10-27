package io.github.kevintsai1202.sesmail;

import java.time.OffsetDateTime;

/**
 * 待寄佇列中的一封信（資料表 scheduled_mail 的一列）。
 *
 * @param id                佇列 id
 * @param batchRef          批次群發的整批參照；排程單封為 null
 * @param recipient         收件人
 * @param subject           主旨
 * @param html              完整 HTML 內文
 * @param status            狀態（見 STATUS_* 常數）
 * @param attempts          已嘗試次數（認領時 +1）
 * @param providerMessageId 送出後 SES 回傳的 MessageId
 * @param nextAttemptAt     下次可嘗試時間
 */
public record QueuedMail(long id, String batchRef, String recipient, String subject, String html,
                         String status, int attempts, String providerMessageId,
                         OffsetDateTime nextAttemptAt) {

    /** 待寄：等待到期 */
    public static final String STATUS_PENDING = "PENDING";
    /** 派送器已認領、正在呼叫 SES */
    public static final String STATUS_SENDING = "SENDING";
    /** 已交給 SES */
    public static final String STATUS_SENT = "SENT";
    /** 放棄重試或被 SES 拒絕 */
    public static final String STATUS_FAILED = "FAILED";
    /** 到期前被取消 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 排程單封回傳給呼叫端的參照前綴（後接佇列 id） */
    public static final String SCHEDULE_PREFIX = "ses-sched-";
    /** 批次群發回傳給呼叫端的整批參照前綴 */
    public static final String BATCH_PREFIX = "ses-batch-";

    /**
     * 呼叫端手上的參照：批次信是整批參照，排程單封是 ses-sched-{id}。
     * 應用程式若有寄送記錄，存的就是這個值，派送結果靠它對回去。
     */
    public String providerRef() {
        return batchRef != null ? batchRef : SCHEDULE_PREFIX + id;
    }
}
