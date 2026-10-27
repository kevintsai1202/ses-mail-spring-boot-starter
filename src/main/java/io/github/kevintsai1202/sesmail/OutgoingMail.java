package io.github.kevintsai1202.sesmail;

/**
 * 要寄出的一封信（寄件人、回信地址由套件設定提供）。
 *
 * @param to      收件人
 * @param subject 主旨
 * @param html    已個人化的完整 HTML 內文
 */
public record OutgoingMail(String to, String subject, String html) {
}
