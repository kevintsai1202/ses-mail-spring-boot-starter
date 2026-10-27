package io.github.kevintsai1202.sesmail;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** MailAddressEncoder：寄件人顯示名稱的 RFC 2047 編碼（SES 不接受未編碼的非 ASCII 名稱） */
class MailAddressEncoderTest {

    /** 線上實際使用的預設寄件人：中文名稱必須編成 =?UTF-8?B?…?=，信箱位址原樣保留 */
    @Test
    void encodesNonAsciiDisplayName() {
        String expectedName = "=?UTF-8?B?"
            + Base64.getEncoder().encodeToString("凱文大叔".getBytes(StandardCharsets.UTF_8)) + "?=";
        assertEquals(expectedName + " <noreply@springai.world>",
            MailAddressEncoder.encode("凱文大叔 <noreply@springai.world>"));
    }

    /** 雙引號包住的中文名稱：去掉引號後再編碼 */
    @Test
    void encodesQuotedNonAsciiDisplayName() {
        String expectedName = "=?UTF-8?B?"
            + Base64.getEncoder().encodeToString("凱文大叔".getBytes(StandardCharsets.UTF_8)) + "?=";
        assertEquals(expectedName + " <noreply@springai.world>",
            MailAddressEncoder.encode("\"凱文大叔\" <noreply@springai.world>"));
    }

    /** 純 ASCII 的顯示名稱不需要編碼，原樣回傳 */
    @Test
    void keepsAsciiDisplayName() {
        assertEquals("Kevin <noreply@springai.world>",
            MailAddressEncoder.encode("Kevin <noreply@springai.world>"));
    }

    /** 沒有顯示名稱的純位址原樣回傳 */
    @Test
    void keepsBareAddress() {
        assertEquals("noreply@springai.world", MailAddressEncoder.encode("noreply@springai.world"));
    }
}
