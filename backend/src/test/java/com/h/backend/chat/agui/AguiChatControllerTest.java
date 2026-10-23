package com.h.backend.chat.agui;

import com.h.backend.chat.application.agui.AguiChatRuntime;
import com.h.backend.chat.infrastructure.config.ChatStreamProperties;
import com.h.backend.chat.infrastructure.config.AguiSdkConfig;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.web.AguiChatController;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import com.agui.community.core.event.*;
import com.agui.community.core.message.Role;
import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.spring.server.core.JacksonSerializer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AguiChatControllerTest {
    @Test
    void disconnectedMvcWriterDetachesItsSubscriptionWithoutStoppingTheBusinessRun() {
        var runtime = mock(AguiChatRuntime.class);
        var detached = new AtomicBoolean();
        when(runtime.run(eq(1L), any())).thenReturn(Flux.concat(
                Flux.just(new RunStartedEvent("thread", "run")), Flux.<Event>never()).doOnCancel(() -> detached.set(true)));
        var controller = new AguiChatController(runtime, new ChatStreamProperties(), new AguiSdkConfig().aguiSerializer());
        var response = controller.run(new AuthUserPrincipal(1L, "fixture@example.com", "USER"),
                "{\"threadId\":\"thread\",\"runId\":\"run\",\"messages\":[]}");
        assertThrows(IOException.class, () -> response.getBody().writeTo(new OutputStream() {
            int writes;
            @Override public void write(int value) { }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                if (++writes == 2) throw new IOException("client disconnected");
            }
        }));
        assertTrue(detached.get());
        verify(runtime, never()).stop(anyLong(), anyString(), any());
    }

    @Test
    void springMvcWritesCommunitySdkFramesWithoutAddingAnotherSseEnvelope() throws Exception {
        var runtime = mock(AguiChatRuntime.class);
        when(runtime.run(eq(1L), any())).thenReturn(Flux.just(new RunStartedEvent("thread", "run"),
                new RunFinishedEvent("thread", "run")));
        var controller = new AguiChatController(runtime, new ChatStreamProperties(), new AguiSdkConfig().aguiSerializer());
        var mvc = MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
            @Override public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == AuthUserPrincipal.class;
            }
            @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                                    NativeWebRequest request, WebDataBinderFactory factory) {
                return new AuthUserPrincipal(1L, "fixture@example.com", "USER");
            }
        }).build();
        var pending = mvc.perform(post("/api/chat/agui").contentType("application/json")
                .content("{\"threadId\":\"thread\",\"runId\":\"run\",\"messages\":[]}"))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream")).andReturn().getResponse();
        assertEquals("""
                : keepalive

                data: {"type":"RUN_STARTED","threadId":"thread","runId":"run"}

                data: {"type":"RUN_FINISHED","threadId":"thread","runId":"run"}

                """, response.getContentAsString());
    }

    @Test
    void acceptsSdkRunInputAndFramesSdkJsonExactlyOnce() throws Exception {
        String body = """
                {"threadId":"thread","runId":"run","messages":[{"id":"u","role":"user","content":"hello"}],
                 "tools":[],"context":[],"state":{},"forwardedProps":{}}
                """;
        var input = new JacksonSerializer().deserialize(body, RunAgentInput.class);
        assertEquals("thread", input.threadId());
        var runtime = mock(AguiChatRuntime.class);
        when(runtime.run(eq(1L), any(RunAgentInput.class))).thenReturn(Flux.just(new RunStartedEvent("thread", "run"),
                new TextMessageStartEvent("answer", Role.ASSISTANT),
                new TextMessageContentEvent("answer", "hello"), new TextMessageEndEvent("answer"),
                new ToolCallStartEvent("call", "web_search"), new ToolCallArgsEvent("call", "{}"),
                new ToolCallEndEvent("call"), new ToolCallResultEvent("result", "call", "sources"),
                new ActivitySnapshotEvent("resource", "h-agent.resource", Map.of("message",
                        new ChatSessionMessageDto("image", "assistant", "IMAGE", "", null, List.of(), LocalDateTime.of(2026, 10, 23, 12, 0)))),
                new RunFinishedEvent("thread", "run")));
        var controller = new AguiChatController(runtime, new ChatStreamProperties(), new AguiSdkConfig().aguiSerializer());
        var response = controller.run(new AuthUserPrincipal(1L, "test@example.com", "USER"), body);
        var output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        String wire = output.toString(StandardCharsets.UTF_8);
        var frames = List.of(wire.split("(?<=\n\n)"));
        assertEquals("no", response.getHeaders().getFirst("X-Accel-Buffering"));
        assertEquals(": keepalive\n\n", frames.getFirst());
        var json = JsonMapper.builder().build();
        assertTrue(frames.get(1).startsWith("data: {"));
        assertTrue(frames.get(1).endsWith("\n\n"));
        assertEquals("RUN_STARTED", json.readTree(frames.get(1).substring(6)).get("type").asString());
        assertEquals("RUN_FINISHED", json.readTree(frames.getLast().substring(6)).get("type").asString());
        assertFalse(frames.get(1).contains("data: data:"));
        assertFalse(json.readTree(frames.get(2).substring(6)).has("threadId"));
        assertFalse(json.readTree(frames.get(2).substring(6)).has("runId"));
        assertEquals("sources", json.readTree(frames.get(8).substring(6)).get("content").asString());
        assertTrue(frames.get(9).contains("2026-10-23T12:00:00"));
        String fixturePath = System.getProperty("agui.fixture.path");
        if (fixturePath != null) Files.writeString(Path.of(fixturePath), wire);
    }
}
