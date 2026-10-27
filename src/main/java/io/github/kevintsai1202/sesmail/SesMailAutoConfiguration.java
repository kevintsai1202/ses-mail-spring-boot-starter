package io.github.kevintsai1202.sesmail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * SES 寄信自動設定：{@code sesmail.enabled=true} 時建立客戶端、佇列、派送器與額度服務。
 *
 * <p>需要應用程式提供 {@link DataSource}（佇列存在資料庫）。每個 bean 都可由應用程式自行覆蓋。</p>
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(SesV2Client.class)
@ConditionalOnProperty(prefix = "sesmail", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SesMailProperties.class)
public class SesMailAutoConfiguration {

    /**
     * SES v2 客戶端：URLConnection HTTP client 與保守的逾時。
     * 金鑰兩個都有給才用靜態憑證，否則走 SDK 預設憑證鏈（在 AWS 上跑的服務可以用 IAM role）。
     */
    @Bean
    @ConditionalOnMissingBean
    public SesV2Client sesMailSesV2Client(SesMailProperties properties) {
        return SesV2Client.builder()
            .region(Region.of(properties.getRegion()))
            .credentialsProvider(credentials(properties))
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(5))
                .socketTimeout(Duration.ofSeconds(20)))
            .overrideConfiguration(ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(Duration.ofSeconds(25))
                .apiCallTimeout(Duration.ofSeconds(30))
                .build())
            .build();
    }

    /** 待寄佇列；需要時在啟動時建表 */
    @Bean
    @ConditionalOnMissingBean
    public ScheduledMailQueue scheduledMailQueue(DataSource dataSource, SesMailProperties properties) {
        if (properties.getQueue().isInitializeSchema()) {
            new ResourceDatabasePopulator(new ClassPathResource(ScheduledMailQueue.SCHEMA_LOCATION))
                .execute(dataSource);
        }
        return new ScheduledMailQueue(dataSource);
    }

    /** 寄信主要入口；沒設寄件人直接啟動失敗，避免到第一封信才發現 */
    @Bean
    @ConditionalOnMissingBean
    public SesMailClient sesMailClient(SesV2Client ses, ScheduledMailQueue queue, SesMailProperties properties) {
        if (!hasText(properties.getFrom())) {
            throw new IllegalStateException("sesmail.enabled=true 但 sesmail.from（寄件人）未設定");
        }
        return new SesMailClient(ses, queue, properties.getFrom(), properties.getReplyTo(),
            properties.getConfigurationSet(), Clock.systemUTC());
    }

    /** 派送器：自帶單執行緒排程器，隨 Spring context 啟動與停止 */
    @Bean
    @ConditionalOnMissingBean
    public ScheduledMailDispatcher scheduledMailDispatcher(ScheduledMailQueue queue, SesMailClient client,
                                                           ObjectProvider<DeliveryListener> listeners,
                                                           SesMailProperties properties) {
        return new ScheduledMailDispatcher(queue, client, () -> listeners.orderedStream().toList(),
            properties.getMaxSendRate(), properties.getDispatchInterval(), Clock.systemUTC(), Thread::sleep);
    }

    /** 額度查詢 */
    @Bean
    @ConditionalOnMissingBean
    public SesQuotaService sesQuotaService(SesV2Client ses, ScheduledMailQueue queue) {
        return new SesQuotaService(ses, queue);
    }

    /** 兩把金鑰都有給才用靜態憑證，否則用 SDK 預設憑證鏈；SQS 客戶端共用 */
    public static AwsCredentialsProvider credentials(SesMailProperties properties) {
        if (hasText(properties.getAccessKeyId()) && hasText(properties.getSecretAccessKey())) {
            return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    /** 非 null 且非空白 */
    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
