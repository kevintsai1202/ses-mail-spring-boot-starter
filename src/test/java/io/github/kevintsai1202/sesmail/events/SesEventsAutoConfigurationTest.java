package io.github.kevintsai1202.sesmail.events;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SesEventsAutoConfiguration：預設不啟用、啟用時註冊端點、沒設 topic 啟動失敗 */
class SesEventsAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SesEventsAutoConfiguration.class));

    /** 沒設 sesmail.sns.enabled：不建任何 bean */
    @Test
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SnsWebhookHandler.class);
            assertThat(context).doesNotHaveBean(SnsWebhookController.class);
        });
    }

    /** 啟用但沒設允許的 topic：啟動失敗（否則任何 topic 都會被接受） */
    @Test
    void missingTopicFailsFast() {
        runner.withPropertyValues("sesmail.sns.enabled=true")
            .run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("sesmail.sns.topic-arns"));
    }

    /** topic-arns 設成空字串（環境變數沒填）：同樣啟動失敗，不能默默拒絕所有訊息 */
    @Test
    void blankTopicFailsFast() {
        runner.withPropertyValues("sesmail.sns.enabled=true", "sesmail.sns.topic-arns=")
            .run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("sesmail.sns.topic-arns"));
    }

    /** 啟用：建立 handler 與 controller；驗證失敗的請求回 403，通過的回 200 */
    @Test
    void endpointRespondsByHandlerResult() {
        SnsMessageVerifier verifier = mock(SnsMessageVerifier.class);
        when(verifier.verify(anyString())).thenThrow(new IllegalArgumentException("bad"));
        runner.withPropertyValues("sesmail.sns.enabled=true",
                "sesmail.sns.topic-arns=arn:aws:sns:ap-northeast-1:123456789012:ses-events")
            .withBean(SnsMessageVerifier.class, () -> verifier)
            .run(context -> {
                assertThat(context).hasSingleBean(SnsWebhookHandler.class);
                MockMvc mvc = MockMvcBuilders.standaloneSetup(context.getBean(SnsWebhookController.class)).build();
                mvc.perform(post("/sesmail/sns").contentType(MediaType.TEXT_PLAIN).content("{}"))
                    .andExpect(status().isForbidden());
            });
    }

    /** 只啟用 SQS：建立拉取器、不註冊 webhook 端點；沒設佇列網址時啟動失敗 */
    @Test
    void sqsModeCreatesPollerWithoutEndpoint() {
        runner.withPropertyValues("sesmail.sqs.enabled=true",
                "sesmail.sqs.queue-url=https://sqs.ap-northeast-1.amazonaws.com/123456789012/ses-events",
                "sesmail.sqs.auto-start=false",
                "sesmail.sns.topic-arns=arn:aws:sns:ap-northeast-1:123456789012:ses-events")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SqsEventPoller.class);
                assertThat(context).hasSingleBean(SnsWebhookHandler.class);
                assertThat(context).doesNotHaveBean(SnsWebhookController.class);
            });
        runner.withPropertyValues("sesmail.sqs.enabled=true",
                "sesmail.sns.topic-arns=arn:aws:sns:ap-northeast-1:123456789012:ses-events")
            .run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("sesmail.sqs.queue-url"));
    }
}
