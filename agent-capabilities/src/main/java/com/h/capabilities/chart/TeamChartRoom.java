package com.h.capabilities.chart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.h.matrixteam.MatrixClient;

public final class TeamChartRoom {
    public static final String EVENT_TYPE = "com.h.agent.chart";
    private static final int INLINE_BYTES = 40 * 1024;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MatrixClient client;
    private final String roomId;

    public TeamChartRoom(MatrixClient client, String roomId) {
        this.client = client;
        this.roomId = roomId;
    }

    public void publish(ChatChart chart) {
        try {
            byte[] content = JSON.writeValueAsBytes(chart);
            if (content.length > MAX_BYTES) throw new IllegalArgumentException("图表过大，请先筛选或聚合数据。");
            ObjectNode event = JSON.createObjectNode().put("chartId", chart.id()).put("title", chart.title());
            if (content.length <= INLINE_BYTES) {
                event.set("chart", JSON.valueToTree(chart));
            } else {
                event.put("chartUrl", client.upload(chart.id() + ".json", "application/json", content));
            }
            if (client.sendEvent(roomId, EVENT_TYPE, event).isBlank()) {
                throw new IllegalStateException("房间未返回图表事件 ID");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalStateException ex) {
            throw new IllegalArgumentException("图表发布到当前房间失败，请重试。", ex);
        }
    }

    public String read(String id) {
        String before = null;
        for (int page = 0; page < 5; page++) {
            JsonNode history;
            try {
                history = client.messages(roomId, 50, before, EVENT_TYPE);
            } catch (IllegalStateException ex) {
                throw new IllegalArgumentException("当前房间的图表历史读取失败，请重试。", ex);
            }
            for (JsonNode event : history.path("chunk")) {
                if (!EVENT_TYPE.equals(event.path("type").asText())) continue;
                JsonNode content = event.path("content");
                String chartId = content.path("chartId").asText(null);
                if (chartId == null || (id != null && !id.equals(chartId))) continue;
                JsonNode chart = content.path("chart");
                String url = content.path("chartUrl").asText(null);
                if (!chart.isObject() && url != null && url.startsWith("mxc://")) {
                    try {
                        chart = JSON.readTree(client.download(url, MAX_BYTES));
                    } catch (java.io.IOException | IllegalArgumentException | IllegalStateException ex) {
                        throw new IllegalArgumentException("房间图表内容读取失败。", ex);
                    }
                }
                if (chart != null && chart.isObject() && chartId.equals(chart.path("id").asText())
                        && chart.path("option").isObject()) return chart.toString();
            }
            String next = history.path("end").asText(null);
            if (next == null || next.equals(before)) break;
            before = next;
        }
        return "当前房间中没有匹配的近期图表。";
    }
}
