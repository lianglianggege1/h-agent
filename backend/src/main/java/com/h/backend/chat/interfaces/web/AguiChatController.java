package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.application.agui.AguiChatRuntime;
import com.h.backend.chat.infrastructure.config.ChatStreamProperties;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import com.agui.community.core.agent.RunAgentInput;
import com.agui.community.core.event.RunFinishedEvent;
import com.agui.community.core.event.RunErrorEvent;
import com.agui.community.core.serialization.Serializer;
import com.agui.community.server.SseEventEncoder;
import com.agui.community.server.OutputStreamEventSink;
import com.h.backend.common.exception.BusinessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/api/chat/agui")
public class AguiChatController {
    private final AguiChatRuntime runtime;
    private final ChatStreamProperties properties;
    private final Serializer serializer;
    private final SseEventEncoder encoder;

    public AguiChatController(AguiChatRuntime runtime, ChatStreamProperties properties, Serializer serializer) {
        this.runtime = runtime;
        this.properties = properties;
        this.serializer = serializer;
        this.encoder = new SseEventEncoder(serializer);
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> run(@AuthenticationPrincipal AuthUserPrincipal principal,
                                          @RequestBody String requestBody) {
        final RunAgentInput input;
        try {
            input = serializer.deserialize(requestBody, RunAgentInput.class);
            if (input == null) throw new IllegalArgumentException("Missing AG-UI input");
        } catch (RuntimeException ex) {
            throw new BusinessException(40000, "AG-UI 请求格式无效");
        }
        record Frame(String encoded, boolean terminal) { }
        Flux<Frame> events = runtime.run(principal.userId(), input).map(event -> new Frame(
                encoder.encode(event), event instanceof RunFinishedEvent || event instanceof RunErrorEvent));
        Flux<Frame> heartbeats = Flux.interval(properties.getHeartbeatInterval()).map(tick -> new Frame(
                ": keepalive\n\n", false));
        Flux<String> frames = Flux.concat(Flux.just(": keepalive\n\n"),
                Flux.merge(events, heartbeats).takeUntil(Frame::terminal).map(Frame::encoded));
        // MVC's virtual-thread executor writes the SDK frames directly, without an SseEmitter wrapping them.
        StreamingResponseBody body = output -> {
            var sink = new OutputStreamEventSink(output);
            try (var stream = frames.toStream(1)) {
                var iterator = stream.iterator();
                while (iterator.hasNext()) sink.write(iterator.next());
            } finally {
                sink.close();
            }
        };
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
