package com.h.backend.chat.infrastructure.ai;

import com.h.backend.chat.domain.agent.DecisionRouterAgentInstance;
import dev.langchain4j.agentic.patterns.decisionrouter.DecisionRouterPlanner;
import dev.langchain4j.agentic.planner.AgentArgument;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.planner.Planner;
import dev.langchain4j.model.decision.DecisionModel;

import java.lang.reflect.Type;
import java.util.List;

/** Adds static topology metadata to the native planner without changing its execution semantics. */
public class TopologyAwareDecisionRouterPlanner extends DecisionRouterPlanner {

    private final Double activationThreshold;

    public TopologyAwareDecisionRouterPlanner(DecisionModel decisionModel) {
        super(decisionModel);
        this.activationThreshold = null;
    }

    public TopologyAwareDecisionRouterPlanner(DecisionModel decisionModel, double activationThreshold) {
        super(decisionModel, activationThreshold);
        if (!Double.isFinite(activationThreshold)) {
            throw new IllegalArgumentException("Activation threshold must be finite");
        }
        this.activationThreshold = activationThreshold;
    }

    @Override
    public <T extends AgentInstance> T as(Class<T> agentType, AgentInstance agent) {
        if (agentType == DecisionRouterAgentInstance.class) {
            return agentType.cast(new MetadataAgent(agent, activationThreshold));
        }
        return super.as(agentType, agent);
    }

    private record MetadataAgent(AgentInstance delegate, Double activationThreshold)
            implements DecisionRouterAgentInstance {
        @Override
        public Class<?> type() { return delegate.type(); }

        @Override
        public Class<? extends Planner> plannerType() { return delegate.plannerType(); }

        @Override
        public String name() { return delegate.name(); }

        @Override
        public String agentId() { return delegate.agentId(); }

        @Override
        public String description() { return delegate.description(); }

        @Override
        public Type outputType() { return delegate.outputType(); }

        @Override
        public String outputKey() { return delegate.outputKey(); }

        @Override
        public boolean async() { return delegate.async(); }

        @Override
        public boolean optional() { return delegate.optional(); }

        @Override
        public List<AgentArgument> arguments() { return delegate.arguments(); }

        @Override
        public AgentInstance parent() { return delegate.parent(); }

        @Override
        public List<AgentInstance> subagents() { return delegate.subagents(); }

        @Override
        public AgenticSystemTopology topology() { return delegate.topology(); }

        @Override
        public <T extends AgentInstance> T as(Class<T> agentType) {
            return agentType.isInstance(this) ? agentType.cast(this) : delegate.as(agentType);
        }
    }
}
