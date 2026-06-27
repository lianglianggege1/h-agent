package com.h.backend.voice.service;

import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.dto.VoiceResourceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class CallTurnService {

    private final Path baseDir;
    private final ResourceStorage resourceStorage;
    private final ChatSessionService chatSessionService;

    @Autowired
    public CallTurnService(
            @Value("${voice.call-turns.base-dir:/tmp/h-agent/call-turns}") String baseDir,
            ResourceStorage resourceStorage,
            ChatSessionService chatSessionService
    ) {
        this(Path.of(baseDir), resourceStorage, chatSessionService);
    }

    public CallTurnService(Path baseDir, ResourceStorage resourceStorage, ChatSessionService chatSessionService) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.resourceStorage = resourceStorage;
        this.chatSessionService = chatSessionService;
    }

    public String start(Long userId, String sessionId, String agentId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(40000, "会话不能为空");
        }
        String turnId = UUID.randomUUID().toString();
        Path dir = turnDir(userId, turnId);
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to create call turn directory", ex);
        }
        return turnId;
    }

    public void appendChunk(Long userId, String turnId, MultipartFile chunk, int sequence, String mimeType) {
        if (sequence < 0) {
            throw new BusinessException(40000, "音频分片序号无效");
        }
        Path dir = existingTurnDir(userId, turnId);
        String extension = extensionFor(mimeType);
        Path target = dir.resolve("chunk-%06d.%s".formatted(sequence, extension)).normalize();
        if (!target.startsWith(dir)) {
            throw new BusinessException(40000, "turnId 无效");
        }
        try {
            Files.write(target, chunk.getBytes());
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write call turn chunk", ex);
        }
    }

    public VoiceResourceResponse finalizeTurn(
            Long userId,
            String turnId,
            String sessionId,
            String agentId,
            Long messageId,
            String transcript
    ) {
        Path dir = existingTurnDir(userId, turnId);
        try {
            byte[] audio = mergeChunks(dir);
            StoredResource stored = resourceStorage.save(new ResourceSaveCommand(
                    "AUDIO",
                    sessionId,
                    "call-user-recording",
                    audio,
                    "audio/webm",
                    "webm",
                    null,
                    null
            ));
            ChatMessageResourceDto resource = chatSessionService.bindStoredAudioResource(
                    userId,
                    sessionId,
                    messageId,
                    "USER_RECORDING",
                    stored,
                    Map.of(
                            "source", "USER_RECORDING",
                            "callTurnId", turnId,
                            "transcript", transcript == null ? "" : transcript
                    )
            );
            return new VoiceResourceResponse(
                    resource.id(),
                    resource.viewUrl(),
                    resource.downloadUrl(),
                    resource.mimeType(),
                    null
            );
        } finally {
            deleteDirectory(dir);
        }
    }

    public void cancel(Long userId, String turnId) {
        deleteDirectory(turnDir(userId, turnId));
    }

    private Path existingTurnDir(Long userId, String turnId) {
        Path dir = turnDir(userId, turnId);
        if (!Files.isDirectory(dir)) {
            throw new BusinessException(40404, "通话片段不存在");
        }
        return dir;
    }

    private Path turnDir(Long userId, String turnId) {
        if (turnId == null || turnId.isBlank()) {
            throw new BusinessException(40000, "turnId 无效");
        }
        Path dir = baseDir.resolve(String.valueOf(userId)).resolve(turnId).normalize();
        if (!dir.startsWith(baseDir)) {
            throw new BusinessException(40000, "turnId 无效");
        }
        return dir;
    }

    private byte[] mergeChunks(Path dir) {
        List<Path> chunks;
        try (Stream<Path> stream = Files.list(dir)) {
            chunks = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to list call turn chunks", ex);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Path chunk : chunks) {
            try {
                output.write(Files.readAllBytes(chunk));
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to read call turn chunk", ex);
            }
        }
        return output.toByteArray();
    }

    private String extensionFor(String mimeType) {
        if ("audio/mpeg".equalsIgnoreCase(mimeType)) {
            return "mp3";
        }
        if ("audio/wav".equalsIgnoreCase(mimeType)) {
            return "wav";
        }
        return "webm";
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException("Failed to delete call turn directory", ex);
                }
            });
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to delete call turn directory", ex);
        }
    }
}
