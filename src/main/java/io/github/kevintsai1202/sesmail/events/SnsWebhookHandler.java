package io.github.kevintsai1202.sesmail.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.messagemanager.sns.model.SnsMessage;
import software.amazon.awssdk.messagemanager.sns.model.SnsNotification;
import software.amazon.awssdk.messagemanager.sns.model.SnsSubscriptionConfirmation;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 處理 SNS 推送到 webhook 的訊息：驗簽 → Topic 白名單 → 依類型處理。
 *
 * <ul>
 *   <li>訂閱確認：SubscribeURL 必須是該區域 SNS 主機（https），才去呼叫它完成訂閱。</li>
 *   <li>通知：解析 SES 事件，分派給所有 {@link SesEventListener}。解析或任一 listener 失敗回 {@code FAILED}：
 *       SQS 模式據此保留訊息重試，webhook 模式仍回 200。</li>
 *   <li>取消訂閱確認與其他事件：忽略。</li>
 * </ul>
 */
public class SnsWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(SnsWebhookHandler.class);

    /** 處理結果；controller 依此決定 HTTP 狀態碼 */
    public enum Result {
        /** 訂閱已確認 */
        CONFIRMED,
        /** 事件已分派 */
        NOTIFIED,
        /** 解析或 listener 處理失敗：SQS 模式不刪除訊息、稍後重試 */
        FAILED,
        /** 合法但不需處理 */
        IGNORED,
        /** 驗簽失敗、topic 不在白名單、SubscribeURL 可疑：回 403 */
        REJECTED
    }

    private final SnsMessageVerifier verifier;
    /** 允許的 topic ARN */
    private final Set<String> allowedTopics;
    /** 訂閱確認網址必須是這個主機 */
    private final String expectedSnsHost;
    private final SubscriptionConfirmer confirmer;
    private final SesEventParser parser;
    private final Supplier<List<SesEventListener>> listeners;

    /**
     * @param allowedTopics 允許的 topic ARN；不得為空
     * @param region        SNS topic 所在區域，用來核對 SubscribeURL 主機
     */
    public SnsWebhookHandler(SnsMessageVerifier verifier, Set<String> allowedTopics, String region,
                             SubscriptionConfirmer confirmer, SesEventParser parser,
                             Supplier<List<SesEventListener>> listeners) {
        if (allowedTopics == null || allowedTopics.isEmpty()) {
            throw new IllegalStateException("sesmail.sns.enabled=true 但 sesmail.sns.topic-arns 未設定");
        }
        this.verifier = verifier;
        this.allowedTopics = Set.copyOf(allowedTopics);
        this.expectedSnsHost = "sns." + region + ".amazonaws.com";
        this.confirmer = confirmer;
        this.parser = parser;
        this.listeners = listeners;
    }

    /** 處理一則 SNS 推送的原始內容 */
    public Result handle(String body) {
        SnsMessage message;
        try {
            message = verifier.verify(body);
        } catch (RuntimeException e) {
            log.warn("SNS 訊息簽章驗證失敗，拒絕：{}", e.getMessage());
            return Result.REJECTED;
        }
        if (!allowedTopics.contains(message.topicArn())) {
            log.warn("SNS 訊息來自未允許的 topic，拒絕：{}", message.topicArn());
            return Result.REJECTED;
        }
        if (message instanceof SnsSubscriptionConfirmation confirmation) {
            return confirm(confirmation);
        }
        if (message instanceof SnsNotification notification) {
            return dispatch(notification);
        }
        log.info("SNS 訊息類型 {} 不需處理（topic {}）", message.type(), message.topicArn());
        return Result.IGNORED;
    }

    /** 訂閱確認：只打該區域 SNS 主機的 https 網址 */
    private Result confirm(SnsSubscriptionConfirmation confirmation) {
        URI url = confirmation.subscribeUrl();
        if (url == null || !"https".equalsIgnoreCase(url.getScheme())
                || !expectedSnsHost.equalsIgnoreCase(url.getHost())) {
            log.warn("SNS 訂閱確認網址不是 {}，拒絕：{}", expectedSnsHost, url);
            return Result.REJECTED;
        }
        confirmer.confirm(url);
        log.info("已確認 SNS 訂閱：{}", confirmation.topicArn());
        return Result.CONFIRMED;
    }

    /** 通知：解析 SES 事件並分派；任何一步失敗回 FAILED（其他 listener 仍會被呼叫） */
    private Result dispatch(SnsNotification notification) {
        SesEvent event;
        try {
            event = parser.parse(notification.message()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("SES 事件解析失敗，稍後重試：{}", e.getMessage(), e);
            return Result.FAILED;
        }
        if (event == null) {
            return Result.IGNORED;
        }
        boolean failed = false;
        for (SesEventListener listener : listeners.get()) {
            try {
                switch (event) {
                    case SesBounce bounce -> listener.onBounce(bounce);
                    case SesComplaint complaint -> listener.onComplaint(complaint);
                    case SesDelivery delivery -> listener.onDelivery(delivery);
                }
            } catch (RuntimeException e) {
                failed = true;
                log.warn("SesEventListener {} 處理失敗：{}", listener.getClass().getName(), e.getMessage(), e);
            }
        }
        return failed ? Result.FAILED : Result.NOTIFIED;
    }
}
