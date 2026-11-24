package com.h.backend.chat.application.chart;

import com.h.capabilities.chart.ChatChart;
import com.h.capabilities.chart.ChatChartTools;
import com.h.capabilities.skill.infrastructure.artifact.BundledSystemSkillArtifacts;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AgentScopeChartToolsTest {
    @Test
    void readsGeoJsonFromTheChildsActiveFilesystemToolAndPublishesInThatScope() {
        var chartTools = new AgentScopeChartTools(new ChatChartTools(null), mock(BundledSystemSkillArtifacts.class));
        var toolkit = new Toolkit();
        var files = mock(AgentTool.class);
        when(files.getName()).thenReturn("read_file");
        when(files.getDescription()).thenReturn("读取当前会话文件");
        when(files.getParameters()).thenReturn(Map.of());
        when(files.callAsync(any())).thenAnswer(invocation -> {
            ToolCallParam params = invocation.getArgument(0);
            assertEquals("/boundaries.geojson", params.getInput().get("path"));
            assertEquals("child-session", params.getRuntimeContext().getSessionId());
            return Mono.just(ToolResultBlock.text("""
                    {"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"测试区"},
                     "geometry":{"type":"Polygon","coordinates":[[[0,0],[0,1],[1,1],[0,0]]]}}]}
                    """));
        });
        toolkit.registerAgentTool(files);
        var charts = new ArrayList<ChatChart>();
        chartTools.register(toolkit, context -> {
            assertEquals("child-session", context.getSessionId());
            return new ChatChartTools.Scope(id -> "", charts::add, null);
        });
        var agent = mock(ReActAgent.class);
        when(agent.getToolkit()).thenReturn(toolkit);
        var context = RuntimeContext.builder().userId("7").sessionId("child-session").build();
        var input = Map.<String, Object>of(
                "title", "区域图", "optionJson", "{\"series\":[{\"type\":\"map\",\"map\":\"sample\"}]}",
                "maps", Map.of("sample", "/boundaries.geojson"));
        var call = ToolCallParam.builder().agent(agent).runtimeContext(context).input(input)
                .toolUseBlock(ToolUseBlock.builder().name("show_chart").id("chart-1").input(input).build())
                .build();
        toolkit.getTool("show_chart").callAsync(call).block(Duration.ofSeconds(3));

        assertEquals(1, charts.size());
        assertEquals("child-session", context.getSessionId());
        assertTrue(charts.getFirst().maps().containsKey("sample"));
        assertEquals(List.of("show_chart", "read_chat_chart", "get_chart_option_guide").stream().sorted().toList(),
                toolkit.getToolSchemas().stream().map(tool -> tool.getName())
                        .filter(name -> !"read_file".equals(name)).sorted().toList());
    }
}
