package io.github.kevintsai1202.sesmail.events;

import java.util.List;

/**
 * 送達事件：收件端郵件伺服器已收下信件（不代表進收件匣，也不代表被開啟）。
 *
 * @param ref        呼叫端參照（見 {@link SesEvent#ref()}）
 * @param recipients 已送達的收件人
 * @param messageId  原信件的 SES MessageId
 */
public record SesDelivery(String ref, List<String> recipients, String messageId) implements SesEvent {
}
