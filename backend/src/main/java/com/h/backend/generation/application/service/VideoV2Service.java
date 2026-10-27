package com.h.backend.generation.application.service;

import com.h.backend.generation.application.port.out.*;
import com.h.backend.generation.application.result.SubmitGenerationResult;
import com.h.backend.generation.domain.model.*;
import com.h.backend.generation.infrastructure.config.GenerationProperties;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** User-scoped video operations shared by both agent runtimes. */
@Service
public class VideoV2Service {
    private final VideoV2ProviderPort provider;
    private final VideoInputResolver inputs;
    private final GenerationTaskRepository tasks;
    private final GenerationTaskSubmissionCoordinator submissions;
    private final GenerationChatProjectionPort chat;
    private final GenerationProperties properties;

    public VideoV2Service(
            VideoV2ProviderPort provider,
            VideoInputResolver inputs,
            GenerationTaskRepository tasks,
            GenerationTaskSubmissionCoordinator submissions,
            GenerationChatProjectionPort chat,
            GenerationProperties properties) {
        this.provider = provider;
        this.inputs = inputs;
        this.tasks = tasks;
        this.submissions = submissions;
        this.chat = chat;
        this.properties = properties;
    }

    public SubmitGenerationResult submit(
            Long user,
            String session,
            String type,
            String prompt,
            String model,
            Integer seconds,
            String resolution,
            String ratio,
            List<VideoV2Spec.Input> media) {
        return submit(user, session, type, prompt, model, seconds, resolution, ratio, media, false);
    }

    public SubmitGenerationResult submit(
            Long user,
            String session,
            String type,
            String prompt,
            String model,
            Integer seconds,
            String resolution,
            String ratio,
            List<VideoV2Spec.Input> media,
            boolean generateAfterEnhancement) {
        String chosen =
                model == null || model.isBlank()
                        ? properties.getMinimax().getDefaultVideoModel()
                        : model;
        var spec = VideoV2Spec.defaults(type, prompt, chosen, seconds, resolution, ratio, media);
        // Resolve and validate before persisting/submitting any paid request.
        if (generateAfterEnhancement)
            spec =
                    new VideoV2Spec(
                            spec.taskType(),
                            spec.prompt(),
                            spec.model(),
                            spec.durationSeconds(),
                            spec.resolution(),
                            spec.ratio(),
                            spec.inputs(),
                            null,
                            true);
        final VideoV2Spec submitted = spec;
        var content = inputs.resolve(user, submitted);
        return submissions.submit(
                user, session, submitted, () -> "v2:" + provider.create(submitted, content));
    }

    public SubmitGenerationResult generateFromEnhancement(GenerationTask parent, String prompt) {
        var existing = tasks.findById(parent.id() + "-generation");
        if (existing.isPresent()) {
            var saved = existing.get();
            return new SubmitGenerationResult(
                    saved.id(), saved.providerTaskId(), saved.chatMessageId());
        }
        VideoV2Spec old = (VideoV2Spec) parent.spec();
        var spec =
                VideoV2Spec.defaults(
                        "generation",
                        prompt,
                        old.model(),
                        old.durationSeconds(),
                        old.resolution(),
                        old.ratio(),
                        old.inputs());
        var content = inputs.resolve(parent.userId(), spec);
        return submissions.submitWithId(
                parent.id() + "-generation",
                parent.userId(),
                parent.sessionId(),
                spec,
                () -> "v2:" + provider.create(spec, content));
    }

    public SubmitGenerationResult regenerate(Long user, String session, String localSourceId) {
        GenerationTask source = owned(user, localSourceId);
        if (!(source.spec() instanceof VideoV2Spec old)
                || !old.taskType().equals("generation")
                || !old.model().equals("MiniMax-H3")
                || !old.resolution().equals("768P")
                || source.status() != GenerationStatus.SUCCEEDED
                || source.artifact() == null)
            throw new IllegalArgumentException("仅支持将本应用已完成的MiniMax-H3 768P视频再生成为2K");
        List<VideoV2Spec.Input> media = new ArrayList<>(old.inputs());
        String upstream = null;
        if (properties.getMinimax().isRegenerationTaskIdEnabled()
                && !"DELETED".equals(source.providerStatus())
                && Instant.now().isBefore(source.createdAt().plus(Duration.ofDays(7)))) {
            upstream = rawId(source);
        } else media.add(new VideoV2Spec.Input(source.artifact().resourceId(), "base_video"));
        var spec =
                new VideoV2Spec(
                        "regeneration",
                        old.prompt(),
                        "MiniMax-H3",
                        old.durationSeconds(),
                        "2K",
                        old.ratio(),
                        media,
                        upstream);
        var content =
                spec.sourceTaskId() == null
                        ? inputs.resolve(user, spec)
                        : List.<Map<String, Object>>of();
        return submissions.submit(
                user, session, spec, () -> "v2:" + provider.create(spec, content));
    }

    public GenerationTask owned(Long user, String id) {
        return tasks.findById(id)
                .filter(task -> task.userId().equals(user) && task.spec() instanceof VideoGenerationSpec)
                .orElseThrow(() -> new IllegalArgumentException("视频任务不存在"));
    }

    public Map<String, Object> query(Long user, String id) {
        GenerationTask task = owned(user, id);
        Map<String, Object> result = summary(task);
        if (task.spec() instanceof VideoV2Spec
                && !"DELETED".equals(task.providerStatus())
                && task.providerTaskId() != null
                && Instant.now().isBefore(task.createdAt().plus(Duration.ofDays(7)))) {
            var current = provider.inspect(rawId(task));
            result.put("providerStatus", current.status());
            if (current.prompt() != null) result.put("prompt", current.prompt());
            if (current.error() != null) result.put("error", current.error());
        }
        return result;
    }

    public Map<String, Object> list(
            Long user, Integer limit, String status, String model, String type) {
        return list(user, limit, 1, status, model, type);
    }

    public Map<String, Object> list(
            Long user, Integer limit, Integer page, String status, String model, String type) {
        int size = limit == null ? 20 : limit;
        int selectedPage = page == null ? 1 : page;
        if (selectedPage < 1 || selectedPage > 100000) throw new IllegalArgumentException("页码超出范围");
        if (size < 1 || size > 100) throw new IllegalArgumentException("列表数量必须为1至100");
        var owned = tasks.findVideosByUser(user, size, (selectedPage - 1) * size);
        var recent =
                owned.stream()
                        .filter(
                                t ->
                                        t.spec() instanceof VideoV2Spec
                                                && !"DELETED".equals(t.providerStatus())
                                                && t.providerTaskId() != null
                                                && Instant.now()
                                                        .isBefore(
                                                                t.createdAt()
                                                                        .plus(Duration.ofDays(7))))
                        .toList();
        var upstream =
                provider.listOwned(
                        recent.stream().map(VideoV2Service::rawId).toList(),
                        1,
                        size,
                        status,
                        model,
                        type);
        Map<String, String> statuses = new HashMap<>();
        upstream.forEach(t -> statuses.put(t.taskId(), t.status()));
        var items =
                owned.stream()
                        .filter(
                                t ->
                                        model == null
                                                || model.isBlank()
                                                || t.spec() instanceof VideoV2Spec v
                                                        && v.model().equals(model))
                        .filter(
                                t ->
                                        type == null
                                                || type.isBlank()
                                                || t.spec() instanceof VideoV2Spec v
                                                        && v.taskType().equals(type))
                        .filter(
                                t ->
                                        status == null
                                                || status.isBlank()
                                                || status.equals(statuses.get(rawId(t)))
                                                || status.equalsIgnoreCase(t.status().name()))
                        .map(this::summary)
                        .toList();
        return Map.of(
                "items",
                items,
                "count",
                items.size(),
                "page",
                selectedPage,
                "pageSize",
                size,
                "scope",
                "当前用户在本应用创建的任务；供应商实时记录仅覆盖7天");
    }

    public Map<String, Object> remove(Long user, String id, boolean deleteRecord) {
        GenerationTask task = owned(user, id);
        if (!(task.spec() instanceof VideoV2Spec) || task.providerTaskId() == null)
            throw new IllegalArgumentException("该任务无法使用V2取消/删除接口");
        var before = provider.inspect(rawId(task));
        if (deleteRecord && !Set.of("succeeded", "failed").contains(before.status()))
            throw new IllegalArgumentException("只有成功或失败的供应商任务记录可以删除");
        if (!deleteRecord && !before.status().equals("queued"))
            throw new IllegalArgumentException("仅排队任务可取消；运行中的视频无法停止，仍会在完成后回填");
        if (deleteRecord
                && before.status().equals("succeeded")
                && task.artifact() == null
                && task.resultText() == null)
            throw new IllegalArgumentException("任务结果尚未转存完成，请稍后再删除供应商记录");
        var outcome = provider.remove(rawId(task));
        for (int attempt = 0; ; attempt++) {
            GenerationTask current = attempt == 0 ? task : owned(user, id);
            if (outcome.action().equals("cancelled") && !current.status().isTerminal())
                current.cancel(Instant.now());
            if (outcome.action().equals("deleted")) {
                if (!current.status().isTerminal())
                    current.fail(
                            deleteRecord ? "供应商任务记录已删除" : "取消时任务状态已改变，供应商返回删除记录；未确认取消成功",
                            Instant.now());
                current.providerRecordDeleted(Instant.now());
            }
            try {
                tasks.save(current);
                chat.updateMessage(current);
                break;
            } catch (org.springframework.dao.ConcurrencyFailureException ex) {
                if (attempt >= 2) throw ex;
            }
        }
        if (!Set.of("cancelled", "deleted").contains(outcome.action()))
            throw new IllegalStateException("供应商未确认取消或删除");
        return Map.of(
                "action",
                outcome.action(),
                "status",
                outcome.status(),
                "localResourcesDeleted",
                false,
                "message",
                outcome.action().equals("cancelled") ? "排队任务已取消" : "供应商记录已删除；本地已保存的视频仍保留");
    }

    private Map<String, Object> summary(GenerationTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.id());
        result.put("status", task.status().name());
        result.put("sessionId", task.sessionId());
        result.put("createdAt", task.createdAt().toString());
        if (task.spec() instanceof VideoV2Spec v) {
            result.put("taskType", v.taskType());
            result.put("model", v.model());
            result.put("duration", v.durationSeconds());
            result.put("resolution", v.resolution());
            if (v.generateAfterEnhancement())
                result.put("generationTaskId", task.id() + "-generation");
        }
        if ("DELETED".equals(task.providerStatus())) result.put("providerRecordDeleted", true);
        if (task.resultText() != null) result.put("prompt", task.resultText());
        if (task.failureMessage() != null) result.put("error", task.failureMessage());
        if (task.artifact() != null) result.put("resourceId", task.artifact().resourceId());
        return result;
    }

    private static String rawId(GenerationTask task) {
        String id = task.providerTaskId();
        return id == null ? "" : id.startsWith("v2:") ? id.substring(3) : id;
    }
}
