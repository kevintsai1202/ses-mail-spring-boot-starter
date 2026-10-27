package io.github.kevintsai1202.sesmail;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** SesMailAutoConfiguration：預設不啟用、啟用時建立全部 bean、設定缺漏時啟動失敗 */
class SesMailAutoConfigurationTest {

    /** 帶一個假的 DataSource（建立佇列物件時不會連線） */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SesMailAutoConfiguration.class))
        .withBean(DataSource.class, () -> mock(DataSource.class))
        .withPropertyValues("sesmail.dispatch-interval=1h");

    /** 沒設 sesmail.enabled：一個 bean 都不建，應用程式照常啟動 */
    @Test
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SesMailClient.class);
            assertThat(context).doesNotHaveBean(ScheduledMailDispatcher.class);
        });
    }

    /** 啟用後：客戶端、佇列、派送器（已啟動）、額度服務都建立；沒給金鑰時走預設憑證鏈 */
    @Test
    void enabledCreatesBeans() {
        runner.withPropertyValues("sesmail.enabled=true", "sesmail.from=noreply@example.com")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SesV2Client.class);
                assertThat(context).hasSingleBean(SesMailClient.class);
                assertThat(context).hasSingleBean(ScheduledMailQueue.class);
                assertThat(context).hasSingleBean(SesQuotaService.class);
                assertThat(context.getBean(ScheduledMailDispatcher.class).isRunning()).isTrue();
            });
    }

    /** 啟用但沒設寄件人：啟動失敗，錯誤訊息說清楚缺哪個設定 */
    @Test
    void missingFromFailsFast() {
        runner.withPropertyValues("sesmail.enabled=true")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("sesmail.from");
            });
    }

    /** 應用程式自己提供 SesV2Client 時沿用它，不重複建立 */
    @Test
    void backsOffWhenClientProvided() {
        SesV2Client custom = mock(SesV2Client.class);
        runner.withPropertyValues("sesmail.enabled=true", "sesmail.from=noreply@example.com")
            .withBean(SesV2Client.class, () -> custom)
            .run(context -> assertThat(context.getBean(SesV2Client.class)).isSameAs(custom));
    }
}
