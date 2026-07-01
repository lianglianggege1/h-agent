package com.h.backend.voice.application;

import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.infrastructure.config.VoiceTtsProperties;
import com.h.backend.voice.interfaces.dto.VoiceResourceResponse;
import com.h.backend.voice.infrastructure.tts.MiniMaxTtsClient;
import com.h.backend.voice.infrastructure.tts.MiniMaxTtsRequest;
import com.h.backend.voice.infrastructure.tts.MiniMaxTtsResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class VoiceTtsService {

    private final VoiceTtsProperties properties;
    private final MiniMaxTtsClient ttsClient;
    private final ResourceStorage resourceStorage;
    private final ChatSessionService chatSessionService;

    public VoiceTtsService(
            VoiceTtsProperties properties,
            MiniMaxTtsClient ttsClient,
            ResourceStorage resourceStorage,
            ChatSessionService chatSessionService
    ) {
        this.properties = properties;
        this.ttsClient = ttsClient;
        this.resourceStorage = resourceStorage;
        this.chatSessionService = chatSessionService;
    }

    public PreviewAudio preview(Long userId, String sessionId, String agentId, String text) {
        String normalizedText = validateText(text, properties.getPreviewMaxTextLength());
        chatSessionService.assertActiveAgentSession(userId, sessionId, agentId);
        MiniMaxTtsResult result = ttsClient.synthesize(new MiniMaxTtsRequest(normalizedText, null));
        return new PreviewAudio(result.audioBytes(), result.mimeType());
    }

    public VoiceResourceResponse messageTts(Long userId, String sessionId, String agentId, Long messageId) {
        chatSessionService.assertActiveAgentSession(userId, sessionId, agentId);
        ChatSessionMessageDto message = chatSessionService.getOwnedMessage(userId, sessionId, messageId);
        if (!"assistant".equalsIgnoreCase(message.role()) || !"AI".equalsIgnoreCase(message.messageType())) {
            throw new BusinessException(40000, "Assistant TTS 只能绑定 AI 回复消息");
        }
        String normalizedText = validateText(message.content(), properties.getMessageMaxTextLength());
        MiniMaxTtsResult result = ttsClient.synthesize(new MiniMaxTtsRequest(normalizedText, null));
        Map<String, Object> metadata = assistantTtsMetadata(result);
        StoredResource stored = resourceStorage.save(new ResourceSaveCommand(
                "AUDIO",
                sessionId,
                "call-assistant-tts",
                result.audioBytes(),
                result.mimeType(),
                extension(result.mimeType()),
                null,
                null
        ));
        ChatMessageResourceDto resource = chatSessionService.bindStoredAudioResource(
                userId,
                sessionId,
                messageId,
                "ASSISTANT_TTS",
                stored,
                metadata
        );
        return new VoiceResourceResponse(
                resource.id(),
                resource.viewUrl(),
                resource.downloadUrl(),
                resource.mimeType(),
                null
        );
    }

    private String validateText(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            throw new BusinessException(40000, "TTS 文本不能为空");
        }
        String normalized = text.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(40000, "TTS 文本长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private Map<String, Object> assistantTtsMetadata(MiniMaxTtsResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "ASSISTANT_TTS");
        putIfPresent(metadata, "voiceId", result.voiceId());
        putIfPresent(metadata, "model", result.model());
        return metadata;
    }

    private void putIfPresent(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private String extension(String mimeType) {
        if ("audio/wav".equalsIgnoreCase(mimeType)) {
            return "wav";
        }
        return "mp3";
    }

    public record PreviewAudio(byte[] audioBytes, String mimeType) {
    }
}
