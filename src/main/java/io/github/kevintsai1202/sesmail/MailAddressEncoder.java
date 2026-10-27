package io.github.kevintsai1202.sesmail;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 寄件人位址的 RFC 2047 編碼。
 *
 * <p>Amazon SES 規定顯示名稱含非 ASCII 字元時必須寫成 MIME encoded-word
 * （{@code =?UTF-8?B?...?=}），否則直接拒收，例如 {@code 凱文大叔 <noreply@example.com>}。
 * 為了不引入 jakarta.mail，這裡只做寄件人這一種情境的最小編碼。</p>
 */
public final class MailAddressEncoder {

    /** 「顯示名稱 <位址>」：名稱可有可無雙引號 */
    private static final Pattern NAME_ADDR = Pattern.compile("^\\s*\"?(.*?)\"?\\s*<([^<>]+)>\\s*$");

    private MailAddressEncoder() {
    }

    /**
     * 把含非 ASCII 顯示名稱的位址編成 {@code =?UTF-8?B?...?= <addr>}；
     * 純 ASCII 名稱或沒有名稱的位址原樣回傳。
     */
    public static String encode(String address) {
        if (address == null) {
            return null;
        }
        Matcher m = NAME_ADDR.matcher(address);
        if (!m.matches()) {
            return address;
        }
        String name = m.group(1).trim();
        String addr = m.group(2).trim();
        if (name.isEmpty() || isAscii(name)) {
            return address;
        }
        String encoded = Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        return "=?UTF-8?B?" + encoded + "?= <" + addr + ">";
    }

    /** 是否全為 ASCII 字元 */
    private static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 128);
    }
}
