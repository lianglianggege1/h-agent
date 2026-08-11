package com.h.backend.chat.infrastructure.config;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.RawMessageStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicStreamingResponseAccumulatorTest {

    @Test
    void shouldEmitOneCompleteResponseOnlyAfterMessageStop() throws Exception {
        AnthropicStreamingResponseAccumulator accumulator =
                new AnthropicStreamingResponseAccumulator();

        List<RawMessageStreamEvent> events = List.of(
                event("""
                        {"type":"message_start","message":{"id":"msg-1","content":[],"model":"test-model","role":"assistant","stop_reason":null,"stop_sequence":null,"type":"message","usage":{"input_tokens":7,"output_tokens":0}}}
                        """),
                event("""
                        {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                        """),
                event("""
                        {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}
                        """),
                event("""
                        {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}
                        """),
                event("""
                        {"type":"content_block_stop","index":0}
                        """),
                event("""
                        {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}
                        """),
                event("""
                        {"type":"message_stop"}
                        """)
        );

        for (int i = 0; i < events.size() - 1; i++) {
            assertTrue(accumulator.accumulate(events.get(i)).isEmpty());
        }

        Optional<String> completed = accumulator.accumulate(events.getLast());

        assertTrue(completed.isPresent());
        assertEquals(
                """
                {"id":"msg-1","content":[{"text":"hello world","type":"text"}],"model":"test-model","role":"assistant","stop_reason":"end_turn","stop_sequence":null,"type":"message","usage":{"input_tokens":7,"output_tokens":2}}""",
                completed.orElseThrow()
        );
    }

    private static RawMessageStreamEvent event(String json) throws Exception {
        return ObjectMappers.jsonMapper().readValue(json, RawMessageStreamEvent.class);
    }
}
