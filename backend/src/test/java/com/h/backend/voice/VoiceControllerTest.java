package com.h.backend.voice;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.security.AuthUserPrincipal;
import com.h.backend.voice.controller.VoiceController;
import com.h.backend.voice.dto.CallTurnFinalizeRequest;
import com.h.backend.voice.dto.CallTurnStartRequest;
import com.h.backend.voice.dto.CallTurnStartResponse;
import com.h.backend.voice.dto.TtsPreviewRequest;
import com.h.backend.voice.dto.VoiceResourceResponse;
import com.h.backend.voice.service.CallTurnService;
import com.h.backend.voice.service.VoiceTtsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceControllerTest {

    @Test
    void previewReturnsAudioResponse() {
        CallTurnService callTurnService = mock(CallTurnService.class);
        VoiceTtsService ttsService = mock(VoiceTtsService.class);
        VoiceController controller = new VoiceController(callTurnService, ttsService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(ttsService.preview(1L, "session-1", "standard-chat", "你好"))
                .thenReturn(new VoiceTtsService.PreviewAudio(new byte[]{1, 2, 3}, "audio/mpeg"));

        var response = controller.preview(principal, new TtsPreviewRequest("session-1", "standard-chat", "你好"));

        assertEquals(MediaType.parseMediaType("audio/mpeg"), response.getHeaders().getContentType());
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
    }

    @Test
    void delegatesCallTurnLifecycle() {
        CallTurnService callTurnService = mock(CallTurnService.class);
        VoiceTtsService ttsService = mock(VoiceTtsService.class);
        VoiceController controller = new VoiceController(callTurnService, ttsService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        MockMultipartFile chunk = new MockMultipartFile("chunk", "0.webm", "audio/webm", new byte[]{1, 2, 3});
        VoiceResourceResponse resource = new VoiceResourceResponse(
                "audio-1",
                "/api/chat/resources/audio-1/content",
                "/api/chat/resources/audio-1/download",
                "audio/webm",
                null
        );
        when(callTurnService.start(1L, "session-1", "standard-chat")).thenReturn("turn-1");
        when(callTurnService.finalizeTurn(1L, "turn-1", "session-1", "standard-chat", 101L, "你好"))
                .thenReturn(resource);

        ApiResponse<CallTurnStartResponse> start = controller.startCallTurn(
                principal,
                new CallTurnStartRequest("session-1", "standard-chat")
        );
        ApiResponse<Void> upload = controller.uploadChunk(principal, "turn-1", chunk, 0, "audio/webm");
        ApiResponse<VoiceResourceResponse> finalized = controller.finalizeCallTurn(
                principal,
                "turn-1",
                new CallTurnFinalizeRequest("session-1", "standard-chat", 101L, "你好")
        );
        ApiResponse<Void> cancel = controller.cancelCallTurn(principal, "turn-1");

        assertNotNull(start.data());
        assertEquals("turn-1", start.data().turnId());
        assertEquals(0, upload.code());
        assertEquals(resource, finalized.data());
        assertEquals(0, cancel.code());
        verify(callTurnService).appendChunk(1L, "turn-1", chunk, 0, "audio/webm");
        verify(callTurnService).cancel(1L, "turn-1");
    }
}
