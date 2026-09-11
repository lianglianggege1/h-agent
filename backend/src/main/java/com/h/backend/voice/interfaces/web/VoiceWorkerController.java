package com.h.backend.voice.interfaces.web;

import com.h.backend.voice.application.*;
import com.h.backend.voice.infrastructure.LiveKitGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.Map;

@RestController
public class VoiceWorkerController {
    private final VoiceCallModule calls; private final VoiceTurnModule turns; private final LiveKitGateway livekit;
    public VoiceWorkerController(VoiceCallModule calls,VoiceTurnModule turns,LiveKitGateway livekit){this.calls=calls;this.turns=turns;this.livekit=livekit;}
    public record Claim(@NotBlank @Size(max=128) String roomName,@NotBlank @Size(max=64) String claimSecret,@NotBlank @Size(max=128) String workerId){}
    public record Heartbeat(boolean ready){}
    public record Submit(@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String turnId,@NotBlank @Size(max=8000) String text){}
    public record Playout(@NotBlank String utteranceId,@Positive long revision,@NotBlank String status,@Min(0) int playedChars,@NotBlank String confidence,boolean last){}
    @PostMapping("/internal/voice/calls/{id}/claim") public Map<String,Object> claim(@PathVariable String id,@Valid @RequestBody Claim r){return calls.claim(id,r.roomName(),r.claimSecret(),r.workerId());}
    @PostMapping("/internal/voice/calls/{id}/heartbeat") public Map<String,Object> heartbeat(@PathVariable String id,@RequestHeader("X-Voice-Worker-Epoch") long epoch,@RequestBody Heartbeat r){return calls.heartbeat(id,epoch,r.ready());}
    @PostMapping("/internal/voice/calls/{id}/turns") public Map<String,Object> submit(@PathVariable String id,@RequestHeader("X-Voice-Worker-Epoch") long epoch,@Valid @RequestBody Submit r){return turns.submit(id,epoch,r.turnId(),r.text());}
    @GetMapping("/internal/voice/calls/{id}/turns/{turnId}") public Map<String,Object> turn(@PathVariable String id,@PathVariable String turnId,@RequestHeader("X-Voice-Worker-Epoch") long epoch){return turns.get(id,epoch,turnId);}
    @GetMapping(value="/internal/voice/calls/{id}/turns/{turnId}/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<VoiceTurnModule.Event>> stream(@PathVariable String id,@PathVariable String turnId,@RequestHeader("X-Voice-Worker-Epoch") long epoch){
        return turns.stream(id,epoch,turnId).map(e->ServerSentEvent.<VoiceTurnModule.Event>builder(e).event(e.type()).build())
                .mergeWith(Flux.interval(Duration.ofSeconds(10)).map(i->ServerSentEvent.<VoiceTurnModule.Event>builder().comment("keepalive").build()))
                .takeUntil(e->"generation_end".equals(e.event()));
    }
    @PostMapping("/internal/voice/calls/{id}/turns/{turnId}/interrupt") public Map<String,Object> interrupt(@PathVariable String id,@PathVariable String turnId,@RequestHeader("X-Voice-Worker-Epoch") long epoch){return turns.interrupt(id,epoch,turnId);}
    @PutMapping("/internal/voice/calls/{id}/turns/{turnId}/playout") public Map<String,Object> playout(@PathVariable String id,@PathVariable String turnId,@RequestHeader("X-Voice-Worker-Epoch") long epoch,@Valid @RequestBody Playout r){return turns.playout(id,epoch,turnId,r.utteranceId(),r.revision(),r.status(),r.playedChars(),r.confidence(),r.last());}
    @PostMapping("/internal/voice/calls/{id}/end") public Map<String,String> end(@PathVariable String id,@RequestHeader("X-Voice-Worker-Epoch") long epoch){
        // Validate even shutdown requests: expired workers cannot terminate a later owner.
        turns.getCallWorker(id,epoch);calls.end(id,"WORKER_ENDED");return Map.of("state","ENDING");
    }
    @PostMapping("/internal/livekit/webhook") public void webhook(@RequestHeader("Authorization") String token,@RequestBody String body){calls.webhook(livekit.verifyWebhook(token,body));}
}
