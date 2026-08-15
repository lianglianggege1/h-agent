package com.h.backend.chat.domain.agent;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HarnessSubagentEventRelayTest {

    @Test
    void shouldReplayEventsThatArrivedBeforeTheChildPageSubscribed() {
        var relay = new HarnessSubagentEventRelay();
        relay.publish("73", "sub-a", new TextBlockDeltaEvent("reply-a", "block-a", "第一段"));
        relay.publish("73", "sub-a", new TextBlockDeltaEvent("reply-a", "block-a", "第二段"));

        List<String> observed = new ArrayList<>();
        relay.subscribe("73", "sub-a", relayed -> observed.add(
                relayed.sequence() + ":" + ((TextBlockDeltaEvent) relayed.event()).getDelta()
        ));

        assertEquals(List.of("1:第一段", "2:第二段"), observed);
    }

    @Test
    void shouldNeverReplayAnotherUsersOrAnotherChildsEvents() {
        var relay = new HarnessSubagentEventRelay();
        relay.publish("73", "sub-a", new TextBlockDeltaEvent("reply-a", "block-a", "A"));
        relay.publish("73", "sub-b", new TextBlockDeltaEvent("reply-b", "block-b", "B"));
        relay.publish("74", "sub-a", new TextBlockDeltaEvent("reply-c", "block-c", "C"));

        List<String> observed = new ArrayList<>();
        relay.subscribe("73", "sub-a", relayed -> observed.add(
                ((TextBlockDeltaEvent) relayed.event()).getDelta()
        ));

        assertEquals(List.of("A"), observed);
    }

    @Test
    void shouldReplayOnlyTheCurrentTurnAfterAChildStartsAgain() {
        var relay = new HarnessSubagentEventRelay();
        relay.publish("73", "sub-a", new AgentStartEvent("sub-a", "old-reply", "child"));
        relay.publish("73", "sub-a", new TextBlockDeltaEvent("old-reply", "old-block", "旧回答"));
        relay.publish("73", "sub-a", new AgentEndEvent("old-reply"));
        relay.publish("73", "sub-a", new AgentStartEvent("sub-a", "new-reply", "child"));
        relay.publish("73", "sub-a", new TextBlockDeltaEvent("new-reply", "new-block", "新回答"));

        List<String> observed = new ArrayList<>();
        relay.subscribe("73", "sub-a", relayed -> observed.add(relayed.event().getType().name()));

        assertEquals(List.of("AGENT_START", "TEXT_BLOCK_DELTA"), observed);
    }
}
