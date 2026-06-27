package com.h.backend.voice;

import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import com.h.backend.voice.service.CallTurnService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallTurnServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void finalizesChunksAndBindsUserRecordingResource() throws Exception {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);

        StoredResource stored = new StoredResource(
                "audio-1",
                "LOCAL_FILE",
                "call-audio/audio-1.webm",
                "audio/webm",
                "call.webm",
                6L,
                null,
                null
        );
        when(storage.save(any(ResourceSaveCommand.class))).thenReturn(stored);
        when(chatSessionService.bindStoredAudioResource(
                eq(1L),
                eq("session-1"),
                eq(101L),
                eq("USER_RECORDING"),
                eq(stored),
                any()
        )).thenReturn(new ChatMessageResourceDto(
                "audio-1",
                "AUDIO",
                "ATTACHMENT",
                "/api/chat/resources/audio-1/content",
                "/api/chat/resources/audio-1/download",
                "call.webm",
                "audio/webm",
                6L,
                null,
                null
        ));

        String turnId = service.start(1L, "session-1", "standard-chat");
        service.appendChunk(
                1L,
                turnId,
                new MockMultipartFile("chunk", "0.webm", "audio/webm", new byte[]{1, 2, 3}),
                0,
                "audio/webm"
        );
        service.appendChunk(
                1L,
                turnId,
                new MockMultipartFile("chunk", "1.webm", "audio/webm", new byte[]{4, 5, 6}),
                1,
                "audio/webm"
        );

        var response = service.finalizeTurn(1L, turnId, "session-1", "standard-chat", 101L, "你好");

        ArgumentCaptor<ResourceSaveCommand> saveCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(storage).save(saveCaptor.capture());
        ResourceSaveCommand command = saveCaptor.getValue();
        assertEquals("AUDIO", command.resourceType());
        assertEquals("session-1", command.sessionId());
        assertEquals("call-user-recording", command.prompt());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, command.content());
        assertEquals("audio/webm", command.mimeType());
        assertEquals("webm", command.extension());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(chatSessionService).bindStoredAudioResource(
                eq(1L),
                eq("session-1"),
                eq(101L),
                eq("USER_RECORDING"),
                eq(stored),
                metadataCaptor.capture()
        );
        assertEquals(Map.of("source", "USER_RECORDING", "callTurnId", turnId, "transcript", "你好"), metadataCaptor.getValue());
        assertEquals("audio-1", response.resourceId());
        assertFalse(Files.exists(tempDir.resolve("1").resolve(turnId)));
    }
}
