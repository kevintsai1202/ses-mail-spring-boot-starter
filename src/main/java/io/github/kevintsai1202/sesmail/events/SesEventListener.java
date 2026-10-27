package io.github.kevintsai1202.sesmail.events;

/**
 * 退信／檢舉事件的回呼。應用程式宣告成 bean 即會被呼叫（可以有多個）。
 *
 * <p>SNS 可能重送同一則通知，實作必須是冪等的（例如「把這個信箱標為退訂」重做一次沒有副作用）。
 * 實作拋出的例外只記 log，webhook 仍回 200，避免 SNS 無止盡重送。</p>
 */
public interface SesEventListener {

    /** 收到退信；永久退信請用 {@link SesBounce#permanent()} 判斷 */
    default void onBounce(SesBounce bounce) {
    }

    /** 收到檢舉 */
    default void onComplaint(SesComplaint complaint) {
    }
}
