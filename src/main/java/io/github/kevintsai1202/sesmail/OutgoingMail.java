package io.github.kevintsai1202.sesmail;

/**
 * 要寄出的一封信（寄件人、回信地址由套件設定提供）。
 *
 * <p>有 {@code unsubscribeUrl} 時，寄出會加上 RFC 8058 一鍵退訂標頭
 * （{@code List-Unsubscribe}、{@code List-Unsubscribe-Post}）。這個網址必須能接受
 * {@code POST}、內容為 {@code List-Unsubscribe=One-Click} 的請求，且不要求登入、不轉址；
 * 退訂的實際處理由應用程式負責。</p>
 *
 * @param to             收件人
 * @param subject        主旨
 * @param html           已個人化的完整 HTML 內文
 * @param unsubscribeUrl 一鍵退訂網址（選填）：https、可列印 ASCII、最多 {@value #MAX_UNSUBSCRIBE_URL} 字；空白視同未提供
 */
public record OutgoingMail(String to, String subject, String html, String unsubscribeUrl) {

    /** 退訂網址長度上限：標頭值是 {@code <網址>}，SES 自訂標頭值最多 995 字 */
    public static final int MAX_UNSUBSCRIBE_URL = 993;

    /** 檢查退訂網址；不合規的在建立時就拒絕，不等到派送時才失敗 */
    public OutgoingMail {
        if (unsubscribeUrl != null && unsubscribeUrl.isBlank()) {
            unsubscribeUrl = null;
        }
        if (unsubscribeUrl != null) {
            if (!unsubscribeUrl.startsWith("https://")) {
                throw new IllegalArgumentException("退訂網址必須是 https：" + unsubscribeUrl);
            }
            if (unsubscribeUrl.length() > MAX_UNSUBSCRIBE_URL) {
                throw new IllegalArgumentException("退訂網址超過 " + MAX_UNSUBSCRIBE_URL + " 字");
            }
            if (!unsubscribeUrl.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
                throw new IllegalArgumentException("退訂網址只能有可列印 ASCII，中文與空白要先做 URL 編碼：" + unsubscribeUrl);
            }
        }
    }

    /** 沒有退訂網址的信（交易信、或不需要一鍵退訂的群發） */
    public OutgoingMail(String to, String subject, String html) {
        this(to, subject, html, null);
    }
}
