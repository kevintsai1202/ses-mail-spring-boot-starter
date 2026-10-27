package io.github.kevintsai1202.sesmail;

/**
 * 待寄佇列派送結果的回呼。應用程式宣告成 bean 即會被呼叫（可以有多個）。
 *
 * <p>只涵蓋經過佇列的信（{@link SesMailClient#enqueueBatch}、{@link SesMailClient#schedule}）；
 * {@link SesMailClient#send} 是同步呼叫，結果直接由回傳值或例外得知。
 * 實作拋出的例外只會被記 log，不會中斷派送。</p>
 */
public interface DeliveryListener {

    /**
     * 一封信已交給 SES。
     *
     * @param ref       呼叫端拿到的參照（ses-batch-* 或 ses-sched-*）
     * @param recipient 收件人
     * @param messageId SES 回傳的 MessageId
     */
    default void onSent(String ref, String recipient, String messageId) {
    }

    /**
     * 一封信放棄寄送（被 SES 拒絕，或暫時性錯誤重試到上限）。
     *
     * @param ref       呼叫端拿到的參照
     * @param recipient 收件人
     * @param error     錯誤摘要
     */
    default void onFailed(String ref, String recipient, String error) {
    }
}
