package com.h.backend.document.infrastructure.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.h.backend.chat.application.*;
import com.h.backend.document.application.DocumentWorkflow;
import com.h.backend.document.domain.DocumentTask;
import com.h.backend.document.domain.DocumentContinuation;
import com.h.backend.document.infrastructure.persistence.DocumentRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

class DocumentJobTest {
    final DocumentRepository repository = mock(DocumentRepository.class);
    final DocumentWorkflow workflow = mock(DocumentWorkflow.class);
    final AgentRunService runs = mock(AgentRunService.class);
    final ChatSessionService sessions = mock(ChatSessionService.class);

    @SuppressWarnings("unchecked")
    final ObjectProvider<ChatService> chats = mock(ObjectProvider.class);

    DocumentTask task(Long run) {
        var task = new DocumentTask();
        task.setUserId(1L);
        task.setSessionId("chat");
        task.setId("doc");
        task.setStatus("GOOD");
        task.setResumeStatus("RUNNING");
        task.setResumedRunId(run);
        when(repository.change(eq(1L), eq("chat"), eq("doc"), any()))
                .thenAnswer(
                        call -> {
                            Consumer<DocumentTask> mutation = call.getArgument(3);
                            mutation.accept(task);
                            return task;
                        });
        return task;
    }

    @Test
    void recognizedTaskStartsANewReplyAfterTheProvisionalAcknowledgment() {
        var task = task(null);
        task.setStatus("RECOGNIZED");
        task.setResumeStatus("QUEUED");
        task.setAnchorMessageId(871L);
        var acknowledged = java.time.LocalDateTime.of(2026, 11, 7, 13, 36, 49);
        task.setRecognizedAtMillis(acknowledged.plusSeconds(10)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
        when(runs.findByUserMessage(1L, "chat", 871L)).thenReturn(
                new com.h.backend.chat.domain.model.AgentRunSummary(
                        257L, "SUCCEEDED", 873L, 2, "[]", null, acknowledged));
        when(sessions.getSessionDetail(1L, "chat")).thenReturn(
                new com.h.backend.chat.interfaces.dto.ChatSessionMetaDto(
                        "chat", "active", null, null, null, null, null, null,
                        0, null, null, false));
        var chat = mock(ChatService.class);
        when(chats.getObject()).thenReturn(chat);
        var reply = new com.h.backend.chat.interfaces.dto.ChatSessionMessageDto(
                "874", "assistant", "AI", "识别正文", null, java.util.List.of(), null);
        when(chat.streamChat(eq(1L), isNull(), isNull(), eq("chat"), anyString(), anyList()))
                .thenReturn(reactor.core.publisher.Flux.deferContextual(context -> {
                    var continuation = context.get(DocumentContinuation.class);
                    assertEquals(871L, continuation.userMessageId());
                    assertEquals("doc", continuation.taskId());
                    task.setResumedRunId(258L);
                    return reactor.core.publisher.Flux.just(
                            new com.h.backend.chat.interfaces.dto.ChatStreamEvent("done", "", reply));
                }));
        try (var job = newJob()) {
            job.resume(task);
        }
        assertEquals("DONE", task.getResumeStatus());
        assertEquals(258L, task.getResumedRunId());
        verify(chat).streamChat(eq(1L), isNull(), isNull(), eq("chat"), anyString(), anyList());
    }

    @Test
    void archivedConversationSettlesContinuationInsteadOfBlockingTheQueue() {
        var task = task(null);
        task.setResumeStatus("QUEUED");
        when(sessions.getSessionDetail(1L, "chat"))
                .thenReturn(new com.h.backend.chat.interfaces.dto.ChatSessionMetaDto(
                        "chat", "archived", null, null, null, null, null, null,
                        0, null, null, true));
        try (var job = newJob()) {
            job.resume(task);
        }
        assertEquals("CANCELLED", task.getResumeStatus());
        assertTrue(task.isContinuationCancelled());
        verifyNoInteractions(chats);
    }

    @Test
    void crashBeforeRunCreationRequeuesWithoutDispatchingTwice() {
        var task = task(null);
        try (var job = newJob()) {
            job.resume(task);
        }
        assertEquals("QUEUED", task.getResumeStatus());
        verifyNoInteractions(chats);
    }

    @Test
    void reclaimedInterruptedRunRequiresExplicitContinuation() {
        var task = task(42L);
        when(repository.runStatus(42L)).thenReturn("RUNNING");
        try (var job = newJob()) {
            job.resume(task);
        }
        verify(runs).failRun(eq(42L), anyString());
        assertEquals("NEEDS_RESUME", task.getResumeStatus());
        verifyNoInteractions(chats);
    }

    @Test
    void pendingHarnessApprovalKeepsItsExistingRun() {
        var task = task(42L);
        when(repository.runStatus(42L)).thenReturn("WAITING_APPROVAL");
        try (var job = newJob()) {
            job.resume(task);
        }
        assertEquals("RUNNING", task.getResumeStatus());
        verify(runs, never()).failRun(anyLong(), anyString());
        verifyNoInteractions(chats);
    }

    private JobHandle newJob() {
        return new JobHandle(new DocumentJob(repository, workflow, chats, runs, sessions));
    }

    private record JobHandle(DocumentJob job) implements AutoCloseable {
        void resume(DocumentTask task) {
            job.resume(task);
        }

        public void close() {
            job.close();
        }
    }
}
