package io.github.kevintsai1202.sesmail.events;

import java.util.List;

/**
 * 退信事件。
 *
 * @param bounceType    Permanent（信箱不存在等，之後不該再寄）／Transient（信箱滿、暫時拒收）／Undetermined
 * @param bounceSubType 細分類，例如 General、NoEmail、MailboxFull
 * @param recipients    退信的收件人
 * @param messageId     原信件的 SES MessageId
 */
public record SesBounce(String bounceType, String bounceSubType, List<String> recipients, String messageId)
        implements SesEvent {

    /** 是否為永久退信：只有這種該停止再寄 */
    public boolean permanent() {
        return "Permanent".equalsIgnoreCase(bounceType);
    }
}
