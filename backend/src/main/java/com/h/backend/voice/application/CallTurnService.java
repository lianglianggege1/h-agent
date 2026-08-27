package com.h.backend.voice.application;

import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceStorageErrorKind;
import com.h.backend.chat.infrastructure.storage.ResourceStorageException;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.interfaces.dto.VoiceResourceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class CallTurnService {

    private final Path baseDir;
    private final ResourceWriteCoordinator writeCoordinator;
    private final ChatSessionService chatSessionService;
    private final ResourceContentInspector contentInspector;
    private final ResourceContentPolicy contentPolicy;

    @Autowired
    public CallTurnService(
            @Value("${voice.call-turns.base-dir:/tmp/h-agent/call-turns}") String baseDir,
            ResourceWriteCoordinator writeCoordinator,
            ChatSessionService chatSessionService,
            ResourceContentInspector contentInspector,
            ResourceContentPolicy contentPolicy
    ) {
        this(Path.of(baseDir), writeCoordinator, chatSessionService, contentInspector, contentPolicy);
    }

    public CallTurnService(
            Path baseDir,
            ResourceWriteCoordinator writeCoordinator,
            ChatSessionService chatSessionService,
            ResourceContentInspector contentInspector,
            ResourceContentPolicy contentPolicy
    ) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.writeCoordinator = writeCoordinator;
        this.chatSessionService = chatSessionService;
        this.contentInspector = contentInspector;
        this.contentPolicy = contentPolicy;
    }

    public String start(Long userId, String sessionId, String agentId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(40000, "会话不能为空");
        }
        String resolvedAgentId = normalizeAgentId(agentId);
        chatSessionService.assertActiveAgentSession(userId, sessionId, resolvedAgentId);
        String turnId = UUID.randomUUID().toString();
        Path dir = turnDir(userId, turnId);
        try {
            Files.createDirectories(dir);
            writeMetadata(dir, sessionId, resolvedAgentId);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to create call turn directory", ex);
        }
        return turnId;
    }

    public void appendChunk(Long userId, String turnId, MultipartFile chunk, int sequence, String mimeType) {
        if (sequence < 0 || sequence > 999_999) {
            throw new BusinessException(40000, "音频分片序号无效");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new BusinessException(40000, "音频分片不能为空");
        }
        if (!isSupportedChunkMimeType(mimeType)) {
            throw new BusinessException(40000, "不支持的音频格式");
        }
        Path dir = existingTurnDir(userId, turnId);
        readMetadata(dir);
        Path target = dir.resolve("chunk-%06d.webm".formatted(sequence)).normalize();
        if (!target.startsWith(dir)) {
            throw new BusinessException(40000, "turnId 无效");
        }
        if (Files.exists(target)) {
            throw new BusinessException(40000, "音频分片重复");
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
        TurnMetadata metadata = readMetadata(dir);
        String resolvedAgentId = normalizeAgentId(agentId);
        if (!metadata.sessionId().equals(sessionId) || !metadata.agentId().equals(resolvedAgentId)) {
            throw new BusinessException(40000, "通话片段与会话不匹配");
        }
        chatSessionService.assertActiveAgentSession(userId, sessionId, resolvedAgentId);
        byte[] audio = mergeChunks(dir);
        // 审查修复第 3 项（计划 §6.3）：浏览器上传分片是用户输入，合并后的字节
        // 必须通过 audio/webm 签名校验（MIME 服务端硬编码）才能保存；
        // 校验失败时分片目录保留（与挂接失败语义一致，用户可重试或 cancel）。
        verifyMergedAudio(audio);
        // 新计划任务 3：写入经 Coordinator，音频绑定在挂接事务内（rollback 时对象被补偿）；
        // 挂接事务提交后才删除本地分片目录（失败时保留，与既有语义一致）。
        ChatMessageResourceDto resource = writeCoordinator.saveAndAttach(
                new ResourceSaveCommand(
                        "AUDIO",
                        audio,
                        "audio/webm",
                        "webm",
                        null,
                        null
                ),
                stored -> chatSessionService.bindStoredAudioResource(
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
                )
        );
        VoiceResourceResponse response = new VoiceResourceResponse(
                resource.id(),
                resource.viewUrl(),
                resource.downloadUrl(),
                resource.mimeType(),
                null
        );
        deleteDirectory(dir);
        return response;
    }

    /** 合并后用户录音的签名校验（审查修复第 3 项）：用户输入不豁免，冲突即拒绝保存。 */
    private void verifyMergedAudio(byte[] audio) {
        ResourceContentInspector.Inspection inspection;
        try {
            inspection = contentInspector.inspect(new ByteArrayInputStream(audio), "audio/webm");
        } catch (IOException exception) {
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.IO_ERROR, "通话音频读取失败", exception);
        }
        ResourceContentPolicy.SaveDecision decision =
                contentPolicy.validateForSave(inspection.result(), "audio/webm");
        if (!decision.allowed()) {
            throw new BusinessException(40000, "音频内容未通过校验，已拒绝保存");
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

    private void writeMetadata(Path dir, String sessionId, String agentId) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("sessionId", sessionId);
        properties.setProperty("agentId", agentId);
        try (var output = Files.newOutputStream(dir.resolve("metadata.properties"))) {
            properties.store(output, "call turn metadata");
        }
    }

    private TurnMetadata readMetadata(Path dir) {
        Path metadataFile = dir.resolve("metadata.properties");
        if (!Files.isRegularFile(metadataFile)) {
            throw new BusinessException(40404, "通话片段不存在");
        }
        Properties properties = new Properties();
        try (var input = Files.newInputStream(metadataFile)) {
            properties.load(input);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read call turn metadata", ex);
        }
        String sessionId = properties.getProperty("sessionId");
        String agentId = properties.getProperty("agentId");
        if (sessionId == null || sessionId.isBlank() || agentId == null || agentId.isBlank()) {
            throw new BusinessException(40404, "通话片段不存在");
        }
        return new TurnMetadata(sessionId, agentId);
    }

    private String normalizeAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? AgentRegistry.STANDARD_CHAT_AGENT_ID : agentId;
    }

    private boolean isSupportedChunkMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return true;
        }
        String mediaType = mimeType.split(";", 2)[0].trim();
        return "audio/webm".equalsIgnoreCase(mediaType);
    }

    private Path turnDir(Long userId, String turnId) {
        if (turnId == null || turnId.isBlank()) {
            throw new BusinessException(40000, "turnId 无效");
        }
        try {
            UUID parsed = UUID.fromString(turnId);
            if (!parsed.toString().equals(turnId)) {
                throw new BusinessException(40000, "turnId 无效");
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(40000, "turnId 无效");
        }
        Path userDir = baseDir.resolve(String.valueOf(userId)).normalize();
        Path dir = baseDir.resolve(String.valueOf(userId)).resolve(turnId).normalize();
        if (!dir.startsWith(userDir)) {
            throw new BusinessException(40000, "turnId 无效");
        }
        return dir;
    }

    private byte[] mergeChunks(Path dir) {
        List<Path> chunks;
        try (Stream<Path> stream = Files.list(dir)) {
            chunks = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("chunk-\\d{6}\\.webm"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to list call turn chunks", ex);
        }
        if (chunks.isEmpty()) {
            throw new BusinessException(40000, "音频分片不能为空");
        }
        for (int i = 0; i < chunks.size(); i++) {
            if (sequenceOf(chunks.get(i)) != i) {
                throw new BusinessException(40000, "音频分片不完整");
            }
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

    private int sequenceOf(Path chunk) {
        String fileName = chunk.getFileName().toString();
        return Integer.parseInt(fileName.substring("chunk-".length(), "chunk-000000".length()));
    }

    private record TurnMetadata(String sessionId, String agentId) {
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
