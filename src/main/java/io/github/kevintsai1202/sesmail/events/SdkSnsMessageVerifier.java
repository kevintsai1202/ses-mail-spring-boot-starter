package io.github.kevintsai1202.sesmail.events;

import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.messagemanager.sns.SnsMessageManager;
import software.amazon.awssdk.messagemanager.sns.model.SnsMessage;
import software.amazon.awssdk.regions.Region;

/**
 * 以 AWS SDK 官方的 {@link SnsMessageManager} 驗證 SNS 訊息。
 *
 * <p>SDK 會檢查簽章憑證網址必須是該區域的 SNS 主機、憑證的主機名稱與有效期，
 * 再用憑證驗證簽章（SignatureVersion 1／2）。不自己實作，避免漏掉任何一項。</p>
 */
public class SdkSnsMessageVerifier implements SnsMessageVerifier, AutoCloseable {

    private final SnsMessageManager manager;
    /** 下載簽章憑證用的 HTTP client，由本類負責關閉 */
    private final SdkHttpClient httpClient;

    /**
     * @param region     SNS topic 所在區域；憑證主機依此判斷
     * @param httpClient 下載憑證用的 HTTP client（交由本類關閉）
     */
    public SdkSnsMessageVerifier(Region region, SdkHttpClient httpClient) {
        this.httpClient = httpClient;
        this.manager = SnsMessageManager.builder().region(region).httpClient(httpClient).build();
    }

    /** 驗證簽章並解析 */
    @Override
    public SnsMessage verify(String body) {
        return manager.parseMessage(body);
    }

    /** 釋放 SDK 資源與 HTTP client */
    @Override
    public void close() {
        manager.close();
        httpClient.close();
    }
}
