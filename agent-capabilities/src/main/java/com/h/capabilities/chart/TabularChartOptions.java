package com.h.capabilities.chart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Produces the default ECharts view of an already prepared table. */
final class TabularChartOptions {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private TabularChartOptions() {}

    static ObjectNode build(ChatChart.TableView view, JsonNode table) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (JsonNode column : table.path("columns")) {
            String name = column.textValue();
            if (name.isBlank() || columns.putIfAbsent(name, columns.size()) != null) {
                throw new IllegalArgumentException("表格列名需要非空且唯一。");
            }
        }

        JsonNode rows = table.path("rows");
        if (rows.isEmpty()) throw new IllegalArgumentException("请提供至少一行有数据的表格。");
        int xIndex = columnIndex(columns, view.x());
        if (view.y().contains(view.x()) || new HashSet<>(view.y()).size() != view.y().size()) {
            throw new IllegalArgumentException("请为 x 和 y 选择不同的列，y 中每列只填写一次。");
        }

        boolean scatter = "scatter".equals(view.chartType());
        boolean pie = "pie".equals(view.chartType());
        if (pie && view.y().size() != 1) throw new IllegalArgumentException("构成图需要且仅需要一个 y 列。");

        ObjectNode option = JSON.objectNode();
        ObjectNode tooltip = option.putObject("tooltip").put("trigger", scatter || pie ? "item" : "axis");
        if (view.unit() != null && !scatter) {
            tooltip.putObject("valueFormatter").put("$callback", "number").put("suffix", " " + view.unit());
        }

        ArrayNode categories = JSON.arrayNode();
        for (JsonNode row : rows) {
            JsonNode value = row.get(xIndex);
            if (scatter) validateNumber(value, view.x());
            else {
                if (value.isNull() || value.asText().isBlank()) {
                    throw new IllegalArgumentException("分类或时间列「" + view.x() + "」包含空值，请先处理。");
                }
                categories.add(value.asText());
            }
        }
        if (!pie) {
            ObjectNode xAxis = option.putObject("xAxis").put("type", scatter ? "value" : "category")
                    .put("name", view.x());
            if (!scatter) xAxis.set("data", categories);
            ObjectNode yAxis = option.putObject("yAxis").put("type", "value");
            if (view.unit() != null) yAxis.put("name", view.unit());
            else if (view.y().size() == 1) yAxis.put("name", view.y().getFirst());
            option.putObject("grid").put("containLabel", true);
        }
        if (view.y().size() > 1 || pie) option.putObject("legend");

        ArrayNode series = option.putArray("series");
        for (String name : view.y()) {
            var entry = series.addObject().put("name", name).put("type", view.chartType());
            if (pie) entry.put("radius", "62%");
            addSeriesData(entry.putArray("data"), rows, categories, xIndex, columnIndex(columns, name), name, scatter, pie);
        }
        return option;
    }

    private static void addSeriesData(ArrayNode data, JsonNode rows, ArrayNode categories,
                                      int xIndex, int yIndex, String name, boolean scatter, boolean pie) {
        boolean hasPlottableValue = false;
        var sliceNames = new HashSet<String>();
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            JsonNode value = row.get(yIndex);
            validateNumber(value, name);
            if (pie) {
                String sliceName = categories.get(i).textValue();
                if (!sliceNames.add(sliceName)) {
                    throw new IllegalArgumentException("构成图类别重复，请先按类别汇总：「" + sliceName + "」。");
                }
                if (!value.isNull() && value.doubleValue() < 0) {
                    throw new IllegalArgumentException("构成图的数值必须为非负数。");
                }
                data.addObject().put("name", sliceName).set("value", value);
                hasPlottableValue |= !value.isNull() && value.doubleValue() > 0;
            } else if (scatter) {
                JsonNode x = row.get(xIndex);
                data.addArray().add(x).add(value);
                hasPlottableValue |= !x.isNull() && !value.isNull();
            } else {
                data.add(value);
                hasPlottableValue |= !value.isNull();
            }
        }
        if (!hasPlottableValue) {
            String detail = pie ? "需要至少一个正数" : "缺少可绘制的数值";
            throw new IllegalArgumentException("列「" + name + "」" + detail + "。");
        }
    }

    private static int columnIndex(Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null) {
            throw new IllegalArgumentException("数据表找不到列「" + name + "」。可用列：" + String.join("、", columns.keySet()) + "。");
        }
        return index;
    }

    private static void validateNumber(JsonNode value, String column) {
        if (!value.isNull() && (!value.isNumber() || !Double.isFinite(value.doubleValue()))) {
            throw new IllegalArgumentException("列「" + column + "」需要 JSON 数字；缺失值请填写 null。");
        }
    }
}
