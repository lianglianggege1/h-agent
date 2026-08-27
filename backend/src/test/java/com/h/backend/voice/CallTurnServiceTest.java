package com.h.backend.voice;

import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import com.h.backend.chat.infrastructure.storage.ResourceAttachment;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.application.CallTurnService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallTurnServiceTest {

    /** WebM EBML 头魔数（合并后签名校验用）：chunk0 以魔数开头。 */
    private static final byte[] WEBM_CHUNK_0 = {
            (byte) 0x1A, (byte) 0x45, (byte) 0xDF, (byte) 0xA3, 0x01, 0x02
    };
    private static final byte[] WEBM_CHUNK_1 = {0x03, 0x04, 0x05};

    @TempDir
    Path tempDir;

    @Test
    void finalizesChunksAndBindsUserRecordingResource() throws Exception {
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());

        StoredResource stored = new StoredResource(
                "audio-1",
                "OBJECT_STORAGE",
                "call-audio/audio-1.webm",
                "audio/webm",
                "call.webm",
                6L,
                null,
                null
        );
        // mock 边界（任务 3）：调用方测试 mock Coordinator，attachment 同步执行（byte[] 形态保留）。
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(stored);
                });
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
                new MockMultipartFile("chunk", "0.webm", "audio/webm", WEBM_CHUNK_0),
                0,
                "audio/webm"
        );
        service.appendChunk(
                1L,
                turnId,
                new MockMultipartFile("chunk", "1.webm", "audio/webm", WEBM_CHUNK_1),
                1,
                "audio/webm"
        );

        var response = service.finalizeTurn(1L, turnId, "session-1", "standard-chat", 101L, "你好");

        verify(chatSessionService, times(2)).assertActiveAgentSession(1L, "session-1", "standard-chat");
        ArgumentCaptor<ResourceSaveCommand> saveCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(writeCoordinator).saveAndAttach(saveCaptor.capture(), any());
        ResourceSaveCommand command = saveCaptor.getValue();
        assertEquals("AUDIO", command.resourceType());
        assertArrayEquals(
                new byte[]{(byte) 0x1A, (byte) 0x45, (byte) 0xDF, (byte) 0xA3, 0x01, 0x02, 0x03, 0x04, 0x05},
                command.content());
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
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
        StoredResource stored = new StoredResource(
                "audio-1",
                "OBJECT_STORAGE",
                "call-audio/audio-1.webm",
                "audio/webm",
                "call.webm",
                3L,
                null,
                null
        );
        // mock 边界（任务 3）：调用方测试 mock Coordinator，attachment 同步执行（byte[] 形态保留）。
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(stored);
                });
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
                new MockMultipartFile("chunk", "0.webm", "audio/webm", WEBM_CHUNK_0),
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
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
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
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());

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
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
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
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
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
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
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
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
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
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
        String upperCaseTurnId = UUID.randomUUID().toString().toUpperCase();

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.cancel(1L, upperCaseTurnId)
        );

        assertEquals(40000, ex.getCode());
    }

    @Test
    void finalizeRejectsNoChunks() {
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
        String turnId = service.start(1L, "session-1", "standard-chat");

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.finalizeTurn(1L, turnId, "session-1", "standard-chat", 101L, "你好")
        );

        assertEquals(40000, ex.getCode());
    }

    @Test
    void finalizeRejectsMismatchedSessionFromStartedTurn() {
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
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
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
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
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());
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

    @Test
    void finalizeTurnRejectsMergedAudioWhenSignatureDoesNotMatch() throws Exception {
        // 审查修复第 3 项：浏览器上传分片是用户输入，合并后的字节必须通过
        // audio/webm 签名校验——拼出 HTML 主动内容则拒绝保存，不进入写入路径；
        // 「签名符合正常保存」由 finalizesChunksAndBindsUserRecordingResource
        // （WebM EBML 魔数分片）覆盖。
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        CallTurnService service = new CallTurnService(tempDir, writeCoordinator, chatSessionService, new ResourceContentInspector(), new ResourceContentPolicy());

        String turnId = service.start(1L, "session-1", "standard-chat");
        service.appendChunk(
                1L,
                turnId,
                new MockMultipartFile("chunk", "0.webm", "audio/webm",
                        "<html><script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                0,
                "audio/webm"
        );

        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> service.finalizeTurn(1L, turnId, "session-1", "standard-chat", 101L, "你好")
        );

        assertEquals("音频内容未通过校验，已拒绝保存", ex.getMessage());
        verify(writeCoordinator, never()).saveAndAttach(any(ResourceSaveCommand.class), any());
    }
}
