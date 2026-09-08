package com.h.backend.automation.infrastructure.delivery;

import com.h.backend.automation.application.AutomationDeliverySinkHandler;
import com.h.backend.automation.domain.AutomationDelivery;
import com.h.backend.automation.domain.AutomationDeliverySink;
import com.h.backend.chat.application.ChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SESSION 投递器：把自动化结果卡片作为助手消息追加到提案/任务绑定的聊天会话。
 * 平台侧会话，幂等性由投递行唯一键（run_id + sink_type）与租约状态机保证；
 * 追加失败抛异常交给投递模块重试/死信，不重跑 Run。
 */
@Component
public class SessionAutomationDeliverySinkHandler implements AutomationDeliverySinkHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionAutomationDeliverySinkHandler.class);

    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper;

    public SessionAutomationDeliverySinkHandler(
            @Lazy ChatSessionService chatSessionService,
            ObjectMapper objectMapper
    ) {
        this.chatSessionService = chatSessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String sinkType() {
        return AutomationDeliverySink.SESSION.name();
    }

    @Override
    public void deliver(AutomationDelivery delivery) throws Exception {
        JsonNode target = objectMapper.readTree(delivery.targetJson());
        String sessionId = target.path("sessionId").asText(null);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("SESSION 投递缺少目标会话");
        }
        String card = renderCard(delivery.payloadJson());
        chatSessionService.appendAssistantMessageIdempotent(
                delivery.userId(), sessionId, card, "automation-delivery:" + delivery.id());
        log.info("Automation result delivered to session runId={} sessionId={}",
                delivery.runId(), sessionId);
    }

    private String renderCard(String payloadJson) throws Exception {
        JsonNode payload = objectMapper.readTree(payloadJson);
        String status = text(payload, "status", "UNKNOWN");
        String taskName = text(payload, "taskName", "自动化任务");
        String trigger = text(payload, "triggerType", "");
        String finishedAt = text(payload, "finishedAt", "");
        String output = text(payload, "outputExcerpt", "");
        String error = text(payload, "errorMessage", "");
        String runSessionId = text(payload, "runSessionId", "");

        StringBuilder card = new StringBuilder();
        card.append("【自动化任务完成】").append(taskName).append('\n');
        card.append("状态：").append(statusLabel(status)).append('\n');
        card.append("触发方式：").append("MANUAL".equals(trigger) ? "手动运行" : "定时触发").append('\n');
        if (!finishedAt.isBlank()) {
            card.append("完成时间：").append(finishedAt).append('\n');
        }
        if (!output.isBlank()) {
            card.append('\n').append(output.strip()).append('\n');
        }
        if (!error.isBlank()) {
            card.append('\n').append("失败原因：").append(error).append('\n');
        }
        if (!runSessionId.isBlank()) {
            card.append('\n').append("可在会话 ").append(runSessionId)
                    .append(" 中查看完整执行过程。");
        }
        return card.toString();
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "SUCCEEDED" -> "成功";
            case "FAILED" -> "失败";
            case "TIMED_OUT" -> "超时";
            case "CANCELLED" -> "已取消";
            case "REJECTED_POLICY" -> "策略拒绝";
            default -> status;
        };
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText("");
        return text.isBlank() ? fallback : text;
    }
}
