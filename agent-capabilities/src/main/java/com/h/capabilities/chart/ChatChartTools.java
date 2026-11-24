package com.h.capabilities.chart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.h.capabilities.chat.infrastructure.filesystem.AssistantFileStorage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
public class ChatChartTools {
    public static final String ENABLE_PARAMETER = ChatChartTools.class.getName();
    private static final int MAX_PAYLOAD = 512 * 1024;
    private static final int MAX_MAP_FILE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("gl", "bar-range");
    private static final Set<String> GL_SERIES = Set.of("bar3D", "scatter3D", "surface", "line3D", "lines3D",
            "map3D", "scatterGL", "linesGL", "flowGL", "graphGL", "polygons3D");
    private static final Set<String> PROTOTYPE_KEYS = Set.of("__proto__", "prototype", "constructor");
    private static final Set<String> FORMATTERS = Set.of("number", "percent");

    private static final ToolSpecification SHOW = ToolSpecification.builder().name(ChatChart.TOOL_NAME)
            .description("在当前聊天中发布交互式 ECharts 图表。可使用来自任意已授权来源或对话整理的数据。"
                    + "常见图提交 intent、x、y、table，工具从同一份数据生成图和表。高级图提交原生 optionJson，"
                    + "可先用 get_chart_option_guide 查配置。两种输入选一种。")
            .parameters(JsonObjectSchema.builder()
                    .addStringProperty("title", "图表名称，用于卡片标题和下载")
                    .addStringProperty("source", "可选，数据来源及筛选、统计口径或估算说明")
                    .addEnumProperty("intent", List.of("trend", "comparison", "relationship", "composition"),
                            "常见图的分析目的：趋势默认折线，对比默认柱状，数值关系用散点，构成用饼图。")
                    .addStringProperty("x", "table.columns 中的横轴、类别或名称列。散点图要求数值列。")
                    .addProperty("y", stringArray("table.columns 中要绘制的数值列名，以数组提交。饼图只填一列。"))
                    .addStringProperty("unit", "常见图的可选数值单位。多系列使用同一单位；按数据实际单位填写。")
                    .addEnumProperty("chartType", List.of("line", "bar", "scatter", "pie"),
                            "可选，显式指定图型。趋势和对比可在折线和柱状之间切换。")
                    .addStringProperty("optionJson", """
                            将完整原生 ECharts option 序列化成一个 JSON 字符串，直接以 xAxis、yAxis、series 等字段作为根属性。
                            折线图示例：{"xAxis":{"type":"category","data":["一月","二月"]},"yAxis":{"type":"value"},"series":[{"type":"line","data":[12,17]}]}。
                            复杂图型先调用 get_chart_option_guide。配置要包含实际图形或 series，并填写真实数据；tooltip 只是辅助设置。
                            tooltip.valueFormatter 需要注册回调，例如 {"$callback":"number","suffix":" 万元"}。
                            """)
                    .addProperty("maps", openObject("可选。地图名映射到内联 GeoJSON FeatureCollection，或当前会话工作区中已存在的 .json/.geojson 文件虚拟路径，例如 /china.geojson。option 的 map 引用同一地图名。"))
                    .addProperty("extensions", JsonArraySchema.builder()
                            .items(JsonEnumSchema.builder().enumValues("gl", "bar-range").build())
                            .description("可选。gl 用于 3D/WebGL，bar-range 提供 series.type=custom、renderItem=barRange。")
                            .build())
                    .addProperty("table", tableSchema())
                    .required("title").additionalProperties(false).build())
            .build();

    private static final ToolSpecification READ = ToolSpecification.builder().name("read_chat_chart")
            .description("读取当前聊天已保存的图表、数据和来源。新建的常见图返回 view 字段（意图和字段映射），可沿用后发布新版。省略 id 时读取最近一张图。")
            .parameters(JsonObjectSchema.builder().addStringProperty("id", "show_chart 返回的图表 ID，可省略")
                    .additionalProperties(false).build())
            .build();

    private static final ToolSpecification GUIDE = ToolSpecification.builder().name(ChatChartOptionGuide.TOOL_NAME)
            .description("查询当前聊天所用 ECharts 版本、原生 option 示例和已注册图形扩展的参数。")
            .parameters(JsonObjectSchema.builder().addEnumProperty("topic", ChatChartOptionGuide.TOPICS,
                    "省略时返回版本与索引。cartesian 为坐标与组合图，connections 为桑基与关系图，"
                            + "bar-range 为自定义区间图，gl 为三维，map 为 GeoJSON。")
                    .additionalProperties(false).build())
            .build();

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final AssistantFileStorage files;

    public record Scope(Function<String, String> readChart, Consumer<ChatChart> publish,
                        Function<String, byte[]> readMapFile) {}

    public ChatChartTools(AssistantFileStorage files) {
        this.files = files;
    }

    public Scope session(String memoryId, Function<String, String> readChart, Consumer<ChatChart> publish) {
        return new Scope(readChart, publish, path -> readSessionFile(memoryId, path));
    }

    public static List<ToolSpecification> specifications() {
        return List.of(SHOW, READ, GUIDE);
    }

    private static JsonObjectSchema openObject(String description) {
        return JsonObjectSchema.builder().description(description).additionalProperties(true).build();
    }

    private static JsonArraySchema stringArray(String description) {
        return JsonArraySchema.builder().items(new JsonStringSchema()).description(description).build();
    }

    private static JsonObjectSchema tableSchema() {
        var cell = JsonAnyOfSchema.builder().anyOf(new JsonStringSchema(), new JsonNumberSchema(),
                new JsonBooleanSchema(), new JsonNullSchema()).build();
        var row = JsonArraySchema.builder().items(cell).build();
        return JsonObjectSchema.builder()
                .description("常见图必填。每行对应 columns 中的列，按绘制顺序排列；数值用 JSON 数字，缺失值用 null。高级图可附数据表。")
                .addProperty("columns", stringArray("列名数组，常见图要求列名唯一。"))
                .addProperty("rows", JsonArraySchema.builder().items(row).build())
                .required("columns", "rows").additionalProperties(false).build();
    }

    public ToolProvider provider(Function<Object, Scope> scope) {
        return request -> {
            if (request == null || request.invocationParameters() == null
                    || !Boolean.TRUE.equals(request.invocationParameters().get(ENABLE_PARAMETER))) {
                return ToolProviderResult.builder().build();
            }
            var result = ToolProviderResult.builder();
            for (var specification : specifications()) {
                result.add(specification, new ToolExecutor() {
                    @Override
                    public String execute(ToolExecutionRequest call, Object memoryId) {
                        return invoke(specification.name(), call.arguments(), scope.apply(memoryId)).resultText();
                    }

                    @Override
                    public ToolExecutionResult executeWithContext(ToolExecutionRequest call, InvocationContext context) {
                        return invoke(specification.name(), call.arguments(), scope.apply(context.chatMemoryId()));
                    }
                });
            }
            return result.build();
        };
    }

    public ToolExecutionResult invoke(String name, String arguments, Scope scope) {
        try {
            if (ChatChart.TOOL_NAME.equals(name)) {
                var result = show(arguments, scope.readMapFile());
                if (!result.isError()) scope.publish().accept((ChatChart) result.result());
                return result;
            }
            JsonNode input = parse(arguments);
            String result = switch (name) {
                case "read_chat_chart" -> scope.readChart().apply(text(input, "id", 64));
                case "get_chart_option_guide" -> ChatChartOptionGuide.forTopic(text(input, "topic", 32));
                default -> throw new IllegalArgumentException("未知图表工具。");
            };
            return ToolExecutionResult.builder().resultText(result).build();
        } catch (IllegalArgumentException invalid) {
            return failure(invalid.getMessage());
        }
    }

    private ToolExecutionResult show(String arguments, Function<String, byte[]> readFile) {
        try {
            JsonNode input = parse(arguments);
            String title = text(input, "title", 160);
            if (title == null) throw new IllegalArgumentException("请提供图表标题。");
            String optionJson = text(input, "optionJson", MAX_PAYLOAD);
            JsonNode table = optionalObject(input, "table");
            if (table != null) validateTable(table);
            var view = tableView(input);
            JsonNode option;
            if (view != null) {
                if (optionJson != null || input.hasNonNull("option")) {
                    throw new IllegalArgumentException("请选择 intent 或 optionJson 中的一种输入方式。");
                }
                if (table == null) throw new IllegalArgumentException("使用 intent 时请提供包含 columns 和 rows 的 table。");
                option = TabularChartOptions.build(view, table);
            } else {
                option = optionJson == null ? input.path("option") : parse(optionJson);
            }
            if (!option.isObject() || option.isEmpty()) {
                throw new IllegalArgumentException("请提供 intent、x、y 和 table，或在 optionJson 中提供完整原生配置。");
            }
            validateTree(option, 0);
            if (!hasChartContent(option)) {
                throw new IllegalArgumentException("optionJson 缺少可绘制内容。请提交完整配置并包含 series 与图型 type，或 graphic、geo 等图形组件。");
            }
            SurfaceGridShape.infer(option);
            JsonNode maps = readMaps(optionalObject(input, "maps"), readFile);
            var chart = new ChatChart(UUID.randomUUID().toString(), title, text(input, "source", 1200),
                    chartObject(option), chartObject(maps), extensions(input.path("extensions"), option), chartObject(table), view);
            return ToolExecutionResult.builder()
                    .result(chart)
                    .resultText("已在聊天中生成图表「" + title + "」，图表 ID 为 " + chart.id() + "。")
                    .build();
        } catch (IllegalArgumentException invalid) {
            return failure(invalid.getMessage());
        }
    }

    private JsonNode readMaps(JsonNode maps, Function<String, byte[]> readFile) {
        if (maps == null) return null;
        if (maps.size() > 8) throw new IllegalArgumentException("每张图最多可注册 8 张地图。");

        int remaining = MAX_MAP_FILE_BYTES;
        ObjectNode resolved = JSON.createObjectNode();
        for (var entry : maps.properties()) {
            String name = entry.getKey();
            if (name.isBlank() || name.length() > 100 || PROTOTYPE_KEYS.contains(name)) {
                throw new IllegalArgumentException("地图名称无效，须为不超过 100 字的非空名称。");
            }
            JsonNode geoJson = entry.getValue();
            if (geoJson.isTextual()) {
                String path = geoJson.textValue();
                String lower = path.toLowerCase(Locale.ROOT);
                if (path.contains("://") || !(lower.endsWith(".json") || lower.endsWith(".geojson"))) {
                    throw new IllegalArgumentException("地图「" + name + "」请提供内联 GeoJSON，或当前会话工作区中的 .json/.geojson 路径，例如 /china.geojson。");
                }
                byte[] bytes = readFile == null ? null : readFile.apply(path);
                if (bytes == null) {
                    throw new IllegalArgumentException("无法读取地图「" + name + "」的文件。请用 list_files 确认当前会话工作区中的路径，或提供内联 GeoJSON。");
                }
                if (bytes.length > remaining) throw new IllegalArgumentException("地图文件合计超过 2 MiB，请先简化边界。");
                remaining -= bytes.length;
                try {
                    geoJson = parseSnapshot(new String(bytes, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("地图「" + name + "」的文件需要包含有效的 GeoJSON。", invalid);
                }
            }
            if (!(geoJson instanceof ObjectNode collection) || !collection.path("features").isArray()
                    || collection.path("features").isEmpty()
                    || (collection.has("type") && !"FeatureCollection".equals(collection.path("type").asText()))) {
                throw new IllegalArgumentException("地图「" + name + "」需要 GeoJSON FeatureCollection 和非空 features 数组。");
            }
            for (JsonNode feature : collection.path("features")) {
                if (!"Feature".equals(feature.path("type").asText()) || !feature.path("geometry").isObject()) {
                    throw new IllegalArgumentException("地图「" + name + "」的 features 中包含无效 Feature 或缺少 geometry。");
                }
            }
            if (!collection.has("type")) collection.put("type", "FeatureCollection");
            validateTree(collection, 0);
            resolved.set(name, collection);
        }
        return resolved;
    }

    private byte[] readSessionFile(String memoryId, String path) {
        if (memoryId == null || files == null) return null;
        var file = files.openSessionFileStream(memoryId, path);
        if (!file.success()) return null;
        try (var stream = file.stream()) {
            if (file.size() > MAX_MAP_FILE_BYTES) throw new IllegalArgumentException("地图文件超过 2 MiB，请先简化边界。");
            return stream.readNBytes(MAX_MAP_FILE_BYTES + 1);
        } catch (IOException ex) {
            return null;
        }
    }

    private static ToolExecutionResult failure(String message) {
        return ToolExecutionResult.builder().isError(true).resultText(message).build();
    }

    private static ChatChart.TableView tableView(JsonNode input) {
        String intent = text(input, "intent", 24);
        if (intent == null) return null;
        String defaultType = switch (intent) {
            case "trend" -> "line";
            case "comparison" -> "bar";
            case "relationship" -> "scatter";
            case "composition" -> "pie";
            default -> throw new IllegalArgumentException("可用的 intent 为 trend、comparison、relationship 或 composition。");
        };
        String chartType = text(input, "chartType", 16);
        if (chartType == null) chartType = defaultType;
        boolean cartesian = "trend".equals(intent) || "comparison".equals(intent);
        if (!chartType.equals(defaultType) && !(cartesian && Set.of("bar", "line").contains(chartType))) {
            throw new IllegalArgumentException("所选 chartType 与 intent 不匹配，特殊配置可通过 optionJson 提交。");
        }

        JsonNode fields = input.path("y");
        if (!fields.isArray() || fields.isEmpty()) {
            throw new IllegalArgumentException("使用 intent 时请填写至少一个 y 列名。");
        }
        var y = new ArrayList<String>();
        for (JsonNode field : fields) y.add(columnName(field, "y"));
        return new ChatChart.TableView(intent, columnName(input.path("x"), "x"), y,
                text(input, "unit", 23), chartType);
    }

    private static String columnName(JsonNode field, String parameter) {
        if (!field.isTextual() || field.textValue().isBlank() || field.textValue().length() > 160) {
            throw new IllegalArgumentException(parameter + " 需要有效的表格列名。");
        }
        return field.textValue();
    }

    private JsonNode parse(String arguments) {
        if (arguments != null && arguments.length() > MAX_PAYLOAD) {
            throw new IllegalArgumentException("图表配置过大，请先对数据做筛选或聚合。");
        }
        JsonNode input = parseSnapshot(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (!input.isObject()) throw new IllegalArgumentException("工具参数必须为 JSON 对象。");
        return input;
    }

    private JsonNode parseSnapshot(String value) {
        try {
            JsonNode parsed = JSON.readTree(value);
            if (parsed != null) return parsed;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("图表配置需要有效的 JSON 对象。", invalid);
        }
        throw new IllegalArgumentException("图表配置不能为空。");
    }

    private static Map<String, Object> chartObject(JsonNode value) {
        return value == null ? null : JSON.convertValue(value, new TypeReference<>() {});
    }

    private static String text(JsonNode input, String field, int limit) {
        JsonNode value = input.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual() || value.textValue().length() > limit) {
            throw new IllegalArgumentException(field + " 需为最多 " + limit + " 字的文本。");
        }
        return value.textValue().isBlank() ? null : value.textValue().trim();
    }

    private static JsonNode optionalObject(JsonNode input, String field) {
        JsonNode value = input.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isObject()) throw new IllegalArgumentException(field + " 需为 JSON 对象。");
        return value;
    }

    private static List<String> extensions(JsonNode input, JsonNode option) {
        var names = new LinkedHashSet<String>();
        if (!input.isMissingNode() && !input.isNull()) {
            if (!input.isArray() || input.size() > EXTENSIONS.size()) {
                throw new IllegalArgumentException("请使用已注册的图表扩展 gl 或 bar-range。");
            }
            for (JsonNode value : input) {
                String name = value.asText("");
                if (!EXTENSIONS.contains(name)) throw new IllegalArgumentException("图表扩展暂不可用：" + name);
                names.add(name);
            }
        }
        addSeriesExtensions(option, names);
        return List.copyOf(names);
    }

    private static void addSeriesExtensions(JsonNode option, Set<String> names) {
        JsonNode series = option.path("series");
        if (series.isArray()) {
            for (JsonNode item : series) addSeriesExtension(item, names);
        } else if (series.isObject()) {
            addSeriesExtension(series, names);
        }
        if (option.path("baseOption").isObject()) addSeriesExtensions(option.path("baseOption"), names);
        for (JsonNode entry : option.path("options")) addSeriesExtensions(entry, names);
        for (JsonNode entry : option.path("media")) addSeriesExtensions(entry.path("option"), names);
    }

    private static void addSeriesExtension(JsonNode series, Set<String> names) {
        String type = series.path("type").asText("");
        if (GL_SERIES.contains(type)) names.add("gl");
        if ("custom".equals(type) && "barRange".equals(series.path("renderItem").asText())) names.add("bar-range");
    }

    private static boolean hasChartContent(JsonNode option) {
        JsonNode series = option.path("series");
        if (hasSeriesType(series)) return true;
        if (series.isArray()) {
            for (JsonNode item : series) if (hasSeriesType(item)) return true;
        }
        for (String component : List.of("graphic", "geo", "calendar", "matrix", "thumbnail")) {
            JsonNode value = option.path(component);
            if (value.isContainerNode() && !value.isEmpty()) return true;
        }
        if (hasChartContentIn(option.path("baseOption"))) return true;
        for (JsonNode variant : option.path("options")) if (hasChartContentIn(variant)) return true;
        for (JsonNode media : option.path("media")) if (hasChartContentIn(media.path("option"))) return true;
        return false;
    }

    private static boolean hasChartContentIn(JsonNode variant) {
        return variant.isObject() && hasChartContent(variant);
    }

    private static boolean hasSeriesType(JsonNode series) {
        JsonNode type = series.path("type");
        return type.isTextual() && !type.textValue().isBlank();
    }

    private static void validateTree(JsonNode node, int depth) {
        if (depth > 40) throw new IllegalArgumentException("图表配置嵌套过深。");
        for (var field : node.properties()) {
            if (PROTOTYPE_KEYS.contains(field.getKey())) throw new IllegalArgumentException("图表配置包含保留字段。");
            if ("$callback".equals(field.getKey())) {
                if (!FORMATTERS.contains(field.getValue().asText(""))) {
                    throw new IllegalArgumentException("可用的格式化回调为 number 和 percent。");
                }
                text(node, "suffix", 24);
            }
            if ("tooltip".equals(field.getKey())) validateTooltips(field.getValue());
        }
        for (JsonNode child : node) validateTree(child, depth + 1);
    }

    private static void validateTooltips(JsonNode value) {
        if (value.isArray()) {
            for (JsonNode tooltip : value) validateTooltips(tooltip);
            return;
        }
        JsonNode trigger = value.path("trigger");
        if (!trigger.isMissingNode() && !trigger.isNull() && !trigger.isTextual()) {
            throw new IllegalArgumentException("tooltip.trigger 是单个字符串，例如 axis 或 item。");
        }
        JsonNode valueFormatter = value.path("valueFormatter");
        if (!valueFormatter.isMissingNode() && !valueFormatter.isNull()
                && (!valueFormatter.isObject() || !valueFormatter.has("$callback"))) {
            throw new IllegalArgumentException("""
                    tooltip.valueFormatter 需要注册回调，例如 {"$callback":"number","suffix":" 万元"}。
                    也可以删除该字段，并在坐标轴名称中标注单位。
                    """);
        }
    }

    private static void validateTable(JsonNode table) {
        JsonNode columns = table.path("columns");
        JsonNode rows = table.path("rows");
        if (!columns.isArray() || columns.isEmpty() || !rows.isArray()) {
            throw new IllegalArgumentException("数据表需要 columns 和 rows 数组。");
        }
        for (JsonNode column : columns) {
            if (!column.isTextual()) throw new IllegalArgumentException("数据表列名需要为文本。");
        }
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() != columns.size()) {
                throw new IllegalArgumentException("数据表每行的单元格数需要与列数一致。");
            }
            for (JsonNode cell : row) {
                if (cell.isContainerNode()) throw new IllegalArgumentException("数据表单元格需要为标量值。");
            }
        }
    }
}
