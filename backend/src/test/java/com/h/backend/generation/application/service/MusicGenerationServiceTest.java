package com.h.backend.generation.application.service;

import com.h.backend.generation.application.port.out.*;
import com.h.backend.generation.domain.model.*;
import com.h.backend.generation.infrastructure.config.MusicGenerationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicGenerationServiceTest {
    private final MemoryTasks tasks = new MemoryTasks();
    private final GenerationChatProjectionPort projection = mock(GenerationChatProjectionPort.class);
    private final MusicProviderPort provider = mock(MusicProviderPort.class);
    private final GeneratedArtifactStoragePort storage = mock(GeneratedArtifactStoragePort.class);
    private final Queue<Runnable> workers = new ArrayDeque<>();
    private final MusicGenerationService service;

    MusicGenerationServiceTest() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(projection.createPendingMessage(any())).thenReturn(9L);
        when(storage.storeAudio(anyString(), any(), any())).thenAnswer(call -> {
            GeneratedArtifactAttachment<?> attachment = call.getArgument(2);
            return attachment.attach(new GeneratedArtifact("audio", "OBJECT_STORAGE", "audio/key.mp3", "audio/mpeg", "song.mp3", 3));
        });
        service = new MusicGenerationService(tasks, projection, provider, storage,
                new MusicGenerationProperties(), workers::add, new TransactionTemplate(manager));
    }

    @Test
    void acceptsBeforeCallingProviderAndPreservesEveryLyricByte() {
        String lyrics = "[Verse]\n 月光，落在窗边 \n\n[Chorus]\n不要改写这一行！";
        when(provider.sing(lyrics, "温柔女声，民谣")).thenReturn(new byte[]{1, 2, 3});
        var result = service.submit(1L, "session", new MusicGenerationSpec("温柔女声，民谣", lyrics, null));
        verifyNoInteractions(provider);
        var queued = tasks.findById(result.taskId()).orElseThrow();
        assertEquals("QUEUED", queued.providerStatus());
        assertEquals(9L, queued.chatMessageId());

        service.dispatch(queued, Instant.now());
        verifyNoInteractions(provider);
        workers.remove().run();

        var complete = tasks.findById(result.taskId()).orElseThrow();
        assertEquals(GenerationStatus.SUCCEEDED, complete.status());
        assertEquals(lyrics, complete.resultText());
        assertEquals("audio/mpeg", complete.artifact().mimeType());
        verify(provider, never()).writeLyrics(any());
        verify(provider, times(1)).sing(lyrics, "温柔女声，民谣");
        assertEquals("SUCCEEDED", service.query(1L, "session", result.taskId()).get("status"));
    }

    @Test
    void writesWithMiniMaxBeforeSingingAndPersistsLyrics() {
        var spec = new MusicGenerationSpec("夏日海边，轻快女声", null, "海风");
        when(provider.writeLyrics(spec)).thenReturn(new MusicProviderPort.Lyrics("海风", "Pop", "[Verse]\n海风吹过沙滩"));
        when(provider.sing(anyString(), anyString())).thenAnswer(call -> {
            assertEquals("[Verse]\n海风吹过沙滩", tasks.values.values().iterator().next().resultText());
            return new byte[]{1};
        });
        var result = service.submit(1L, "session", spec);
        service.dispatch(tasks.findById(result.taskId()).orElseThrow(), Instant.now());
        workers.remove().run();
        var ordered = inOrder(provider);
        ordered.verify(provider).writeLyrics(spec);
        ordered.verify(provider).sing("[Verse]\n海风吹过沙滩", "夏日海边，轻快女声\n风格建议：Pop");
        assertEquals(GenerationStatus.SUCCEEDED, tasks.findById(result.taskId()).orElseThrow().status());
    }

    @Test
    void staleWorkersAndExpiredClaimsCannotRepeatPaidGeneration() {
        var result = service.submit(1L, "session", new MusicGenerationSpec(null, "已有歌词", null));
        var first = tasks.findById(result.taskId()).orElseThrow();
        var stale = tasks.findById(result.taskId()).orElseThrow();
        service.dispatch(first, Instant.now());
        assertThrows(ConcurrencyFailureException.class, () -> service.dispatch(stale, Instant.now()));
        var running = tasks.findById(result.taskId()).orElseThrow();
        service.dispatch(running, running.nextPollAt().plusSeconds(1));
        assertEquals(GenerationStatus.SUBMISSION_UNKNOWN, tasks.findById(result.taskId()).orElseThrow().status());
        // A delayed executor must also check that its persisted claim is still active.
        workers.remove().run();
        verifyNoInteractions(provider);
    }

    @Test
    void providerTimeoutIsTerminalAndDoesNotTriggerAnAutomaticRetry() {
        when(provider.sing("已有歌词", null)).thenThrow(new MusicProviderException("生成结果待确认", true));
        var result = service.submit(1L, "session", new MusicGenerationSpec(null, "已有歌词", null));
        service.dispatch(tasks.findById(result.taskId()).orElseThrow(), Instant.now());
        workers.remove().run();
        var failed = tasks.findById(result.taskId()).orElseThrow();
        assertEquals(GenerationStatus.SUBMISSION_UNKNOWN, failed.status());
        assertNull(failed.nextPollAt());
        service.dispatch(failed, Instant.now().plusSeconds(600));
        verify(provider, times(1)).sing("已有歌词", null);
        verifyNoInteractions(storage);
    }

    @Test
    void excessiveLyricsAreRejectedWithoutTruncationOrSinging() {
        assertThrows(IllegalArgumentException.class,
                () -> new MusicGenerationSpec(null, "字".repeat(3501), null));
        var spec = new MusicGenerationSpec("创作主题", null, null);
        when(provider.writeLyrics(spec)).thenReturn(new MusicProviderPort.Lyrics("", "", "字".repeat(3501)));
        var result = service.submit(1L, "session", spec);
        service.dispatch(tasks.findById(result.taskId()).orElseThrow(), Instant.now());
        workers.remove().run();
        assertEquals(GenerationStatus.FAILED, tasks.findById(result.taskId()).orElseThrow().status());
        verify(provider, never()).sing(any(), any());
    }

    @Test
    void cannotReadAnotherUserOrSessionsTask() {
        var result = service.submit(1L, "session", new MusicGenerationSpec(null, "已有歌词", null));
        assertThrows(IllegalArgumentException.class, () -> service.query(2L, "session", result.taskId()));
        assertThrows(IllegalArgumentException.class, () -> service.query(1L, "other-session", result.taskId()));
    }

    @Test
    void storageFailureCannotResubmitThePaidRequest() {
        when(provider.sing("已有歌词", null)).thenReturn(new byte[]{1});
        doThrow(new IllegalStateException("private storage details")).when(storage).storeAudio(anyString(), any(), any());
        var result = service.submit(1L, "session", new MusicGenerationSpec(null, "已有歌词", null));
        service.dispatch(tasks.findById(result.taskId()).orElseThrow(), Instant.now());
        workers.remove().run();
        var failed = tasks.findById(result.taskId()).orElseThrow();
        assertEquals(GenerationStatus.FAILED, failed.status());
        assertFalse(failed.failureMessage().contains("private"));
        service.dispatch(failed, Instant.now());
        verify(provider, times(1)).sing("已有歌词", null);
    }

    private static class MemoryTasks implements GenerationTaskRepository {
        final Map<String, GenerationTask> values = new HashMap<>();
        @Override public void save(GenerationTask task) {
            GenerationTask previous = values.get(task.id());
            if (previous != null && previous.version() != task.version()) throw new ConcurrencyFailureException("stale");
            task.persisted(previous == null ? 0 : task.version() + 1);
            values.put(task.id(), copy(task));
        }
        @Override public Optional<GenerationTask> findById(String id) { return Optional.ofNullable(values.get(id)).map(MemoryTasks::copy); }
        @Override public List<GenerationTask> findDue(Instant now, int limit) { return List.of(); }
        private static GenerationTask copy(GenerationTask task) {
            var copy = GenerationTask.rehydrate(task.id(), task.userId(), task.sessionId(), task.spec(), task.status(),
                    task.providerTaskId(), task.providerStatus(), task.providerFileId(), task.chatMessageId(), task.artifact(),
                    task.retryCount(), task.nextPollAt(), task.failureMessage(), task.createdAt(), task.updatedAt(), task.completedAt());
            copy.restoredDetails(task.resultText(), task.version());
            return copy;
        }
    }
}
