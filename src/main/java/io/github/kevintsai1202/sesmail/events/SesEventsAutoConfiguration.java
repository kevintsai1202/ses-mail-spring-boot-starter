package io.github.kevintsai1202.sesmail.events;

import io.github.kevintsai1202.sesmail.ScheduledMailQueue;
import io.github.kevintsai1202.sesmail.SesMailAutoConfiguration;
import io.github.kevintsai1202.sesmail.SesMailProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.messagemanager.sns.SnsMessageManager;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;
import java.util.LinkedHashSet;

/**
 * 退信／檢舉／送達事件自動設定。兩種接收方式共用同一套驗簽、topic 白名單與分派：
 * <ul>
 *   <li>{@code sesmail.sqs.enabled=true}（建議）：SNS → SQS → {@link SqsEventPoller} 拉取</li>
 *   <li>{@code sesmail.sns.enabled=true}：SNS 以 HTTPS 推送到 {@link SnsWebhookController}</li>
 * </ul>
 * 兩者都需要 {@code sesmail.sns.topic-arns}。有寄信佇列時，事件的 MessageId 會被對回呼叫端參照。
 */
@AutoConfiguration(after = SesMailAutoConfiguration.class)
@ConditionalOnClass(SnsMessageManager.class)
@Conditional(SesEventsAutoConfiguration.AnyReceiverEnabled.class)
@EnableConfigurationProperties(SesMailProperties.class)
public class SesEventsAutoConfiguration {

    /** webhook 或 SQS 任一啟用即成立 */
    static class AnyReceiverEnabled extends AnyNestedCondition {
        AnyReceiverEnabled() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "sesmail.sns", name = "enabled", havingValue = "true")
        static class WebhookEnabled {
        }

        @ConditionalOnProperty(prefix = "sesmail.sqs", name = "enabled", havingValue = "true")
        static class SqsEnabled {
        }
    }

    /** SDK 官方驗證器，下載憑證用 URLConnection client */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public SnsMessageVerifier sesMailSnsMessageVerifier(SesMailProperties properties) {
        return new SdkSnsMessageVerifier(Region.of(snsRegion(properties)), UrlConnectionHttpClient.builder()
            .connectionTimeout(Duration.ofSeconds(5))
            .socketTimeout(Duration.ofSeconds(10))
            .build());
    }

    /** 訂閱確認（GET SubscribeURL）；SQS 同帳號訂閱會自動確認，webhook 模式才用得到 */
    @Bean
    @ConditionalOnMissingBean
    public SubscriptionConfirmer sesMailSubscriptionConfirmer() {
        return SubscriptionConfirmer.http();
    }

    /** SES 事件解析；有寄信佇列時以 MessageId 反查呼叫端參照，否則參照即 MessageId */
    @Bean
    @ConditionalOnMissingBean
    public SesEventParser sesEventParser(ObjectProvider<ScheduledMailQueue> queue) {
        return new SesEventParser(messageId -> {
            ScheduledMailQueue q = queue.getIfAvailable();
            return q == null ? messageId : q.findRefByMessageId(messageId).orElse(messageId);
        });
    }

    /** 事件處理器；沒設允許的 topic 時啟動失敗 */
    @Bean
    @ConditionalOnMissingBean
    public SnsWebhookHandler snsWebhookHandler(SnsMessageVerifier verifier, SubscriptionConfirmer confirmer,
                                               SesEventParser parser, ObjectProvider<SesEventListener> listeners,
                                               SesMailProperties properties) {
        return new SnsWebhookHandler(verifier, new LinkedHashSet<>(properties.getSns().getTopicArns()),
            snsRegion(properties), confirmer, parser, () -> listeners.orderedStream().toList());
    }

    /** SNS topic 區域：未設定時沿用 sesmail.region */
    private static String snsRegion(SesMailProperties properties) {
        String region = properties.getSns().getRegion();
        return region == null || region.isBlank() ? properties.getRegion() : region;
    }

    /** SQS 拉取（建議的接收方式） */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "sesmail.sqs", name = "enabled", havingValue = "true")
    static class SqsReceiverConfiguration {

        /** SQS 客戶端；讀取逾時要大於長輪詢的 20 秒 */
        @Bean
        @ConditionalOnMissingBean
        public SqsClient sesMailSqsClient(SesMailProperties properties) {
            String region = properties.getSqs().getRegion();
            return SqsClient.builder()
                .region(Region.of(region == null || region.isBlank() ? properties.getRegion() : region))
                .credentialsProvider(SesMailAutoConfiguration.credentials(properties))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(5))
                    .socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                    .apiCallAttemptTimeout(Duration.ofSeconds(35))
                    .apiCallTimeout(Duration.ofSeconds(40))
                    .build())
                .build();
        }

        /** 拉取器；沒設佇列網址時啟動失敗 */
        @Bean
        @ConditionalOnMissingBean
        public SqsEventPoller sqsEventPoller(SqsClient sqs, SnsWebhookHandler handler,
                                             SesMailProperties properties) {
            String queueUrl = properties.getSqs().getQueueUrl();
            if (queueUrl == null || queueUrl.isBlank()) {
                throw new IllegalStateException("sesmail.sqs.enabled=true 但 sesmail.sqs.queue-url 未設定");
            }
            SqsEventPoller poller = new SqsEventPoller(sqs, queueUrl, handler, Thread::sleep);
            poller.setAutoStartup(properties.getSqs().isAutoStart());
            return poller;
        }
    }

    /** HTTPS webhook 端點：只在 sesmail.sns.enabled 且為 Servlet Web 應用程式時註冊 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "sesmail.sns", name = "enabled", havingValue = "true")
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
