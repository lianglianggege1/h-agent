package com.h.backend.chat;

import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.HarnessExecutionSession;
import com.h.backend.chat.domain.agent.HarnessEventMapper;
import com.h.backend.chat.domain.agent.HarnessSubagentEventRelay;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.interfaces.dto.HarnessAgentEventPayload;
import com.h.backend.chat.interfaces.web.HarnessSubagentEventController;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HarnessSubagentEventControllerTest {

    @Test
    void shouldReplayAndThenFinishTheObservedChildStream() {
        HarnessCollaborationService collaboration = mock(HarnessCollaborationService.class);
        when(collaboration.resolveExecutionSession(73L, "sub-live")).thenReturn(
                new HarnessExecutionSession(
                        "root", "sub-live", "gateway-child", "root",
                        "general-purpose", "完整委托"
                )
        );
        HarnessSubagentEventRelay relay = new HarnessSubagentEventRelay();
        relay.publish("73", "sub-live", new TextBlockDeltaEvent(
                "event-delta", "2026-08-14T10:00:00Z",
                "reply-live", "block-live", "第一段"
        ));
        relay.publish("73", "sub-live", new AgentEndEvent("reply-live"));
        HarnessSubagentEventController controller = new HarnessSubagentEventController(
                collaboration, relay, new HarnessEventMapper(), new com.h.backend.chat.infrastructure.config.ChatStreamProperties()
        );

        List<ServerSentEvent<ChatStreamEvent>> events = controller.events(
                        new AuthUserPrincipal(73L, "user@example.com", "USER"), "sub-live"
                )
                .getBody()
                .collectList()
                .block();

        List<HarnessAgentEventPayload> payloads = events.stream()
                .map(ServerSentEvent::data)
                .filter(java.util.Objects::nonNull)
                .map(ChatStreamEvent::payload)
                .map(HarnessAgentEventPayload.class::cast)
                .toList();
        assertEquals(List.of("TEXT_BLOCK_DELTA", "AGENT_END"),
                payloads.stream().map(HarnessAgentEventPayload::eventType).toList());
        assertEquals("sub-live", payloads.getFirst().data().get("agentSessionId"));
        assertEquals("SUBAGENT", payloads.getFirst().source().scope());
        assertEquals(1L, payloads.getFirst().sequence());
        assertEquals(2L, payloads.getLast().sequence());
    }
}
