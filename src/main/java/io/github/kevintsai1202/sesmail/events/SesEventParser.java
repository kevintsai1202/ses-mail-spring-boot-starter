package io.github.kevintsai1202.sesmail.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 解析 SNS 通知內的 SES 事件 JSON。
 *
 * <p>同時支援兩種來源：configuration set 的事件發佈（欄位 {@code eventType}），
 * 以及身分層級的回饋通知（欄位 {@code notificationType}）。只回傳退信與檢舉，
 * 其他事件（送達、開信…）與解析不了的內容都回空，不拋例外。</p>
 */
public class SesEventParser {

    private static final Logger log = LoggerFactory.getLogger(SesEventParser.class);

    private final ObjectMapper mapper = new ObjectMapper();

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
            List<String> recipients = emails(bounce.path("bouncedRecipients"));
            return recipients.isEmpty() ? Optional.empty() : Optional.of(new SesBounce(
                text(bounce, "bounceType"), text(bounce, "bounceSubType"), recipients, messageId));
        }
        if ("Complaint".equals(type) && root.path("complaint").isObject()) {
            JsonNode complaint = root.path("complaint");
            List<String> recipients = emails(complaint.path("complainedRecipients"));
            return recipients.isEmpty() ? Optional.empty() : Optional.of(new SesComplaint(
                recipients, text(complaint, "complaintFeedbackType"), messageId));
        }
        return Optional.empty();
    }

    /** 取出收件人陣列裡的 emailAddress */
    private static List<String> emails(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                String email = text(node, "emailAddress");
                if (email != null && !email.isBlank()) {
                    result.add(email);
                }
            }
        }
        return List.copyOf(result);
    }

    /** 取文字欄位，不存在或非文字回 null */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
