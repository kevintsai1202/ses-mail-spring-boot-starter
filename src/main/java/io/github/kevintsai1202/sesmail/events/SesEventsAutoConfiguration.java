package io.github.kevintsai1202.sesmail.events;

import io.github.kevintsai1202.sesmail.SesMailProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.messagemanager.sns.SnsMessageManager;
import software.amazon.awssdk.regions.Region;

import java.time.Duration;
import java.util.LinkedHashSet;

/**
 * 退信／檢舉事件自動設定：{@code sesmail.sns.enabled=true} 時建立 SNS 驗證器、處理器與 webhook 端點。
 *
 * <p>與寄信（{@code sesmail.enabled}）互相獨立。必須設定允許的 topic ARN，否則啟動失敗。</p>
 */
@AutoConfiguration
@ConditionalOnClass(SnsMessageManager.class)
@ConditionalOnProperty(prefix = "sesmail.sns", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SesMailProperties.class)
public class SesEventsAutoConfiguration {

    /** SDK 官方驗證器，下載憑證用 URLConnection client */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public SnsMessageVerifier sesMailSnsMessageVerifier(SesMailProperties properties) {
        return new SdkSnsMessageVerifier(Region.of(region(properties)), UrlConnectionHttpClient.builder()
            .connectionTimeout(Duration.ofSeconds(5))
            .socketTimeout(Duration.ofSeconds(10))
            .build());
    }

    /** 訂閱確認（GET SubscribeURL） */
    @Bean
    @ConditionalOnMissingBean
    public SubscriptionConfirmer sesMailSubscriptionConfirmer() {
        return SubscriptionConfirmer.http();
    }

    /** SES 事件 JSON 解析 */
    @Bean
    @ConditionalOnMissingBean
    public SesEventParser sesEventParser() {
        return new SesEventParser();
    }

    /** webhook 處理器；沒設允許的 topic 時啟動失敗 */
    @Bean
    @ConditionalOnMissingBean
    public SnsWebhookHandler snsWebhookHandler(SnsMessageVerifier verifier, SubscriptionConfirmer confirmer,
                                               SesEventParser parser, ObjectProvider<SesEventListener> listeners,
                                               SesMailProperties properties) {
        return new SnsWebhookHandler(verifier, new LinkedHashSet<>(properties.getSns().getTopicArns()),
            region(properties), confirmer, parser, () -> listeners.orderedStream().toList());
    }

    /** topic 區域：未設定時沿用 sesmail.region */
    private static String region(SesMailProperties properties) {
        String region = properties.getSns().getRegion();
        return region == null || region.isBlank() ? properties.getRegion() : region;
    }

    /** webhook 端點：只在 Servlet Web 應用程式註冊 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(RestController.class)
    static class WebhookEndpointConfiguration {

        /** SNS webhook controller */
        @Bean
        @ConditionalOnMissingBean
        public SnsWebhookController snsWebhookController(SnsWebhookHandler handler) {
            return new SnsWebhookController(handler);
        }
    }
}
