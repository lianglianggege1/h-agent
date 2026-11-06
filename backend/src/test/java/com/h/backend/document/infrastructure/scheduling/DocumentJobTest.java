package com.h.backend.document.infrastructure.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.h.backend.chat.application.*;
import com.h.backend.document.application.DocumentWorkflow;
import com.h.backend.document.domain.DocumentTask;
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
