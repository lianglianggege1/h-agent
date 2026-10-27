package com.h.backend.generation.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.h.backend.generation.application.port.out.*;
import com.h.backend.generation.domain.model.*;
import com.h.backend.generation.infrastructure.config.GenerationProperties;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class VideoV2ServiceTest {
    @Test
    void musicCannotAppearInVideoQueriesOrProviderTaskFilters() {
        var song = GenerationTask.create("song", 1L, "s", new MusicGenerationSpec(null, "原词", null), Instant.now());
        song.queueMusic(Instant.now());
        tasks.save(song);
        assertThrows(IllegalArgumentException.class, () -> service.query(1L, "song"));
        assertEquals(List.of(), service.list(1L, 20, null, null, null).get("items"));
        assertEquals(List.of(), provider.listIds);
    }
    final Instant now = Instant.now();
    final MemoryTasks tasks = new MemoryTasks();
    final FakeProvider provider = new FakeProvider();
    final GenerationChatProjectionPort chat =
            new GenerationChatProjectionPort() {
                public Long createPendingMessage(GenerationTask t) {
                    return 10L;
                }

                public void updateMessage(GenerationTask t) {}
            };
    final GenerationTaskSubmissionCoordinator submissions =
            new GenerationTaskSubmissionCoordinator(tasks, chat, Clock.fixed(now, ZoneOffset.UTC));
    final VideoV2Service service =
            new VideoV2Service(
                    provider,
                    (u, s) -> List.of(Map.of("type", "text", "text", s.prompt())),
                    tasks,
                    submissions,
                    chat,
                    new GenerationProperties());

    @Test
    void uncertainCreationIsDurableAndNeverAutomaticallySubmittedAgain() {
        provider.uncertain = true;
        var result = service.submit(1L, "s", "generation", "海边", null, 15, null, null, List.of());
        assertNull(result.providerTaskId());
        var saved = tasks.findById(result.taskId()).orElseThrow();
        assertEquals(GenerationStatus.SUBMISSION_UNKNOWN, saved.status());
        assertNull(saved.nextPollAt());
        assertEquals("SUBMISSION_UNKNOWN", service.query(1L, result.taskId()).get("status"));
        assertEquals(1, provider.creates);
    }

    @Test
    void foreignTasksCannotBeInspectedDeletedOrIncludedInProviderListFilters() {
        var own = service.submit(1L, "s", "generation", "own", null, null, null, null, List.of());
        var foreign =
                service.submit(
                        2L, "other", "generation", "private", null, null, null, null, List.of());
        assertThrows(IllegalArgumentException.class, () -> service.query(1L, foreign.taskId()));
        assertThrows(
                IllegalArgumentException.class, () -> service.remove(1L, foreign.taskId(), true));
        service.list(1L, 20, null, null, null);
        assertEquals(List.of(own.providerTaskId().substring(3)), provider.listIds);
    }

    @Test
    void runningTasksCannotBeCancelledAndQueuedCancellationIsTerminal() {
        var result =
                service.submit(1L, "s", "generation", "clip", null, null, null, null, List.of());
        provider.status = "running";
        assertThrows(
                IllegalArgumentException.class, () -> service.remove(1L, result.taskId(), false));
        assertEquals(0, provider.removes);
        provider.status = "queued";
        assertEquals("cancelled", service.remove(1L, result.taskId(), false).get("action"));
        var task = tasks.findById(result.taskId()).orElseThrow();
        assertEquals(GenerationStatus.CANCELLED, task.status());
        assertThrows(
                IllegalStateException.class,
                () -> task.recordProviderProgress("running", now.plusSeconds(5), now));
    }

    @Test
    void enhancedTextFinishesWithoutAttemptingVideoMaterialization() {
        var result =
                service.submit(1L, "s", "h3_context_ir", "优化这段提示词", null, 6, null, null, List.of());
        var query =
                (ProviderTaskQueryPort)
                        (id) ->
                                new ProviderTaskQueryPort.ProviderTaskStatus(
                                        ProviderTaskQueryPort.ProviderTaskStatus.Status.SUCCESS,
                                        null,
                                        null,
                                        "增强后的完整提示词");
        new PollDueGenerationTasksService(
                        tasks,
                        query,
                        null,
                        chat,
                        new GenerationProperties(),
                        Clock.fixed(now.plusSeconds(6), ZoneOffset.UTC))
                .execute();
        var task = tasks.findById(result.taskId()).orElseThrow();
        assertEquals(GenerationStatus.SUCCEEDED, task.status());
        assertEquals("增强后的完整提示词", task.resultText());
        assertNull(task.artifact());
        assertEquals(1, provider.creates);
    }

    @Test
    void continuationHasAStableIdentityAndDoesNotSubmitTwiceAfterRecovery() {
        var spec =
                new VideoV2Spec(
                        "h3_context_ir",
                        "原始描述",
                        "MiniMax-H3",
                        6,
                        "768P",
                        "16:9",
                        List.of(),
                        null,
                        true);
        var parent = GenerationTask.create("parent", 1L, "s", spec, now);
        parent.markSubmitted("v2:ir", now, now);
        tasks.save(parent);
        var first = service.generateFromEnhancement(parent, "最终增强描述");
        var second = service.generateFromEnhancement(parent, "最终增强描述");
        assertEquals(first.taskId(), second.taskId());
        assertEquals(1, provider.creates);
        assertEquals(
                "最终增强描述",
                ((VideoV2Spec) tasks.findById(first.taskId()).orElseThrow().spec()).prompt());
    }

    @Test
    void invalidDestinationCannotCreateAPaidProviderTask() {
        var rejecting =
                new GenerationChatProjectionPort() {
                    public Long createPendingMessage(GenerationTask task) {
                        throw new IllegalArgumentException("会话不存在");
                    }

                    public void updateMessage(GenerationTask task) {}
                };
        var coordinator =
                new GenerationTaskSubmissionCoordinator(
                        tasks, rejecting, Clock.fixed(now, ZoneOffset.UTC));
        var guarded =
                new VideoV2Service(
                        provider,
                        (u, spec) -> List.of(Map.of("type", "text", "text", spec.prompt())),
                        tasks,
                        coordinator,
                        rejecting,
                        new GenerationProperties());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        guarded.submit(
                                1L,
                                "foreign",
                                "generation",
                                "test",
                                null,
                                null,
                                null,
                                null,
                                List.of()));
        assertEquals(0, provider.creates);
    }

    @Test
    void crashedSubmissionBecomesUnknownWithoutRepeatingThePost() {
        var pending =
                GenerationTask.create(
                        "crashed",
                        1L,
                        "s",
                        VideoV2Spec.defaults(
                                "generation", "test", null, null, null, null, List.of()),
                        now.minusSeconds(300));
        tasks.save(pending);
        ProviderTaskQueryPort forbidden =
                id -> {
                    throw new AssertionError(
                            "Unknown provider ID must not be queried or resubmitted");
                };
        new PollDueGenerationTasksService(
                        tasks,
                        forbidden,
                        null,
                        chat,
                        new GenerationProperties(),
                        Clock.fixed(now, ZoneOffset.UTC))
                .execute();
        assertEquals(GenerationStatus.SUBMISSION_UNKNOWN, pending.status());
        assertEquals(0, provider.creates);
        assertNotNull(pending.chatMessageId());
    }

    @Test
    void deletingTheProviderRecordPreservesTheSavedArtifactAndLocalQueries() {
        var result =
                service.submit(1L, "s", "generation", "test", null, null, null, null, List.of());
        var task = tasks.findById(result.taskId()).orElseThrow();
        task.startMaterialization("v2:ready", now);
        var artifact =
                new GeneratedArtifact(
                        "saved", "OBJECT_STORAGE", "key", "video/mp4", "clip.mp4", 123L);
        task.complete(artifact, now);
        provider.status = "succeeded";
        assertEquals("deleted", service.remove(1L, task.id(), true).get("action"));
        assertEquals(artifact, task.artifact());
        assertEquals("DELETED", task.providerStatus());
        assertEquals(true, service.query(1L, task.id()).get("providerRecordDeleted"));
    }

    @Test
    void expiredMaterializationDoesNotQueryBeyondTheSevenDayWindow() {
        var created = now.minus(Duration.ofDays(8));
        var task =
                GenerationTask.create(
                        "expired",
                        1L,
                        "s",
                        VideoV2Spec.defaults(
                                "generation", "test", null, null, null, null, List.of()),
                        created);
        task.markSubmitted("v2:old", created, created);
        task.startMaterialization("v2:old", created);
        tasks.save(task);
        ProviderTaskQueryPort forbidden =
                id -> {
                    throw new AssertionError("expired task queried");
                };
        new PollDueGenerationTasksService(
                        tasks,
                        forbidden,
                        null,
                        chat,
                        new GenerationProperties(),
                        Clock.fixed(now, ZoneOffset.UTC))
                .execute();
        assertEquals(GenerationStatus.FAILED, task.status());
        assertTrue(task.failureMessage().contains("7日"));
    }

    static class MemoryTasks implements GenerationTaskRepository {
        final Map<String, GenerationTask> rows = new LinkedHashMap<>();

        public void save(GenerationTask t) {
            rows.put(t.id(), t);
        }

        public Optional<GenerationTask> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        public List<GenerationTask> findStaleSubmissions(Instant before, int limit) {
            return rows.values().stream()
                    .filter(
                            t ->
                                    t.status() == GenerationStatus.PENDING_SUBMISSION
                                            && t.updatedAt().isBefore(before))
                    .toList();
        }

        public List<GenerationTask> findDue(Instant now, int limit) {
            return rows.values().stream()
                    .filter(t -> t.nextPollAt() != null && !t.nextPollAt().isAfter(now))
                    .toList();
        }

        public List<GenerationTask> findByUser(Long user, int limit) {
            return rows.values().stream()
                    .filter(t -> t.userId().equals(user))
                    .limit(limit)
                    .toList();
        }
    }

    static class FakeProvider implements VideoV2ProviderPort {
        int creates, removes;
        boolean uncertain;
        String status = "queued";
        List<String> listIds;

        public String create(VideoV2Spec s, List<Map<String, Object>> c) {
            creates++;
            if (uncertain) throw new VideoSubmissionUncertainException(new java.io.IOException());
            return "upstream-" + creates;
        }

        public TaskInfo inspect(String id) {
            return new TaskInfo(id, status, "generation", null, null, null);
        }

        public List<TaskInfo> listOwned(
                List<String> ids, int p, int size, String status, String model, String type) {
            listIds = ids;
            return ids.stream().map(this::inspect).toList();
        }

        public Removal remove(String id) {
            removes++;
            return status.equals("queued")
                    ? new Removal("cancelled", "cancelled")
                    : new Removal("deleted", status);
        }
    }
}
