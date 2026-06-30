package com.h.backend.voice;

import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.service.CallTurnService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
        verify(chatSessionService).assertActiveAgentSession(1L, "session-1", "standard-chat");
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

        verify(chatSessionService, times(2)).assertActiveAgentSession(1L, "session-1", "standard-chat");
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

    @Test
    void finalizeKeepsTurnDirectoryWhenBindingResourceFails() throws Exception {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        StoredResource stored = new StoredResource(
                "audio-1",
                "LOCAL_FILE",
                "call-audio/audio-1.webm",
                "audio/webm",
                "call.webm",
                3L,
                null,
                null
        );
        when(storage.save(any(ResourceSaveCommand.class))).thenReturn(stored);
        doThrow(new IllegalStateException("bind failed")).when(chatSessionService).bindStoredAudioResource(
                eq(1L),
                eq("session-1"),
                eq(101L),
                eq("USER_RECORDING"),
                eq(stored),
                any()
        );

        String turnId = service.start(1L, "session-1", "standard-chat");
        service.appendChunk(
                1L,
                turnId,
                new MockMultipartFile("chunk", "0.webm", "audio/webm", new byte[]{1, 2, 3}),
                0,
                "audio/webm"
        );
        Path turnDir = tempDir.resolve("1").resolve(turnId);
        Path chunkFile = turnDir.resolve("chunk-000000.webm");

        assertThrows(
                IllegalStateException.class,
                () -> service.finalizeTurn(1L, turnId, "session-1", "standard-chat", 101L, "你好")
        );

        assertTrue(Files.isDirectory(turnDir));
        assertTrue(Files.exists(chunkFile));
    }

    @Test
    void cancelRejectsTurnIdEscapingUserDirectory() throws Exception {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        String userTwoTurnId = UUID.randomUUID().toString();
        Path userTwoDir = tempDir.resolve("2").resolve(userTwoTurnId);
        Files.createDirectories(userTwoDir);
        Path userTwoChunk = userTwoDir.resolve("chunk-000000.webm");
        Files.write(userTwoChunk, new byte[]{9});

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.cancel(1L, "../2/" + userTwoTurnId)
        );

        assertEquals(40000, ex.getCode());
        assertTrue(Files.isDirectory(userTwoDir));
        assertTrue(Files.exists(userTwoChunk));
    }

    @Test
    void appendRejectsDuplicateSequence() {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);

        String turnId = service.start(1L, "session-1", "standard-chat");
        service.appendChunk(
                1L,
                turnId,
                new MockMultipartFile("chunk", "0.webm", "audio/webm", new byte[]{1}),
                0,
                "audio/webm"
        );

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.appendChunk(
                        1L,
                        turnId,
                        new MockMultipartFile("chunk", "0-again.webm", "audio/webm", new byte[]{2}),
                        0,
                        "audio/webm"
                )
        );

        assertEquals(40000, ex.getCode());
    }

    @Test
    void appendRejectsSequenceOutsideChunkFilenameRange() {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        String turnId = service.start(1L, "session-1", "standard-chat");

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.appendChunk(
                        1L,
                        turnId,
                        new MockMultipartFile("chunk", "1000000.webm", "audio/webm", new byte[]{1}),
                        1_000_000,
                        "audio/webm"
                )
        );

        assertEquals(40000, ex.getCode());
    }

    @Test
    void appendRejectsEmptyChunk() {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        String turnId = service.start(1L, "session-1", "standard-chat");

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.appendChunk(
                        1L,
                        turnId,
                        new MockMultipartFile("chunk", "0.webm", "audio/webm", new byte[0]),
                        0,
                        "audio/webm"
                )
        );

        assertEquals(40000, ex.getCode());
    }

    @Test
    void appendRejectsUnsupportedAudioFormat() {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        String turnId = service.start(1L, "session-1", "standard-chat");

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.appendChunk(
                        1L,
                        turnId,
                        new MockMultipartFile("chunk", "0.mp3", "audio/mpeg", new byte[]{1}),
                        0,
                        "audio/mpeg"
                )
        );

        assertEquals(40000, ex.getCode());
    }

    @Test
    void appendAcceptsWebmAudioFormatWithCodecParameters() {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        String turnId = service.start(1L, "session-1", "standard-chat");

        service.appendChunk(
                1L,
                turnId,
                new MockMultipartFile("chunk", "0.webm", "audio/webm;codecs=opus", new byte[]{1}),
                0,
                "audio/webm;codecs=opus "
        );

        assertTrue(Files.exists(tempDir.resolve("1").resolve(turnId).resolve("chunk-000000.webm")));
    }

    @Test
    void cancelRejectsNonCanonicalUuidTurnId() {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        String upperCaseTurnId = UUID.randomUUID().toString().toUpperCase();

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.cancel(1L, upperCaseTurnId)
        );

        assertEquals(40000, ex.getCode());
    }

    @Test
    void finalizeRejectsNoChunks() {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        String turnId = service.start(1L, "session-1", "standard-chat");

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.finalizeTurn(1L, turnId, "session-1", "standard-chat", 101L, "你好")
        );

        assertEquals(40000, ex.getCode());
    }

    @Test
    void finalizeRejectsMismatchedSessionFromStartedTurn() {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        String turnId = service.start(1L, "session-1", "standard-chat");
        service.appendChunk(
                1L,
                turnId,
                new MockMultipartFile("chunk", "0.webm", "audio/webm", new byte[]{1}),
                0,
                "audio/webm"
        );

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.finalizeTurn(1L, turnId, "session-2", "standard-chat", 101L, "你好")
        );

        assertEquals(40000, ex.getCode());
    }

    @Test
    void appendRejectsTurnWithoutMetadata() throws Exception {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        String turnId = UUID.randomUUID().toString();
        Files.createDirectories(tempDir.resolve("1").resolve(turnId));

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.appendChunk(
                        1L,
                        turnId,
                        new MockMultipartFile("chunk", "0.webm", "audio/webm", new byte[]{1}),
                        0,
                        "audio/webm"
                )
        );

        assertEquals(40404, ex.getCode());
    }

    @Test
    void finalizeRejectsMissingSequence() {
        ResourceStorage storage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, storage, chatSessionService);
        String turnId = service.start(1L, "session-1", "standard-chat");
        service.appendChunk(
                1L,
                turnId,
                new MockMultipartFile("chunk", "0.webm", "audio/webm", new byte[]{1}),
                0,
                "audio/webm"
        );
        service.appendChunk(
                1L,
                turnId,
                new MockMultipartFile("chunk", "2.webm", "audio/webm", new byte[]{3}),
                2,
                "audio/webm"
        );

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.finalizeTurn(1L, turnId, "session-1", "standard-chat", 101L, "你好")
        );

        assertEquals(40000, ex.getCode());
    }
}
