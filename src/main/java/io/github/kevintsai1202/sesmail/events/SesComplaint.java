package io.github.kevintsai1202.sesmail.events;

import java.util.List;

/**
 * 檢舉事件：收件人把信標成垃圾信。
 *
 * @param ref          呼叫端參照（見 {@link SesEvent#ref()}）
 * @param recipients   檢舉的收件人
 * @param feedbackType 收件端回報的分類，例如 abuse；可能為 null
 * @param messageId    原信件的 SES MessageId
 */
public record SesComplaint(String ref, List<String> recipients, String feedbackType, String messageId)
        implements SesEvent {
}
