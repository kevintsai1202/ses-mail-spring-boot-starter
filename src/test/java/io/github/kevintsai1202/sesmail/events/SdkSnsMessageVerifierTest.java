package io.github.kevintsai1202.sesmail.events;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 實際用 AWS SDK 的 SnsMessageManager 驗證偽造訊息。
 *
 * <p>偽造訊息把簽章憑證網址指向非 AWS 主機，SDK 必須在下載憑證之前就拒絕（不連網）。
 * 這條測試確保我們真的有把驗證交給 SDK，而不是讓任何 JSON 都通過。</p>
 */
class SdkSnsMessageVerifierTest {

    /** 憑證網址不是該區域 SNS 主機的訊息：拒絕 */
    @Test
    void forgedCertificateUrlIsRejected() {
        String forged = """
            {"Type":"Notification","MessageId":"m","TopicArn":"arn:aws:sns:ap-northeast-1:123456789012:t",
             "Message":"{}","Timestamp":"2026-10-27T10:00:00.000Z","SignatureVersion":"2",
             "Signature":"c2lnbmF0dXJl","SigningCertURL":"https://evil.example.com/cert.pem"}
            """;
        try (SdkSnsMessageVerifier verifier = new SdkSnsMessageVerifier(Region.AP_NORTHEAST_1,
                UrlConnectionHttpClient.builder().build())) {
            assertThrows(RuntimeException.class, () -> verifier.verify(forged));
        }
    }
}
