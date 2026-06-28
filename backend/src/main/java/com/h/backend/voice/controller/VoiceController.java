package com.h.backend.voice.controller;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.security.AuthUserPrincipal;
import com.h.backend.voice.dto.CallTurnFinalizeRequest;
import com.h.backend.voice.dto.CallTurnStartRequest;
import com.h.backend.voice.dto.CallTurnStartResponse;
import com.h.backend.voice.dto.TtsMessageRequest;
import com.h.backend.voice.dto.TtsPreviewRequest;
import com.h.backend.voice.dto.VoiceResourceResponse;
import com.h.backend.voice.service.CallTurnService;
import com.h.backend.voice.service.VoiceTtsService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final CallTurnService callTurnService;
    private final VoiceTtsService ttsService;

    public VoiceController(CallTurnService callTurnService, VoiceTtsService ttsService) {
        this.callTurnService = callTurnService;
        this.ttsService = ttsService;
    }

    @PostMapping("/call-turns/start")
    public ApiResponse<CallTurnStartResponse> startCallTurn(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody CallTurnStartRequest request
    ) {
        String turnId = callTurnService.start(principal.userId(), request.sessionId(), request.agentId());
        return ApiResponse.ok(new CallTurnStartResponse(turnId));
    }

    @PostMapping(value = "/call-turns/{turnId}/chunks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Void> uploadChunk(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String turnId,
            @RequestParam("chunk") MultipartFile chunk,
            @RequestParam("sequence") int sequence,
            @RequestParam(value = "mimeType", required = false) String mimeType
    ) {
        callTurnService.appendChunk(principal.userId(), turnId, chunk, sequence, mimeType);
        return ApiResponse.ok(null);
    }

    @PostMapping("/call-turns/{turnId}/finalize")
    public ApiResponse<VoiceResourceResponse> finalizeCallTurn(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String turnId,
            @RequestBody CallTurnFinalizeRequest request
    ) {
        return ApiResponse.ok(callTurnService.finalizeTurn(
                principal.userId(),
                turnId,
                request.sessionId(),
                request.agentId(),
                request.messageId(),
                request.transcript()
        ));
    }

    @PostMapping("/call-turns/{turnId}/cancel")
    public ApiResponse<Void> cancelCallTurn(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String turnId
    ) {
        callTurnService.cancel(principal.userId(), turnId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/tts/preview")
    public ResponseEntity<byte[]> preview(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody TtsPreviewRequest request
    ) {
        VoiceTtsService.PreviewAudio audio = ttsService.preview(
                principal.userId(),
                request.sessionId(),
                request.agentId(),
                request.text()
        );
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(audio.mimeType()))
                .body(audio.audioBytes());
    }

    @PostMapping("/tts/message")
    public ApiResponse<VoiceResourceResponse> messageTts(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestBody TtsMessageRequest request
    ) {
        return ApiResponse.ok(ttsService.messageTts(
                principal.userId(),
                request.sessionId(),
                request.agentId(),
                request.messageId()
        ));
    }
}
