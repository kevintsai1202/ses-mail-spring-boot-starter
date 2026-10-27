package io.github.kevintsai1202.sesmail.events;

import java.util.List;

/** 從 SNS 通知解析出來、需要應用程式處理的 SES 事件（退信、檢舉、送達） */
public sealed interface SesEvent permits SesBounce, SesComplaint, SesDelivery {

    /**
     * 呼叫端參照：佇列寄出的信是 ses-batch-*／ses-sched-*（由 starter 用 MessageId 反查），
     * 交易信或查不到時等於 SES MessageId。應用程式的寄送記錄存的就是這個值。
     */
    String ref();

    /** 事件涉及的收件人 */
    List<String> recipients();

    /** 原信件的 SES MessageId */
    String messageId();
}
