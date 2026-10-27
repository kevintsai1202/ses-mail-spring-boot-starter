package io.github.kevintsai1202.sesmail.events;

import java.util.List;

/**
 * 檢舉事件：收件人把信標成垃圾信。
 *
 * @param recipients   檢舉的收件人
 * @param feedbackType 收件端回報的分類，例如 abuse；可能為 null
 * @param messageId    原信件的 SES MessageId
 */
public record SesComplaint(List<String> recipients, String feedbackType, String messageId) implements SesEvent {
}
