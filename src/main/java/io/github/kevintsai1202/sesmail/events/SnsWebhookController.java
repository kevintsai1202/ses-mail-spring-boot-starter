package io.github.kevintsai1202.sesmail.events;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * SNS 推送退信／檢舉事件的 webhook 端點（預設 {@code POST /sesmail/sns}）。
 *
 * <p>SNS 送來的 Content-Type 是 text/plain，所以直接收原始字串。驗證失敗回 403，其餘一律 200，
 * 避免 SNS 對處理不了的訊息無止盡重送。這個端點必須能從公網以 HTTPS 直接打到，
 * 不能被登入或 Host 白名單擋住。</p>
 */
@RestController
public class SnsWebhookController {

    private final SnsWebhookHandler handler;

    /** 注入處理器 */
    public SnsWebhookController(SnsWebhookHandler handler) {
        this.handler = handler;
    }

    /** 接收 SNS 推送 */
    @PostMapping("${sesmail.sns.path:/sesmail/sns}")
    public ResponseEntity<Void> receive(@RequestBody String body) {
        SnsWebhookHandler.Result result = handler.handle(body);
        return result == SnsWebhookHandler.Result.REJECTED
            ? ResponseEntity.status(403).build()
            : ResponseEntity.ok().build();
    }
}
