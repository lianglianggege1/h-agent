package com.h.backend.chat.agui;

import com.h.backend.chat.application.agui.AguiChatRuntime;
import com.h.backend.chat.infrastructure.config.ChatStreamProperties;
import com.h.backend.chat.interfaces.web.AguiChatController;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AguiChatControllerTest {
    @Test
    void acceptsSdkRunInputAndFramesSdkJsonExactlyOnce() {
        String body = """
                {"threadId":"thread","runId":"run","messages":[{"id":"u","role":"user","content":"hello"}],
                 "tools":[],"context":[],"state":{},"forwardedProps":{}}
                """;
        var input = JsonUtils.getJsonCodec().fromJson(body, RunAgentInput.class);
        var runtime = mock(AguiChatRuntime.class);
        when(runtime.run(eq(1L), any(RunAgentInput.class))).thenReturn(Flux.just(new AguiEvent.RunStarted("thread", "run"),
                new AguiEvent.TextMessageStart("thread", "run", "answer", "assistant"),
                new AguiEvent.TextMessageContent("thread", "run", "answer", "hello"),
                new AguiEvent.TextMessageEnd("thread", "run", "answer"),
                new AguiEvent.RunFinished("thread", "run")));
        var controller = new AguiChatController(runtime, new ChatStreamProperties());
        var response = controller.run(new AuthUserPrincipal(1L, "test@example.com", "USER"), body);
        var frames = response.getBody().collectList().block(Duration.ofSeconds(2));
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertEquals("keepalive", frames.getFirst().comment());
        var json = JsonMapper.builder().build();
        assertEquals("RUN_STARTED", json.readTree(frames.get(1).data()).get("type").asString());
        assertEquals("RUN_FINISHED", json.readTree(frames.getLast().data()).get("type").asString());
        assertFalse(frames.get(1).data().startsWith("data:"));
        assertFalse(json.readTree(frames.get(2).data()).has("threadId"));
        assertFalse(json.readTree(frames.get(2).data()).has("runId"));
    }
}
