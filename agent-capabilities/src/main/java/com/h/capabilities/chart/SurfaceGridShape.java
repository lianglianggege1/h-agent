package com.h.capabilities.chart;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;

/** Fills in the shape of explicit, regular ECharts GL grids without reordering the caller's points. */
final class SurfaceGridShape {
    private SurfaceGridShape() {}

    static void infer(JsonNode option) {
        JsonNode series = option.path("series");
        if (series.isArray()) series.forEach(SurfaceGridShape::inferSeries);
        else inferSeries(series);
        if (option.path("baseOption").isObject()) infer(option.path("baseOption"));
        option.path("options").forEach(SurfaceGridShape::infer);
        option.path("media").forEach(item -> infer(item.path("option")));
    }

    private static void inferSeries(JsonNode series) {
        if (!(series instanceof ObjectNode surface) || !"surface".equals(surface.path("type").asText())
                || surface.hasNonNull("dataShape") || surface.path("parametric").asBoolean()) return;
        JsonNode data = surface.path("data");
        if (!data.isArray() || data.size() < 4) return;
        for (JsonNode point : data) {
            if (!point.isArray() || point.size() != 3 || !coordinate(point.get(0)) || !coordinate(point.get(1))) return;
        }

        int columns = columnsFor(data, 0);
        if (columns == 0) columns = columnsFor(data, 1);
        if (columns != 0) surface.putArray("dataShape").add(data.size() / columns).add(columns);
    }

    private static boolean coordinate(JsonNode value) {
        return value.isTextual() || (value.isNumber() && Double.isFinite(value.doubleValue()));
    }

    private static int columnsFor(JsonNode data, int fixedAxis) {
        JsonNode first = data.get(0).get(fixedAxis);
        int columns = 1;
        while (columns < data.size() && first.equals(data.get(columns).get(fixedAxis))) columns++;
        if (columns < 2 || columns == data.size() || data.size() % columns != 0) return 0;

        int varyingAxis = 1 - fixedAxis;
        var columnValues = new HashSet<JsonNode>();
        for (int i = 0; i < columns; i++) {
            if (!columnValues.add(data.get(i).get(varyingAxis))) return 0;
        }
        var rowValues = new HashSet<JsonNode>();
        for (int row = 0; row < data.size(); row += columns) {
            JsonNode fixed = data.get(row).get(fixedAxis);
            if (!rowValues.add(fixed)) return 0;
            for (int column = 0; column < columns; column++) {
                JsonNode point = data.get(row + column);
                if (!fixed.equals(point.get(fixedAxis))
                        || !data.get(column).get(varyingAxis).equals(point.get(varyingAxis))) return 0;
            }
        }
        return columns;
    }
}
