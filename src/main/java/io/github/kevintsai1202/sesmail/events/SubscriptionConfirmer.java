package io.github.kevintsai1202.sesmail.events;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 呼叫 SNS 的 SubscribeURL 完成訂閱確認 */
@FunctionalInterface
public interface SubscriptionConfirmer {

    /** 對 subscribeUrl 發 GET；非 2xx 拋例外 */
    void confirm(URI subscribeUrl);

    /** 以 JDK HttpClient 實作的預設版本 */
    static SubscriptionConfirmer http() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return url -> {
            try {
                HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("SNS 訂閱確認失敗，HTTP " + response.statusCode());
                }
            } catch (IOException e) {
                throw new IllegalStateException("SNS 訂閱確認失敗：" + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("SNS 訂閱確認被中斷", e);
            }
        };
    }
}
