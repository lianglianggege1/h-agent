package com.h.backend.chat.application.chart;

import java.util.List;
import java.util.Map;

/** The durable chart shown in a standard chat turn. */
public record ChatChart(String id, String title, String source, Map<String, Object> option,
                        Map<String, Object> maps, List<String> extensions, Map<String, Object> table,
                        TableView view) {
    public record TableView(String intent, String x, List<String> y, String unit, String chartType) {
        public TableView {
            y = List.copyOf(y);
        }
    }

    public ChatChart(String id, String title, String source, Map<String, Object> option,
                     Map<String, Object> maps, List<String> extensions, Map<String, Object> table) {
        this(id, title, source, option, maps, extensions, table, null);
    }

    public static final String ACTIVITY_TYPE = "h-agent.chart";
    public static final String TOOL_NAME = "show_chart";
}
