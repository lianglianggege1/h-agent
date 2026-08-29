package com.h.agent.observability.lifecycle;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Primary/Maintenance 阶段协调契约测试（设计 7.3）：产品结果提交后 Primary 原子结束，
 * 后置工作延迟创建 Maintenance trace 并通过 OTel Link 关联 Primary 根；无后置工作
 * 则永不创建；Publisher 终止即结束 Maintenance。
 */
class ExecutionObservationCarrierTest {

    private AgentObservability observability;
    private InMemorySpanExporter exporter;

    private AgentObservability create() {
        exporter = InMemorySpanExporter.create();
        AgentObservabilityConfig config = AgentObservabilityConfig.builder()
                .baseUrl("http://langfuse.local")
                .publicKey("pk-test")
                .secretKey("sk-test")
                .rootRatio(1.0)
                .scheduleDelayMillis(10)
                .build();
        return AgentObservabilityTesting.build(config, exporter);
    }

    @AfterEach
    void tearDown() {
        if (observability != null) {
            observability.close();
        }
    }

    private AgentExecutionStart primaryStart() {
        return new AgentExecutionStart(
                "agent.run", "session-1", 42L, "general-assistant", "as-1", "CHAT", "run-1",
                List.of("env:test"), Map.of(),
                SemanticContent.ofMessages(List.of(SemanticMessage.of("user", "hello"))));
    }

    private AgentExecutionStart maintenanceStart() {
        return new AgentExecutionStart(
                "agent.maintenance", "session-1", 42L, "general-assistant", "as-1",
                "maintenance", "run-1", List.of("env:test"), Map.of(), null);
    }

    private static SemanticContent assistantReply() {
        return SemanticContent.ofMessages(List.of(SemanticMessage.of("assistant", "final answer")));
    }

    private static String traceIdOf(ObservationContext context) {
        return Span.fromContext(context.otelContext()).getSpanContext().getTraceId();
    }

    @Test
    void firstPostSubmissionWorkOpensLinkedMaintenanceTrace() {
        observability = create();
        AgentExecutionObservation primary = observability.start(primaryStart());
        ExecutionObservationCarrier carrier =
                new ExecutionObservationCarrier(observability, primary, maintenanceStart());

        // 规则 1：提交点之前新 Observation 挂 Primary 根。
        assertSame(primary.observationContext(), carrier.parentForNewObservation(null));

        carrier.completePrimary(assistantReply());
        assertTrue(carrier.inMaintenance());

        // 规则 2：切换本身不创建 Maintenance；第一项新 Observation 才延迟创建。
        assertNull(carrier.maintenanceTraceId());
        ObservationContext maintenanceParent = carrier.parentForNewObservation(null);
        assertNotNull(carrier.maintenanceTraceId());
        assertNotEquals(carrier.traceId(), carrier.maintenanceTraceId());

        AgentObservation postWork = observability.span(
                ObservationSpec.of("tool memory-extract", HObsKind.TOOL, "agentscope"),
                maintenanceParent);
        assertEquals(carrier.maintenanceTraceId(), postWork.traceId());
        postWork.succeed();

        // 规则 5：Publisher 正常终止结束 Maintenance。
        carrier.executionCompleted();
        AgentObservabilityTesting.flush(observability);

        SpanData rootSpan = spanByName("agent.run");
        SpanData maintenanceSpan = spanByName("agent.maintenance");
        SpanData toolSpan = spanByName("tool memory-extract");

        assertEquals("success", rootSpan.getAttributes().get(AttributeKey.stringKey("h.outcome")));
        assertEquals("success", maintenanceSpan.getAttributes().get(AttributeKey.stringKey("h.outcome")));
        // 规则 3：Maintenance 根通过 OTel Link 指向 Primary 根。
        assertEquals(1, maintenanceSpan.getLinks().size());
        assertEquals(rootSpan.getSpanContext(), maintenanceSpan.getLinks().get(0).getSpanContext());
        // 规则 4：沿用产品 Session、rootRunId，entry_kind 标为 maintenance。
        assertEquals("session-1", maintenanceSpan.getAttributes().get(AttributeKey.stringKey("langfuse.session.id")));
        assertEquals("run-1", maintenanceSpan.getAttributes().get(AttributeKey.stringKey("h.root_run_id")));
        assertEquals("maintenance", maintenanceSpan.getAttributes().get(AttributeKey.stringKey("h.entry_kind")));
        assertEquals(maintenanceSpan.getTraceId(), toolSpan.getTraceId());
        assertEquals(maintenanceSpan.getSpanId(), toolSpan.getParentSpanId());
    }

    @Test
    void publisherCompletionWithoutPostWorkNeverCreatesMaintenance() {
        observability = create();
        AgentExecutionObservation primary = observability.start(primaryStart());
        ExecutionObservationCarrier carrier =
                new ExecutionObservationCarrier(observability, primary, maintenanceStart());

        carrier.completePrimary(assistantReply());
        // 规则 6：无后置工作则永不创建 Maintenance。
        carrier.executionCompleted();
        AgentObservabilityTesting.flush(observability);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals("agent.run", spans.get(0).getName());
        assertEquals("success", spans.get(0).getAttributes().get(AttributeKey.stringKey("h.outcome")));
    }

    @Test
    void observationStartedBeforeSwitchStaysInPrimaryTrace() {
        observability = create();
        AgentExecutionObservation primary = observability.start(primaryStart());
        ExecutionObservationCarrier carrier =
                new ExecutionObservationCarrier(observability, primary, maintenanceStart());

        // 切换前已开始的 Observation：父级在提交点之前确定，即使结束晚于切换也不迁移。
        AgentObservation tool = observability.span(
                ObservationSpec.of("tool search", HObsKind.TOOL, "agentscope"),
                carrier.parentForNewObservation(null));
        carrier.completePrimary(assistantReply());
        // 维护期新工作进入 Maintenance trace。
        AgentObservation postWork = observability.span(
                ObservationSpec.of("tool memory-extract", HObsKind.TOOL, "agentscope"),
                carrier.parentForNewObservation(null));
        String maintenanceTrace = carrier.maintenanceTraceId();
        carrier.executionCompleted();
        tool.succeed();
        postWork.succeed();
        AgentObservabilityTesting.flush(observability);

        assertEquals(carrier.traceId(), spanByName("tool search").getTraceId());
        assertEquals(maintenanceTrace, spanByName("tool memory-extract").getTraceId());
    }

    @Test
    void publisherFailureFailsMaintenanceButKeepsSucceededPrimary() {
        observability = create();
        AgentExecutionObservation primary = observability.start(primaryStart());
        ExecutionObservationCarrier carrier =
                new ExecutionObservationCarrier(observability, primary, maintenanceStart());

        carrier.completePrimary(assistantReply());
        carrier.parentForNewObservation(null);
        // 规则 8：Publisher 失败以失败结束 Maintenance，不修改已成功的 Primary。
        carrier.executionFailed(new IllegalStateException("boom"));
        AgentObservabilityTesting.flush(observability);

        SpanData rootSpan = spanByName("agent.run");
        SpanData maintenanceSpan = spanByName("agent.maintenance");
        assertEquals("success", rootSpan.getAttributes().get(AttributeKey.stringKey("h.outcome")));
        assertEquals("failure", maintenanceSpan.getAttributes().get(AttributeKey.stringKey("h.outcome")));
        assertEquals("java.lang.IllegalStateException",
                maintenanceSpan.getAttributes().get(AttributeKey.stringKey("exception.type")));
    }

    @Test
    void failedPrimaryClosesPhaseAndFallsBackToPrimaryContext() {
        observability = create();
        AgentExecutionObservation primary = observability.start(primaryStart());
        ExecutionObservationCarrier carrier =
                new ExecutionObservationCarrier(observability, primary, maintenanceStart());

        carrier.failPrimary(new RuntimeException("kaputt"));
        assertFalse(carrier.inMaintenance());
        // 业务失败路径没有后置维护：CLOSED 后新 Observation 退回 Primary 根，不建 Maintenance。
        ObservationContext fallback = carrier.parentForNewObservation(null);
        assertEquals(carrier.traceId(), traceIdOf(fallback));
        assertNull(carrier.maintenanceTraceId());
        carrier.executionCompleted();
        AgentObservabilityTesting.flush(observability);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals("failure", spans.get(0).getAttributes().get(AttributeKey.stringKey("h.outcome")));
    }

    @Test
    void nestedAgentInsideMaintenanceKeepsAgentAsParent() {
        observability = create();
        AgentExecutionObservation primary = observability.start(primaryStart());
        ExecutionObservationCarrier carrier =
                new ExecutionObservationCarrier(observability, primary, maintenanceStart());

        carrier.completePrimary(assistantReply());
        ObservationContext maintenanceRoot = carrier.parentForNewObservation(null);
        // 维护期新 Agent 挂 Maintenance 根；该 Agent 内的嵌套调用继续挂该 Agent。
        AgentObservation maintenanceAgent = observability.span(
                ObservationSpec.of("agent memory-agent", HObsKind.AGENT, "agentscope"),
                maintenanceRoot);
        ObservationContext nested = carrier.parentForNewObservation(maintenanceAgent.context());
        assertSame(maintenanceAgent.context(), nested);
        // 旧的 Primary Agent 上下文不是 Maintenance trace：退回 Maintenance 根而不是沿用。
        ObservationContext stale = carrier.parentForNewObservation(primary.observationContext());
        assertEquals(carrier.maintenanceTraceId(), traceIdOf(stale));
        String maintenanceTrace = carrier.maintenanceTraceId();
        carrier.executionCompleted();
        maintenanceAgent.succeed();
        AgentObservabilityTesting.flush(observability);

        SpanData agentSpan = spanByName("agent memory-agent");
        assertEquals(maintenanceTrace, agentSpan.getTraceId());
        assertEquals(spanByName("agent.maintenance").getSpanId(), agentSpan.getParentSpanId());
    }

    @Test
    void nullMaintenanceStartNeverCreatesMaintenance() {
        observability = create();
        AgentExecutionObservation primary = observability.start(primaryStart());
        ExecutionObservationCarrier carrier =
                new ExecutionObservationCarrier(observability, primary, null);

        carrier.completePrimary(assistantReply());
        assertTrue(carrier.inMaintenance());
        // maintenanceStart 缺失时退化为普通根：不触发延迟创建。
        assertSame(primary.observationContext(), carrier.parentForNewObservation(null));
        carrier.executionCompleted();
        AgentObservabilityTesting.flush(observability);
        assertEquals(1, exporter.getFinishedSpanItems().size());
    }

    @Test
    void noopObservabilityKeepsCarrierSilent() {
        observability = com.h.agent.observability.NoopAgentObservability.getInstance();
        ExecutionObservationCarrier carrier =
                new ExecutionObservationCarrier(observability, null, maintenanceStart());

        carrier.completePrimary(assistantReply());
        assertNull(carrier.traceId());
        assertNotNull(carrier.parentForNewObservation(null));
        assertNull(carrier.maintenanceTraceId());
        carrier.executionCompleted();
        carrier.executionFailed(new RuntimeException("ignored"));
        carrier.executionCancelled();
    }

    private SpanData spanByName(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name));
    }
}
