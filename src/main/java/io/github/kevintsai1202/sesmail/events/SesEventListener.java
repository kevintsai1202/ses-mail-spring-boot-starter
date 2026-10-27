package io.github.kevintsai1202.sesmail.events;

/**
 * 退信／檢舉事件的回呼。應用程式宣告成 bean 即會被呼叫（可以有多個）。
 *
 * <p>SNS／SQS 可能重送同一則通知，實作必須是冪等的（例如「把這個信箱標為退訂」重做一次沒有副作用）。
 * 實作拋出例外時，SQS 模式會保留訊息稍後重試（多次失敗進死信佇列）；webhook 模式只記 log、仍回 200。</p>
 */
public interface SesEventListener {

    /** 收到退信；永久退信請用 {@link SesBounce#permanent()} 判斷 */
    default void onBounce(SesBounce bounce) {
    }

    /** 收到檢舉 */
    default void onComplaint(SesComplaint complaint) {
    }

    /** 收件端已收下信件（需要在 configuration set 開啟 Delivery 事件） */
    default void onDelivery(SesDelivery delivery) {
    }
}
