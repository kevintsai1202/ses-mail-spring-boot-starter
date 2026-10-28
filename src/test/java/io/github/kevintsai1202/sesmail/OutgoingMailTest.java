package io.github.kevintsai1202.sesmail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** OutgoingMail：退訂網址選填，不合規的網址在建立時就拒絕，不等到寄出才失敗 */
class OutgoingMailTest {

    /** 原本的三個參數寫法照常可用，沒有退訂網址 */
    @Test
    void threeArgumentFormHasNoUnsubscribeUrl() {
        OutgoingMail mail = new OutgoingMail("a@example.com", "s", "<p>h</p>");

        assertNull(mail.unsubscribeUrl());
    }

    /** https 網址原樣保留 */
    @Test
    void keepsHttpsUnsubscribeUrl() {
        OutgoingMail mail = new OutgoingMail("a@example.com", "s", "<p>h</p>",
            "https://example.com/unsubscribe?email=a%40example.com&token=abc");

        assertEquals("https://example.com/unsubscribe?email=a%40example.com&token=abc", mail.unsubscribeUrl());
    }

    /** 空白視同沒有退訂網址 */
    @Test
    void blankUnsubscribeUrlBecomesNull() {
        assertNull(new OutgoingMail("a@example.com", "s", "<p>h</p>", "  ").unsubscribeUrl());
    }

    /** 一鍵退訂只接受 https */
    @Test
    void rejectsNonHttpsUrl() {
        assertThrows(IllegalArgumentException.class,
            () -> new OutgoingMail("a@example.com", "s", "<p>h</p>", "http://example.com/unsubscribe"));
        assertThrows(IllegalArgumentException.class,
            () -> new OutgoingMail("a@example.com", "s", "<p>h</p>", "mailto:unsubscribe@example.com"));
    }

    /** SES 的標頭值只能是可列印 ASCII：中文、空白、換行都要先做 URL 編碼 */
    @Test
    void rejectsNonPrintableAsciiUrl() {
        assertThrows(IllegalArgumentException.class,
            () -> new OutgoingMail("a@example.com", "s", "<p>h</p>", "https://example.com/退訂"));
        assertThrows(IllegalArgumentException.class,
            () -> new OutgoingMail("a@example.com", "s", "<p>h</p>", "https://example.com/a b"));
        assertThrows(IllegalArgumentException.class,
            () -> new OutgoingMail("a@example.com", "s", "<p>h</p>", "https://example.com/a\r\nBcc: x@example.com"));
    }

    /** 標頭值是 <網址>，SES 上限 995 字，所以網址最多 993 字 */
    @Test
    void limitsUrlLength() {
        String prefix = "https://example.com/";
        String ok = prefix + "a".repeat(993 - prefix.length());
        String tooLong = ok + "a";

        assertEquals(ok, new OutgoingMail("a@example.com", "s", "<p>h</p>", ok).unsubscribeUrl());
        assertThrows(IllegalArgumentException.class,
            () -> new OutgoingMail("a@example.com", "s", "<p>h</p>", tooLong));
    }
}
