package io.github.kevintsai1202.sesmail;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code sesmail.*} 設定 */
@ConfigurationProperties(prefix = "sesmail")
public class SesMailProperties {

    /** 是否啟用；預設 false，加了依賴也不會有任何作用，直到明確開啟 */
    private boolean enabled = false;
    /** SES 區域 */
    private String region = "ap-northeast-1";
    /** IAM 存取金鑰 ID；與 secretAccessKey 都空白時改用 AWS SDK 預設憑證鏈（環境變數、IAM role…） */
    private String accessKeyId;
    /** IAM 存取金鑰 Secret */
    private String secretAccessKey;
    /** 寄件人，例如 {@code 凱文大叔 <noreply@example.com>}；中文名稱會自動做 RFC 2047 編碼 */
    private String from;
    /** 回信地址，選填 */
    private String replyTo;
    /** SES configuration set，選填（接退信／檢舉事件時使用） */
    private String configurationSet;
    /** 待寄佇列每秒最多送幾封，不要高於 SES 帳號的 Maximum send rate（沙箱為 1） */
    private double maxSendRate = 5;
    /** 派送器多久掃一次佇列 */
    private Duration dispatchInterval = Duration.ofSeconds(10);
    /** 佇列設定 */
    private final Queue queue = new Queue();

    /** 佇列相關設定 */
    public static class Queue {
        /** 啟動時以 IF NOT EXISTS 建立 scheduled_mail 表；用 Flyway 的專案請保持 false、把 SQL 放進自己的 migration */
        private boolean initializeSchema = false;

        public boolean isInitializeSchema() { return initializeSchema; }
        public void setInitializeSchema(boolean initializeSchema) { this.initializeSchema = initializeSchema; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
    public String getSecretAccessKey() { return secretAccessKey; }
    public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getReplyTo() { return replyTo; }
    public void setReplyTo(String replyTo) { this.replyTo = replyTo; }
    public String getConfigurationSet() { return configurationSet; }
    public void setConfigurationSet(String configurationSet) { this.configurationSet = configurationSet; }
    public double getMaxSendRate() { return maxSendRate; }
    public void setMaxSendRate(double maxSendRate) { this.maxSendRate = maxSendRate; }
    public Duration getDispatchInterval() { return dispatchInterval; }
    public void setDispatchInterval(Duration dispatchInterval) { this.dispatchInterval = dispatchInterval; }
    public Queue getQueue() { return queue; }
}
