package com.h.backend.chat.application.chart;

import com.h.backend.chat.infrastructure.persistence.mapper.AguiRunSnapshotMapper;
import com.h.capabilities.chart.ChatChart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ChatChartHistory {
    private final AguiRunSnapshotMapper snapshots;
    private final ObjectMapper json = new ObjectMapper();

    public ChatChartHistory(AguiRunSnapshotMapper snapshots) {
        this.snapshots = snapshots;
    }

    public String read(long userId, String threadId, String id) {
        for (var run : snapshots.list(userId, threadId).reversed()) {
            try {
                JsonNode activities = json.readTree(run.snapshotJson()).path("activities");
                for (int index = activities.size() - 1; index >= 0; index--) {
                    JsonNode activity = activities.get(index);
                    if (!ChatChart.ACTIVITY_TYPE.equals(activity.path("activityType").asText())) continue;
                    JsonNode chart = activity.path("content").path("chart");
                    if (chart.isObject() && (id == null || id.equals(chart.path("id").asText()))) {
                        return chart.toString();
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException("图表历史读取失败。", ex);
            }
        }
        return "当前会话中没有匹配的图表。";
    }
}
