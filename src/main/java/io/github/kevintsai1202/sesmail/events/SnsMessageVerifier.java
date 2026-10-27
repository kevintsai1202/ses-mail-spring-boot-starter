package io.github.kevintsai1202.sesmail.events;

import software.amazon.awssdk.messagemanager.sns.model.SnsMessage;

/** 驗證並解析 SNS HTTP 推送的原始內容；簽章不合法時拋例外 */
@FunctionalInterface
public interface SnsMessageVerifier {

    /** 驗證簽章後回傳解析好的 SNS 訊息 */
    SnsMessage verify(String body);
}
