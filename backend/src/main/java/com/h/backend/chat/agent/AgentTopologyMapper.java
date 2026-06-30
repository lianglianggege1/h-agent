package com.h.backend.chat.agent;

import com.h.backend.chat.dto.AgentSummaryDto;
import com.h.backend.chat.dto.AgentTopologyDto;
import com.h.backend.chat.dto.AgentTopologyNodeDto;
import com.h.backend.chat.dto.LoopMetaDto;
import com.h.backend.chat.dto.StateKeyDto;
import dev.langchain4j.agentic.planner.AgentArgument;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.planner.Planner;
import dev.langchain4j.agentic.supervisor.SupervisorPlanner;
import dev.langchain4j.agentic.workflow.ConditionalAgent;
import dev.langchain4j.agentic.workflow.ConditionalAgentInstance;
import dev.langchain4j.agentic.workflow.LoopAgentInstance;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentTopologyMapper {

    private static final String[] KEY_PALETTE = {
            "#e63946", "#457b9d", "#2a9d8f", "#e9c46a", "#7b2d8e",
            "#f4a261", "#264653", "#d62828", "#6a994e", "#bc6c25"
    };

    public AgentTopologyDto from(AgentDefinition definition, AgentInstance root) {
        return new AgentTopologyDto(toSummary(definition), toNode(root, null), collectStateKeys(root));
    }

    private AgentTopologyNodeDto toNode(AgentInstance agent, String condition) {
        Map<String, String> conditions = conditionsOf(agent);
        List<AgentTopologyNodeDto> children = subagents(agent).stream()
                .map(child -> toNode(child, conditions.get(child.agentId())))
                .toList();

        return new AgentTopologyNodeDto(
                agent.agentId(),
                agent.name(),
                topologyName(agent),
                agent.type() == null ? null : agent.type().getSimpleName(),
                agent.description(),
                simpleTypeName(agent.outputType()),
                agent.plannerType() == null ? null : agent.plannerType().getSimpleName(),
                agent.outputKey(),
                arguments(agent).stream()
                        .map(AgentArgument::name)
                        .filter(this::isVisibleStateKey)
                        .toList(),
                condition,
                agent.async(),
                loopMeta(agent),
                children
        );
    }

    private AgentSummaryDto toSummary(AgentDefinition definition) {
        return new AgentSummaryDto(
                definition.agentId(),
                definition.displayName(),
                definition.domain(),
                definition.tags(),
                definition.summary(),
                definition.runtimeType().name(),
                definition.enabled()
        );
    }

    private Map<String, String> conditionsOf(AgentInstance agent) {
        if (agent.topology() != AgenticSystemTopology.ROUTER) {
            return Map.of();
        }

        Map<String, String> conditions = new LinkedHashMap<>();
        for (ConditionalAgent conditionalAgent : agent.as(ConditionalAgentInstance.class).conditionalSubagents()) {
            if (conditionalAgent.condition() == null) {
                continue;
            }
            for (AgentInstance child : conditionalAgent.agentInstances()) {
                conditions.put(child.agentId(), conditionalAgent.condition());
            }
        }
        return conditions;
    }

    private LoopMetaDto loopMeta(AgentInstance agent) {
        if (agent.topology() != AgenticSystemTopology.LOOP) {
            return null;
        }

        LoopAgentInstance loop = agent.as(LoopAgentInstance.class);
        return new LoopMetaDto(loop.maxIterations(), loop.exitCondition(), loop.testExitAtLoopEnd());
    }

    private List<StateKeyDto> collectStateKeys(AgentInstance root) {
        Map<String, StateKeyDto> keys = new LinkedHashMap<>();
        collectStateKeys(root, keys);
        return List.copyOf(keys.values());
    }

    private void collectStateKeys(AgentInstance agent, Map<String, StateKeyDto> keys) {
        for (AgentArgument argument : arguments(agent)) {
            if (!isVisibleStateKey(argument.name())) {
                continue;
            }
            keys.putIfAbsent(
                    argument.name(),
                    new StateKeyDto(argument.name(), simpleTypeName(argument.type()), colorFor(argument.name()))
            );
        }

        if (isVisibleStateKey(agent.outputKey())) {
            keys.putIfAbsent(
                    agent.outputKey(),
                    new StateKeyDto(agent.outputKey(), simpleTypeName(agent.outputType()), colorFor(agent.outputKey()))
            );
        }

        for (AgentInstance child : subagents(agent)) {
            collectStateKeys(child, keys);
        }
    }

    private String simpleTypeName(Type type) {
        if (type == null) {
            return null;
        }
        if (type instanceof Class<?> cls) {
            return cls.getSimpleName();
        }
        return type.getTypeName();
    }

    private String colorFor(String key) {
        return KEY_PALETTE[Math.floorMod(key.hashCode(), KEY_PALETTE.length)];
    }

    private String topologyName(AgentInstance agent) {
        Class<? extends Planner> plannerType = agent.plannerType();
        if (plannerType != null && SupervisorPlanner.class.isAssignableFrom(plannerType)) {
            return AgenticSystemTopology.STAR.name();
        }
        return agent.topology() == null ? null : agent.topology().name();
    }

    private List<AgentArgument> arguments(AgentInstance agent) {
        return agent.arguments() == null ? List.of() : agent.arguments();
    }

    private List<AgentInstance> subagents(AgentInstance agent) {
        return agent.subagents() == null ? List.of() : agent.subagents();
    }

    private boolean isVisibleStateKey(String key) {
        return key != null && !key.isBlank() && !key.startsWith("@");
    }
}
