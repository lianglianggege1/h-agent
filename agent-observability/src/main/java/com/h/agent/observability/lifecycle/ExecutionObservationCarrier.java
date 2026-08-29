package com.h.agent.observability.lifecycle;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.semantic.SemanticContent;
import io.opentelemetry.api.trace.Span;

/**
 * Harness 执行的观测阶段载体（设计 7.3）：{@code PRIMARY -> MAINTENANCE -> CLOSED}。
 *
 * <p>Primary trace 在产品结果提交后原子地结束并切换阶段；切换后第一项新 Observation
 * 才延迟创建 Maintenance trace（无后置工作则永不创建），Maintenance 根通过 OTel Link
 * 指向 Primary 根并沿用产品 Session、rootRunId 与环境标签。原始 AgentScope Publisher
 * 完成、失败或取消时结束 Maintenance。切换前已开始的 Observation 保持原 Trace，不迁移父级。</p>
 *
 * <p>业务代码只把它作为类型化值放入 {@code RuntimeContext} 随执行传递；阶段切换由
 * Harness 执行在产品结果提交点显式调用，本类不读取任何业务状态。</p>
 */
public final class ExecutionObservationCarrier {

    private final AgentObservability observability;
    private final AgentExecutionObservation primary;
    private final AgentExecutionStart maintenanceStart;

    private Phase phase = Phase.PRIMARY;
    private AgentExecutionObservation maintenance;

    public ExecutionObservationCarrier(
            AgentObservability observability,
            AgentExecutionObservation primary,
            AgentExecutionStart maintenanceStart
    ) {
        this.observability = observability;
        this.primary = primary != null ? primary : NOOP_PRIMARY;
        this.maintenanceStart = maintenanceStart;
    }

    /** Primary trace 的身份；可能为空（未采样或 no-op）。 */
    public String traceId() {
        return primary.traceId();
    }

    /** 已创建的 Maintenance trace 身份；未创建时为 null（不触发延迟创建）。 */
    public String maintenanceTraceId() {
        synchronized (this) {
            return maintenance == null ? null : maintenance.traceId();
        }
    }

    public boolean inMaintenance() {
        synchronized (this) {
            return phase == Phase.MAINTENANCE;
        }
    }

    /**
     * 阶段感知的父上下文：PRIMARY 沿用当前 Agent span；MAINTENANCE 在第一次新
     * Observation 时才延迟创建 Maintenance trace。维护期新 Agent 的嵌套调用继续挂
     * 在该 Agent 下，而不是压平到 Maintenance 根。
     */
    public ObservationContext parentForNewObservation(ObservationContext currentAgent) {
        synchronized (this) {
            if (phase != Phase.MAINTENANCE || observability == null || maintenanceStart == null) {
                return currentAgent != null ? currentAgent : primary.observationContext();
            }
            if (maintenance == null) {
                maintenance = observability.startMaintenance(maintenanceStart, primary.observationContext());
            }
            ObservationContext maintenanceContext = maintenance.observationContext();
            if (currentAgent != null && sameTrace(currentAgent, maintenanceContext)) {
                return currentAgent;
            }
            return maintenanceContext;
        }
    }

    /** 产品结果提交后原子地结束 Primary 并切换到 MAINTENANCE（规则 1）。 */
    public void completePrimary(SemanticContent output) {
        synchronized (this) {
            primary.succeed(output);
            if (phase == Phase.PRIMARY) {
                phase = Phase.MAINTENANCE;
            }
        }
    }

    /** Primary 失败：业务失败路径没有后置维护，直接 CLOSED。 */
    public void failPrimary(Throwable error) {
        synchronized (this) {
            primary.fail(error);
            if (phase == Phase.PRIMARY) {
                phase = Phase.CLOSED;
            }
        }
    }

    /** Primary 取消：直接 CLOSED。 */
    public void cancelPrimary(String reason) {
        synchronized (this) {
            primary.cancel(reason);
            if (phase == Phase.PRIMARY) {
                phase = Phase.CLOSED;
            }
        }
    }

    /** 原始 Publisher 正常终止：结束 Maintenance（规则 5）；无后置工作是 no-op（规则 6）。 */
    public void executionCompleted() {
        synchronized (this) {
            AgentExecutionObservation pending = maintenance;
            maintenance = null;
            phase = Phase.CLOSED;
            if (pending != null) {
                pending.succeed(null);
            }
        }
    }

    /** 原始 Publisher 失败：以失败结束 Maintenance；不修改已成功的 Primary（规则 8）。 */
    public void executionFailed(Throwable error) {
        synchronized (this) {
            AgentExecutionObservation pending = maintenance;
            maintenance = null;
            phase = Phase.CLOSED;
            if (pending != null) {
                pending.fail(error);
            }
        }
    }

    /** 原始 Publisher 取消：以取消结束 Maintenance。 */
    public void executionCancelled() {
        synchronized (this) {
            AgentExecutionObservation pending = maintenance;
            maintenance = null;
            phase = Phase.CLOSED;
            if (pending != null) {
                pending.cancel("publisher cancelled");
            }
        }
    }

    private static boolean sameTrace(ObservationContext left, ObservationContext right) {
        String leftTrace = traceIdOf(left);
        String rightTrace = traceIdOf(right);
        return leftTrace != null && leftTrace.equals(rightTrace);
    }

    private static String traceIdOf(ObservationContext context) {
        if (context == null || context.otelContext() == null) {
            return null;
        }
        io.opentelemetry.api.trace.SpanContext spanContext =
                Span.fromContext(context.otelContext()).getSpanContext();
        return spanContext.isValid() ? spanContext.getTraceId() : null;
    }

    enum Phase {
        PRIMARY, MAINTENANCE, CLOSED
    }

    /** primary 为 null 的防御路径，保持 no-op 语义。 */
    private static final AgentExecutionObservation NOOP_PRIMARY = new AgentExecutionObservation() {
        @Override
        public String traceId() {
            return null;
        }

        @Override
        public ObservationContext observationContext() {
            return ObservationContext.root();
        }

        @Override
        public ObservationScope scope() {
            return () -> {
            };
        }

        @Override
        public void succeed(SemanticContent output) {
        }

        @Override
        public void fail(Throwable error) {
        }

        @Override
        public void cancel(String reason) {
        }

        @Override
        public void close() {
        }
    };
}
