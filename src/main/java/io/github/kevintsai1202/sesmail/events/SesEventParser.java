package io.github.kevintsai1202.sesmail.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 解析 SNS 通知內的 SES 事件 JSON。
 *
 * <p>同時支援 configuration set 的事件發佈（欄位 {@code eventType}）與身分層級的回饋通知
 * （欄位 {@code notificationType}）。回傳退信、檢舉、送達三種；其他事件與解析不了的 JSON 回空，不拋例外。</p>
 *
 * <p>SES 事件只帶 MessageId；建構時給的 {@code refResolver} 負責把它對回呼叫端參照
 * （佇列寄出的信是 ses-batch-*／ses-sched-*）。查詢失敗（例如資料庫斷線）會往外拋，
 * 讓 SQS 保留訊息稍後重試。</p>
 */
public class SesEventParser {

    private static final Logger log = LoggerFactory.getLogger(SesEventParser.class);

    private final ObjectMapper mapper = new ObjectMapper();
    /** SES MessageId → 呼叫端參照；查不到時應回傳 MessageId 本身 */
    private final Function<String, String> refResolver;

    /** @param refResolver SES MessageId → 呼叫端參照 */
    public SesEventParser(Function<String, String> refResolver) {
        this.refResolver = refResolver;
    }

    /** 解析一則 SES 事件 JSON */
    public Optional<SesEvent> parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            log.warn("SES 事件不是合法 JSON，略過：{}", e.getMessage());
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String type = text(root, "eventType");
        if (type == null) {
            type = text(root, "notificationType");
        }
        String messageId = text(root.path("mail"), "messageId");
        if ("Bounce".equals(type) && root.path("bounce").isObject()) {
            JsonNode bounce = root.path("bounce");
            List<String> recipients = objectEmails(bounce.path("bouncedRecipients"));
            return recipients.isEmpty() ? Optional.empty() : Optional.of(new SesBounce(ref(messageId),
                text(bounce, "bounceType"), text(bounce, "bounceSubType"), recipients, messageId));
        }
        if ("Complaint".equals(type) && root.path("complaint").isObject()) {
            JsonNode complaint = root.path("complaint");
            List<String> recipients = objectEmails(complaint.path("complainedRecipients"));
            return recipients.isEmpty() ? Optional.empty() : Optional.of(new SesComplaint(ref(messageId),
                recipients, text(complaint, "complaintFeedbackType"), messageId));
        }
        if ("Delivery".equals(type) && root.path("delivery").isObject()) {
            // 送達事件的 recipients 是字串陣列，與退信／檢舉的 {emailAddress} 物件陣列不同
            List<String> recipients = stringArray(root.path("delivery").path("recipients"));
            return recipients.isEmpty() ? Optional.empty()
                : Optional.of(new SesDelivery(ref(messageId), recipients, messageId));
        }
        return Optional.empty();
    }

    /** MessageId 對回參照；沒有 MessageId 時無從查起 */
    private String ref(String messageId) {
        return messageId == null ? null : refResolver.apply(messageId);
    }

    /** 取出物件陣列裡的 emailAddress（退信、檢舉） */
    private static List<String> objectEmails(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                addIfPresent(result, text(node, "emailAddress"));
            }
        }
        return List.copyOf(result);
    }

    /** 取出字串陣列（送達） */
    private static List<String> stringArray(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                addIfPresent(result, node.isTextual() ? node.asText() : null);
            }
        }
        return List.copyOf(result);
    }

    /** 非空白才加入 */
    private static void addIfPresent(List<String> list, String email) {
        if (email != null && !email.isBlank()) {
            list.add(email);
        }
    }

    /** 取文字欄位，不存在或非文字回 null */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
