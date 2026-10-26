package com.h.backend.chat;

import com.h.backend.chat.application.HarnessMemoryDocumentManager;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.interfaces.web.HarnessMemoryDocumentExceptionAdvisor;
import com.h.backend.chat.interfaces.web.MeHarnessMemoryController;
import com.h.backend.common.exception.GlobalExceptionHandler;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentScopedMemoryControllerTest {
    private final InMemoryStore store = new InMemoryStore();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var registry = new AgentRegistry(List.of(
                HarnessMemoryTestAgents.agent("harness-agent"),
                HarnessMemoryTestAgents.agent("order-processing-assistant"),
                HarnessMemoryTestAgents.agent("new-assistant"),
                new AgentDefinition("disabled", "disabled", "", List.of(), "", new Object(),
                        AgentRuntimeType.HARNESS_STREAMING, false, true),
                new AgentDefinition("no-memory", "no-memory", "", List.of(), "", new Object(),
                        AgentRuntimeType.HARNESS_STREAMING, true),
                new AgentDefinition("other-runtime", "other-runtime", "", List.of(), "", new Object(),
                        AgentRuntimeType.AGENTIC_SYNC, true, true)));
        var manager = new HarnessMemoryDocumentManager(store, Clock.systemUTC(), registry);
        mvc = MockMvcBuilders.standaloneSetup(new MeHarnessMemoryController(manager))
                .setControllerAdvice(new HarnessMemoryDocumentExceptionAdvisor(), new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        authenticate(7);
    }

    private void authenticate(long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthUserPrincipal(userId, "user@example.com", "USER"), null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void catalogOnlyIncludesEnabledMemoryCapableHarnessAgentsIncludingNewRegistrations() throws Exception {
        mvc.perform(get("/api/me/memory/agents"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("""
                        {"code":0,"message":"OK","data":[
                          {"agentId":"harness-agent","displayName":"harness-agent"},
                          {"agentId":"order-processing-assistant","displayName":"order-processing-assistant"},
                          {"agentId":"new-assistant","displayName":"new-assistant"}
                        ]}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void savesAndReadsAreIsolatedByAgentAndAuthenticatedUser() throws Exception {
        mvc.perform(put("/api/me/memory").param("agentId", "new-assistant").param("userId", "8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"private memory\",\"expectedRevision\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(1));
        mvc.perform(get("/api/me/memory").param("agentId", "new-assistant"))
                .andExpect(jsonPath("$.data.content").value("private memory"));
        mvc.perform(get("/api/me/memory"))
                .andExpect(jsonPath("$.data.exists").value(false));
        mvc.perform(get("/api/me/memory").param("agentId", "order-processing-assistant"))
                .andExpect(jsonPath("$.data.exists").value(false));
        authenticate(8);
        mvc.perform(get("/api/me/memory").param("agentId", "new-assistant"))
                .andExpect(jsonPath("$.data.exists").value(false));
        assertEquals("private memory", store.get(List.of("agents", "new-assistant", "users", "7", "root"),
                "/MEMORY.md").value().get("content"));
    }

    @Test
    void unknownDisabledAndMemorylessAgentsCannotBeReadOrWritten() throws Exception {
        for (String id : List.of("unknown", "disabled", "no-memory", "other-runtime", "user-subagent")) {
            mvc.perform(get("/api/me/memory").param("agentId", id)).andExpect(status().isBadRequest());
            mvc.perform(put("/api/me/memory").param("agentId", id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"rejected\",\"expectedRevision\":0}"))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, store.size());
    }

    @Test
    void selectedAgentRetainsRevisionConflictProtection() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put("/api/me/memory").param("agentId", "new-assistant")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"memory\",\"expectedRevision\":0}"))
                    .andExpect(status().is(attempt == 0 ? 200 : 409))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }
}
