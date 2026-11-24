package com.h.backend.chat.application.chart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.capabilities.chart.ChatChart;
import com.h.capabilities.chart.ChatChartTools;
import com.h.backend.chat.infrastructure.persistence.mapper.AguiRunSnapshotMapper;
import com.h.capabilities.chat.infrastructure.filesystem.AssistantFileStorage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatChartToolsTest {
    @Test
    void resolvesGeoJsonFilesOnlyFromTheCurrentChatAndNormalizesTheCollection(@TempDir Path temp) throws Exception {
        var files = new AssistantFileStorage(temp, 4 * 1024 * 1024);
        var tools = new ChatChartTools(files);
        String owner = "7:1:same-session";
        String geoJson = """
                {"features":[{"type":"Feature","properties":{"name":"测试区域"},
                  "geometry":{"type":"Polygon","coordinates":[[[0,0],[1,0],[1,1],[0,0]]]}}]}
                """;
        assertFalse(files.write(owner, "/china.geojson", geoJson).startsWith("Error:"));
        String parameters = """
                {"title":"地图","option":{"series":[{"type":"map","map":"china"}]},
                 "maps":{"china":"/china.geojson"}}
                """;

        var result = show(tools, parameters, owner);
        assertFalse(result.isError(), result.resultText());
        var chart = assertInstanceOf(ChatChart.class, result.result());
        var maps = new ObjectMapper().valueToTree(chart.maps());
        assertEquals("FeatureCollection", maps.at("/china/type").asText());
        assertEquals("测试区域", maps.at("/china/features/0/properties/name").asText());
        assertTrue(show(tools, parameters, "7:1:another-session").isError());
        Path outside = Files.writeString(temp.resolve("outside.geojson"), geoJson);
        Files.createSymbolicLink(temp.resolve("7/same-session/linked.geojson"), outside);
        assertTrue(show(tools, parameters.replace("china.geojson", "linked.geojson"), owner).isError());
        var url = show(tools, parameters.replace("/china.geojson", "https://example.test/china.geojson"), owner);
        assertTrue(url.isError());
        assertTrue(url.resultText().contains("会话工作区"));
    }

    @Test
    void fillsSurfaceShapeOnlyForACompleteGrid() {
        var tools = new ChatChartTools(null);
        var json = new ObjectMapper();
        String input = """
                {"title":"曲面","option":{"series":[{"type":"surface","data":[
                   [0,0,12],[0,1,14],[0,2,16],[1,0,18],[1,1,20],[1,2,22]]}]}}
                """;
        var complete = assertInstanceOf(ChatChart.class, show(tools, input).result());
        var shape = json.valueToTree(complete.option()).at("/series/0/dataShape");
        assertEquals(2, shape.get(0).asInt());
        assertEquals(3, shape.get(1).asInt());
        var irregular = assertInstanceOf(ChatChart.class, show(tools, input.replace("[1,1,20]", "[1,3,20]")).result());
        assertTrue(json.valueToTree(irregular.option()).at("/series/0/dataShape").isMissingNode());
    }

    @Test
    void usesOneTableForTheChartAndPreservesTheMappingForFollowUp() {
        var tools = new ChatChartTools(null);
        var json = new ObjectMapper();
        var result = show(tools, """
                {"title":"月度销售额","intent":"trend","x":"月份","y":["销售额"],"unit":"万元",
                 "table":{"columns":["月份","销售额"],"rows":[["一月",12],["二月",null],["三月",15]]}}
                """);
        assertFalse(result.isError(), result.resultText());
        var chart = assertInstanceOf(ChatChart.class, result.result());
        var option = json.valueToTree(chart.option());
        var table = json.valueToTree(chart.table());
        assertEquals("line", option.at("/series/0/type").asText());
        assertEquals("万元", option.at("/yAxis/name").asText());
        assertEquals(" 万元", option.at("/tooltip/valueFormatter/suffix").asText());
        assertEquals(3, table.path("rows").size());
        for (int i = 0; i < table.path("rows").size(); i++) {
            assertEquals(table.path("rows").get(i).get(0), option.at("/xAxis/data").get(i));
            assertEquals(table.path("rows").get(i).get(1), option.at("/series/0/data").get(i));
        }

        var revised = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(chart.view());
        revised.put("chartType", "bar").put("title", "月度销售额对比");
        revised.set("table", table);
        var next = show(tools, revised.toString());
        assertFalse(next.isError(), next.resultText());
        var comparison = assertInstanceOf(ChatChart.class, next.result());
        assertEquals("bar", json.valueToTree(comparison.option()).at("/series/0/type").asText());
        assertEquals(chart.table(), comparison.table());
        assertNotEquals(chart.id(), comparison.id());
    }

    @Test
    void buildsNumericPairsForRelationshipsAndNamedSlicesForComposition() {
        var tools = new ChatChartTools(null);
        var json = new ObjectMapper();
        var scatter = assertInstanceOf(ChatChart.class, show(tools, """
                {"title":"关系","intent":"relationship","x":"体积","y":["重量"],
                 "table":{"columns":["体积","重量"],"rows":[[8,2],[12,3]]}}
                """).result());
        var scatterOption = json.valueToTree(scatter.option());
        assertEquals("value", scatterOption.at("/xAxis/type").asText());
        assertEquals(12, scatterOption.at("/series/0/data/1/0").asInt());
        assertEquals(3, scatterOption.at("/series/0/data/1/1").asInt());

        var pie = assertInstanceOf(ChatChart.class, show(tools, """
                {"title":"构成","intent":"composition","x":"地区","y":["销量"],
                 "table":{"columns":["地区","销量"],"rows":[["华东",40],["华南",60]]}}
                """).result());
        var pieOption = json.valueToTree(pie.option());
        assertEquals("pie", pieOption.at("/series/0/type").asText());
        assertEquals("华南", pieOption.at("/series/0/data/1/name").asText());
        assertEquals(60, pieOption.at("/series/0/data/1/value").asInt());
    }

    @Test
    void rejectsUnknownFieldsAndNonNumericValuesWithUsefulFeedback() {
        var tools = new ChatChartTools(null);
        var result = show(tools, """
                {"title":"趋势","intent":"trend","x":"月","y":["销售额"],
                 "table":{"columns":["月","销量"],"rows":[["一月",12]]}}
                """);
        assertTrue(result.isError());
        assertTrue(result.resultText().contains("可用列：月、销量"));

        result = show(tools, """
                {"title":"趋势","intent":"trend","x":"月","y":["销量"],
                 "table":{"columns":["月","销量"],"rows":[["一月","12"] ]}}
                """);
        assertTrue(result.isError());
        assertTrue(result.resultText().contains("需要 JSON 数字"));

        result = show(tools, """
                {"title":"构成","intent":"composition","x":"地区","y":["销量"],
                 "table":{"columns":["地区","销量"],"rows":[["华东",-3],["华南",6]]}}
                """);
        assertTrue(result.isError());
        assertTrue(result.resultText().contains("非负数"));
    }

    @Test
    void publishesNativeOptionsWhileGivingTheModelACompactToolResult() {
        var tools = new ChatChartTools(null);
        var result = show(tools, """
                {
                  "title":"调用流","source":"日志聚合",
                  "option":{"series":[{"type":"sankey","links":[{"source":"app","target":"db","value":3}]}]},
                  "table":{"columns":["目标","次数"],"rows":[["db",3]]}
                }
                """);

        assertFalse(result.isError());
        var chart = assertInstanceOf(ChatChart.class, result.result());
        var mapper = new ObjectMapper();
        assertEquals("sankey", mapper.valueToTree(chart.option()).at("/series/0/type").asText());
        assertEquals(3, mapper.valueToTree(chart.table()).at("/rows/0/1").asInt());
        assertTrue(result.resultText().contains(chart.id()));
        assertFalse(result.resultText().contains("sankey"));
    }

    @Test
    void acceptsTheModelsSerializedOptionAndInfersRegisteredExtensions() throws Exception {
        var tools = new ChatChartTools(null);
        var input = new ObjectMapper().createObjectNode();
        input.put("title", "区间图");
        input.put("optionJson", """
                {"xAxis":{},"yAxis":{"type":"category","data":["API"]},
                 "series":[{"type":"custom","renderItem":"barRange","data":[[0,12,29]],
                            "encode":{"x":[1,2],"y":0}}]}
                """);
        var result = show(tools, input.toString());
        assertFalse(result.isError());
        var chart = assertInstanceOf(ChatChart.class, result.result());
        assertEquals(List.of("bar-range"), chart.extensions());
        assertTrue(chart.option().containsKey("series"));
    }

    @Test
    void rejectsTheTooltipOnlyOptionSeenWithTheRealModel() {
        var tools = new ChatChartTools(null);
        var input = new ObjectMapper().createObjectNode();
        input.put("title", "趋势图");
        input.put("optionJson", """
                {"tooltip":{"trigger":"axis","axisPointer":{"type":"line"}}}
                """);
        var result = show(tools, input.toString());
        assertTrue(result.isError());
        assertTrue(result.resultText().contains("缺少可绘制内容"));
        assertNull(result.result());
    }

    @Test
    void returnsAUsableCorrectionForAnInvalidTooltipFormatter() {
        var tools = new ChatChartTools(null);
        var input = new ObjectMapper().createObjectNode();
        input.put("title", "趋势图");
        input.put("optionJson", """
                {"series":[{"type":"line","data":[12,17]}],"tooltip":{"valueFormatter":"万元"}}
                """);
        var result = show(tools, input.toString());
        assertTrue(result.isError());
        assertTrue(result.resultText().contains("\"$callback\":\"number\""));

        input.put("optionJson", """
                {"series":[{"type":"line","data":[12,17]}],
                 "tooltip":{"valueFormatter":{"$callback":"number","suffix":" 万元"}}}
                """);
        assertFalse(show(tools, input.toString()).isError());
    }

    @Test
    void validatesTheChartInputBeforePublication() {
        var tools = new ChatChartTools(null);
        assertTrue(show(tools, "{\"title\":\"图\",\"option\":[]}").isError());
        assertTrue(show(tools, "{\"title\":\"图\",\"option\":{\"__proto__\":{}}}").isError());
        assertTrue(show(tools, "{\"title\":\"图\",\"option\":{\"series\":[]},\"extensions\":[\"missing\"]}").isError());
    }

    @Test
    void readsOnlyChartSnapshotsOwnedByTheCurrentSession() {
        var snapshots = mock(AguiRunSnapshotMapper.class);
        when(snapshots.list(7L, "same-session")).thenReturn(List.of(
                new AguiRunSnapshotMapper.Snapshot("r1", "same-session", 7L, "hash", "COMPLETED", """
                        {"activities":[{"activityType":"h-agent.chart","content":{"chart":{"id":"saved","title":"旧图"}}}]}
                        """, null)));
        var tools = new ChatChartTools(null);
        var history = new ChatChartHistory(snapshots);
        var provider = tools.provider(memoryId -> {
            String value = String.valueOf(memoryId);
            var scope = value.split(":", 3);
            return tools.session(value, id -> history.read(Long.parseLong(scope[0]), scope[2], id), ignored -> {});
        });
        assertTrue(provider.provideTools(null).aiServiceTools().isEmpty());
        var request = ToolProviderRequest.builder()
                .userMessage(UserMessage.from("调整图表"))
                .invocationContext(InvocationContext.builder()
                        .invocationParameters(InvocationParameters.from(ChatChartTools.ENABLE_PARAMETER, true)).build())
                .build();
        var available = provider.provideTools(request).aiServiceTools();
        var guide = available.stream()
                .filter(tool -> "get_chart_option_guide".equals(tool.toolSpecification().name()))
                .findFirst().orElseThrow().toolExecutor();
        var guideCall = ToolExecutionRequest.builder().name("get_chart_option_guide")
                .arguments("{\"topic\":\"bar-range\"}").build();
        assertTrue(guide.execute(guideCall, "7:1:same-session").contains("\"renderItem\":\"barRange\""));
        var read = available.stream()
                .filter(tool -> "read_chat_chart".equals(tool.toolSpecification().name()))
                .findFirst().orElseThrow().toolExecutor();
        var call = ToolExecutionRequest.builder().name("read_chat_chart").arguments("{\"id\":\"saved\"}").build();
        assertTrue(read.execute(call, "7:1:same-session").contains("旧图"));
        assertTrue(read.execute(ToolExecutionRequest.builder().name("read_chat_chart").build(),
                "7:1:same-session").contains("旧图"));
        assertFalse(read.execute(call, "7:1:another-session").contains("旧图"));
        assertFalse(read.execute(call, "8:1:same-session").contains("旧图"));
    }
    private static dev.langchain4j.service.tool.ToolExecutionResult show(ChatChartTools tools, String arguments) {
        return show(tools, arguments, null);
    }

    private static dev.langchain4j.service.tool.ToolExecutionResult show(ChatChartTools tools, String arguments, String memoryId) {
        return tools.invoke(ChatChart.TOOL_NAME, arguments, tools.session(memoryId, id -> "", ignored -> {}));
    }
}
