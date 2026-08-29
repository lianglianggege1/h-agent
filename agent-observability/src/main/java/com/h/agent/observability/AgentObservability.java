package com.h.agent.observability;

import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.lifecycle.AgentObservationLifecycle;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.lifecycle.ObservationScope;

import java.util.Map;

public interface AgentObservability extends AgentObservationLifecycle {

    String INSTRUMENTATION_SCOPE = "com.h.agent.observability";

    boolean enabled();

    LangfuseRuntimeStatus status();

    /**
     * 开始一条 Maintenance trace（设计 7.3 规则 3/4）：新根 span 通过 OTel Link 指向
     * primary 根，并沿用其 Session、rootRunId 与环境标签。linkedPrimary 为 null 或
     * 无效 span 时退化为无 Link 的普通根。
     */
    AgentExecutionObservation startMaintenance(AgentExecutionStart start, ObservationContext linkedPrimary);

    AgentObservation span(ObservationSpec spec, ObservationContext parent);

    ObservationContext currentContext();

    ObservationScope scope(ObservationContext context);

    ObservationContext extract(Map<String, String> headers);

    void inject(ObservationContext context, Map<String, String> headers);

    void close();
}
