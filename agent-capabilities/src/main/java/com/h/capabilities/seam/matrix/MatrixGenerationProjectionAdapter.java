package com.h.capabilities.seam.matrix;

import com.h.capabilities.chat.infrastructure.storage.ResourceRange;
import com.h.capabilities.chat.infrastructure.storage.ResourceStorage;
import com.h.capabilities.generation.application.port.out.GenerationChatProjectionPort;
import com.h.capabilities.generation.domain.model.GenerationStatus;
import com.h.capabilities.generation.domain.model.GenerationTask;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamProtocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队视频投影：Matrix 消息即投影。提交时发"生成中"占位文本（句柄恒为 0，
 * 仅满足 bind-once 约束），终态时把持久化成果上传为 mxc 并发 m.video，
 * 失败/取消/提交待确认发文本说明。轮询线程无 agent 上下文，客户端按 task.actor() 选择。
 */
public class MatrixGenerationProjectionAdapter implements GenerationChatProjectionPort {

    private static final Logger log = LoggerFactory.getLogger(MatrixGenerationProjectionAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_VIDEO_BYTES = 100 * 1024 * 1024;

    private final List<MatrixClient> clients;
    private final ResourceStorage storage;
    private final Set<String> delivered = ConcurrentHashMap.newKeySet();

    public MatrixGenerationProjectionAdapter(List<MatrixClient> clients, ResourceStorage storage) {
        this.clients = clients;
        this.storage = storage;
    }

    @Override
    public Long createPendingMessage(GenerationTask task) {
        MatrixClients.forActor(clients, task.actor())
                .sendText(task.sessionId(), "视频生成中，完成后会自动发送到本房间。", null, null, null);
        return 0L;
    }

    @Override
    public void updateMessage(GenerationTask task) {
        GenerationStatus status = task.status();
        if (status == GenerationStatus.SUCCEEDED) {
            deliverVideo(task);
        } else if (status == GenerationStatus.FAILED
                || status == GenerationStatus.CANCELLED
                || status == GenerationStatus.SUBMISSION_UNKNOWN) {
            deliverFailure(task);
        }
    }

    private void deliverVideo(GenerationTask task) {
        if (task.artifact() == null || !delivered.add(task.id())) {
            return;
        }
        try {
            var artifact = task.artifact();
            byte[] bytes = readArtifact(artifact.storageKey());
            MatrixClient client = MatrixClients.forActor(clients, task.actor());
            String fileName = artifact.fileName() == null ? "video.mp4" : artifact.fileName();
            String mime = artifact.mimeType() == null ? "video/mp4" : artifact.mimeType();
            String mxc = client.upload(fileName, mime, bytes);
            ObjectNode info = JSON.createObjectNode();
            info.put("mimetype", mime);
            info.put("size", bytes.length);
            client.sendMedia(task.sessionId(), TeamProtocol.MSG_VIDEO, mxc, fileName, info, null, null, null);
        } catch (RuntimeException failure) {
            delivered.remove(task.id());
            log.warn("团队视频交付失败 taskId={} roomId={}", task.id(), task.sessionId(), failure);
        }
    }

    private void deliverFailure(GenerationTask task) {
        if (!delivered.add(task.id())) {
            return;
        }
        String text = switch (task.status()) {
            case CANCELLED -> "视频任务已取消。";
            case SUBMISSION_UNKNOWN -> "视频提交结果待确认，请勿自动重复提交。"
                    + (task.failureMessage() == null ? "" : "（" + task.failureMessage() + "）");
            default -> "视频生成失败：" + (task.failureMessage() == null ? "未知原因" : task.failureMessage());
        };
        try {
            MatrixClients.forActor(clients, task.actor())
                    .sendText(task.sessionId(), text, null, null, null);
        } catch (RuntimeException failure) {
            delivered.remove(task.id());
            log.warn("团队视频失败通知发送失败 taskId={}", task.id(), failure);
        }
    }

    private byte[] readArtifact(String storageKey) {
        var stored = storage.open(storageKey, ResourceRange.fullRead());
        try (var stream = stored.inputStream()) {
            if (stored.totalSize() > MAX_VIDEO_BYTES) {
                throw new IllegalStateException("生成视频超过团队交付大小限制");
            }
            byte[] bytes = stream.readNBytes(MAX_VIDEO_BYTES + 1);
            if (bytes.length > MAX_VIDEO_BYTES) {
                throw new IllegalStateException("生成视频超过团队交付大小限制");
            }
            return bytes;
        } catch (IOException failure) {
            throw new IllegalStateException("读取生成视频失败", failure);
        }
    }
}