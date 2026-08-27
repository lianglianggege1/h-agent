package com.h.backend.voice;

import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.infrastructure.storage.ResourceAttachment;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.infrastructure.config.VoiceTtsProperties;
import com.h.backend.voice.application.VoiceTtsService;
import com.h.backend.voice.infrastructure.tts.MiniMaxTtsClient;
import com.h.backend.voice.infrastructure.tts.MiniMaxTtsRequest;
import com.h.backend.voice.infrastructure.tts.MiniMaxTtsResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VoiceTtsServiceTest {

    @Test
    void previewReturnsAudioBytesWithoutPersisting() {
        MiniMaxTtsClient client = mock(MiniMaxTtsClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        VoiceTtsService service = new VoiceTtsService(new VoiceTtsProperties(), client, writeCoordinator, chatSessionService);
        when(client.synthesize(new MiniMaxTtsRequest("你好", null)))
                .thenReturn(new MiniMaxTtsResult(new byte[]{1, 2, 3}, "audio/mpeg", "trace-1", null, null));

        VoiceTtsService.PreviewAudio audio = service.preview(1L, "session-1", "standard-chat", "你好");

        assertArrayEquals(new byte[]{1, 2, 3}, audio.audioBytes());
        assertEquals("audio/mpeg", audio.mimeType());
        verify(chatSessionService).assertActiveAgentSession(1L, "session-1", "standard-chat");
        verify(writeCoordinator, never()).saveAndAttach(any(ResourceSaveCommand.class), any());
    }

    @Test
    void previewValidatesActiveSessionBeforeCallingProvider() {
        MiniMaxTtsClient client = mock(MiniMaxTtsClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        VoiceTtsService service = new VoiceTtsService(new VoiceTtsProperties(), client, writeCoordinator, chatSessionService);
        doThrow(new BusinessException(40404, "会话不存在")).when(chatSessionService)
                .assertActiveAgentSession(1L, "session-1", "standard-chat");

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.preview(1L, "session-1", "standard-chat", "你好")
        );

        assertEquals(40404, error.getCode());
        verify(chatSessionService).assertActiveAgentSession(1L, "session-1", "standard-chat");
        verify(client, never()).synthesize(any(MiniMaxTtsRequest.class));
    }

    @Test
    void messageTtsReadsAssistantMessageAndBindsAudioResource() {
        MiniMaxTtsClient client = mock(MiniMaxTtsClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        VoiceTtsService service = new VoiceTtsService(new VoiceTtsProperties(), client, writeCoordinator, chatSessionService);
        ChatSessionMessageDto message = new ChatSessionMessageDto(
                "101",
                "assistant",
                "AI",
                "完整回复",
                null,
                List.of(),
                LocalDateTime.now()
        );
        StoredResource stored = new StoredResource(
                "audio-1",
                "LOCAL_FILE",
                "call-audio/audio-1.mp3",
                "audio/mpeg",
                "audio-1.mp3",
                3L,
                null,
                null
        );
        when(chatSessionService.getOwnedMessage(1L, "session-1", 101L)).thenReturn(message);
        when(client.synthesize(new MiniMaxTtsRequest("完整回复", null)))
                .thenReturn(new MiniMaxTtsResult(
                        new byte[]{1, 2, 3},
                        "audio/mpeg",
                        "trace-1",
                        "speech-2.8-turbo",
                        "voice-1"
                ));
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
                eq("ASSISTANT_TTS"),
                eq(stored),
                any()
        )).thenReturn(new ChatMessageResourceDto(
                "audio-1",
                "AUDIO",
                "GENERATED",
                "/api/chat/resources/audio-1/content",
                "/api/chat/resources/audio-1/download",
                "audio-1.mp3",
                "audio/mpeg",
                3L,
                null,
                null
        ));

        var response = service.messageTts(1L, "session-1", "standard-chat", 101L);

        ArgumentCaptor<ResourceSaveCommand> saveCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(writeCoordinator).saveAndAttach(saveCaptor.capture(), any());
        ResourceSaveCommand command = saveCaptor.getValue();
        assertEquals("AUDIO", command.resourceType());
        assertArrayEquals(new byte[]{1, 2, 3}, command.content());
        assertEquals("audio/mpeg", command.mimeType());
        assertEquals("mp3", command.extension());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(chatSessionService).bindStoredAudioResource(
                eq(1L),
                eq("session-1"),
                eq(101L),
                eq("ASSISTANT_TTS"),
                eq(stored),
                metadataCaptor.capture()
        );
        assertEquals(
                Map.of("source", "ASSISTANT_TTS", "voiceId", "voice-1", "model", "speech-2.8-turbo"),
                metadataCaptor.getValue()
        );
        assertEquals("audio-1", response.resourceId());
    }

    @Test
    void messageTtsHandlesMissingProviderMetadata() {
        MiniMaxTtsClient client = mock(MiniMaxTtsClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        VoiceTtsService service = new VoiceTtsService(new VoiceTtsProperties(), client, writeCoordinator, chatSessionService);
        ChatSessionMessageDto message = new ChatSessionMessageDto(
                "101",
                "assistant",
                "AI",
                "完整回复",
                null,
                List.of(),
                LocalDateTime.now()
        );
        StoredResource stored = new StoredResource(
                "audio-1",
                "LOCAL_FILE",
                "call-audio/audio-1.mp3",
                "audio/mpeg",
                "audio-1.mp3",
                3L,
                null,
                null
        );
        when(chatSessionService.getOwnedMessage(1L, "session-1", 101L)).thenReturn(message);
        when(client.synthesize(new MiniMaxTtsRequest("完整回复", null)))
                .thenReturn(new MiniMaxTtsResult(new byte[]{1, 2, 3}, "audio/mpeg", "trace-1", null, null));
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(stored);
                });
        when(chatSessionService.bindStoredAudioResource(
                eq(1L),
                eq("session-1"),
                eq(101L),
                eq("ASSISTANT_TTS"),
                eq(stored),
                any()
        )).thenReturn(new ChatMessageResourceDto(
                "audio-1",
                "AUDIO",
                "GENERATED",
                "/api/chat/resources/audio-1/content",
                "/api/chat/resources/audio-1/download",
                "audio-1.mp3",
                "audio/mpeg",
                3L,
                null,
                null
        ));

        var response = service.messageTts(1L, "session-1", "standard-chat", 101L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(chatSessionService).bindStoredAudioResource(
                eq(1L),
                eq("session-1"),
                eq(101L),
                eq("ASSISTANT_TTS"),
                eq(stored),
                metadataCaptor.capture()
        );
        assertEquals(Map.of("source", "ASSISTANT_TTS"), metadataCaptor.getValue());
        assertEquals("audio-1", response.resourceId());
    }

    @Test
    void messageTtsRejectsNonAssistantAiMessage() {
        MiniMaxTtsClient client = mock(MiniMaxTtsClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        VoiceTtsService service = new VoiceTtsService(new VoiceTtsProperties(), client, writeCoordinator, chatSessionService);
        when(chatSessionService.getOwnedMessage(1L, "session-1", 101L)).thenReturn(new ChatSessionMessageDto(
                "101",
                "user",
                "USER",
                "用户消息",
                null,
                List.of(),
                LocalDateTime.now()
        ));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.messageTts(1L, "session-1", "standard-chat", 101L)
        );

        assertEquals(40000, error.getCode());
        assertEquals("Assistant TTS 只能绑定 AI 回复消息", error.getMessage());
        verify(client, never()).synthesize(any(MiniMaxTtsRequest.class));
        verify(writeCoordinator, never()).saveAndAttach(any(ResourceSaveCommand.class), any());
    }

    @Test
    void messageTtsPropagatesAttachmentFailureForCompensation() {
        MiniMaxTtsClient client = mock(MiniMaxTtsClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        VoiceTtsService service = new VoiceTtsService(new VoiceTtsProperties(), client, writeCoordinator, chatSessionService);
        when(chatSessionService.getOwnedMessage(1L, "session-1", 101L)).thenReturn(new ChatSessionMessageDto(
                "101", "assistant", "AI", "完整回复", null, List.of(), LocalDateTime.now()
        ));
        when(client.synthesize(new MiniMaxTtsRequest("完整回复", null)))
                .thenReturn(new MiniMaxTtsResult(new byte[]{1, 2, 3}, "audio/mpeg", "trace-1", null, null));
        StoredResource stored = new StoredResource(
                "audio-1", "OBJECT_STORAGE", "key-1", "audio/mpeg", "audio-1.mp3", 3L, null, null);
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(stored);
                });
        IllegalStateException boom = new IllegalStateException("音频绑定失败");
        when(chatSessionService.bindStoredAudioResource(any(), any(), any(), any(), any(), any())).thenThrow(boom);

        // 挂接失败必须原样上抛（Coordinator 事务 rollback 补偿对象），不得被吞。
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> service.messageTts(1L, "session-1", "standard-chat", 101L)
        );

        assertEquals(boom, thrown);
    }
}
