package com.h.backend.chat.controller;

import com.h.backend.chat.agent.AgentDefinition;
import com.h.backend.chat.agent.AgentRegistry;
import com.h.backend.chat.agent.AgentTopologyMapper;
import com.h.backend.chat.dto.AgentSummaryDto;
import com.h.backend.chat.dto.AgentTopologyDto;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.common.exception.BusinessException;
import dev.langchain4j.agentic.planner.AgentInstance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRegistry agentRegistry;
    private final AgentTopologyMapper topologyMapper;

    public AgentController(AgentRegistry agentRegistry, AgentTopologyMapper topologyMapper) {
        this.agentRegistry = agentRegistry;
        this.topologyMapper = topologyMapper;
    }

    @GetMapping
    public ApiResponse<List<AgentSummaryDto>> list() {
        return ApiResponse.ok(agentRegistry.listEnabled().stream()
                .map(this::toSummary)
                .toList());
    }

    @GetMapping("/{agentId}/topology")
    public ApiResponse<AgentTopologyDto> topology(@PathVariable String agentId) {
        AgentDefinition definition = agentRegistry.requireEnabled(agentId);
        if (!(definition.agentBean() instanceof AgentInstance agentInstance)) {
            throw new BusinessException(41002, "该 Agent 暂不支持编排拓扑");
        }
        return ApiResponse.ok(topologyMapper.from(definition, agentInstance));
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
}
