package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.application.agui.AguiChatRuntime;
import com.h.backend.chat.infrastructure.config.ChatStreamProperties;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import com.h.backend.chat.application.agui.AguiEventJson;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.util.JsonUtils;
import com.h.backend.common.exception.BusinessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/chat/agui")
public class AguiChatController {
    private final AguiChatRuntime runtime;
    private final ChatStreamProperties properties;
    private final AguiEventJson encoder = new AguiEventJson();

    public AguiChatController(AguiChatRuntime runtime, ChatStreamProperties properties) {
        this.runtime = runtime;
        this.properties = properties;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> run(@AuthenticationPrincipal AuthUserPrincipal principal,
                                          @RequestBody String requestBody) {
        final RunAgentInput input;
        try {
            // AgentScope owns its Jackson 2 content codec; Boot 4's Jackson 3 does not read
            // the SDK's MessageContent deserializer annotations.
            input = JsonUtils.getJsonCodec().fromJson(requestBody, RunAgentInput.class);
            if (input == null) throw new IllegalArgumentException("Missing AG-UI input");
        } catch (RuntimeException ex) {
            throw new BusinessException(40000, "AG-UI 请求格式无效");
        }
        record Frame(ServerSentEvent<String> event, boolean terminal) { }
        Flux<Frame> events = runtime.run(principal.userId(), input).map(event -> new Frame(
                ServerSentEvent.<String>builder().data(encoder.encode(event)).build(),
                event instanceof AguiEvent.RunFinished || event instanceof AguiEvent.RunError));
        Flux<Frame> heartbeats = Flux.interval(properties.getHeartbeatInterval()).map(tick -> new Frame(
                ServerSentEvent.<String>builder().comment("keepalive").build(), false));
        Flux<ServerSentEvent<String>> body = Flux.concat(Flux.just(ServerSentEvent.<String>builder().comment("keepalive").build()),
                Flux.merge(events, heartbeats).takeUntil(Frame::terminal).map(Frame::event));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache().cachePrivate().noTransform())
                .header("X-Accel-Buffering", "no").body(body);
    }

    @GetMapping("/threads/{threadId}")
    public ApiResponse<Map<String, Object>> thread(@AuthenticationPrincipal AuthUserPrincipal principal,
                                                  @PathVariable String threadId) {
        return ApiResponse.ok(runtime.thread(principal.userId(), threadId));
    }

    public record StopRequest(String runId) { }

    @PostMapping("/threads/{threadId}/stop")
    public ApiResponse<Void> stop(@AuthenticationPrincipal AuthUserPrincipal principal,
                                 @PathVariable String threadId,
                                 @RequestBody(required = false) StopRequest request) {
        runtime.stop(principal.userId(), threadId, request == null ? null : request.runId());
        return ApiResponse.ok(null);
    }
}
