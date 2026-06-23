package com.h.backend.chat.controller;

import com.h.backend.chat.agent.AgentDefinition;
import com.h.backend.chat.agent.AgentRegistry;
import com.h.backend.chat.dto.AgentSummaryDto;
import com.h.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRegistry agentRegistry;

    public AgentController(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @GetMapping
    public ApiResponse<List<AgentSummaryDto>> list() {
        return ApiResponse.ok(agentRegistry.listEnabled().stream()
                .map(this::toSummary)
                .toList());
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
