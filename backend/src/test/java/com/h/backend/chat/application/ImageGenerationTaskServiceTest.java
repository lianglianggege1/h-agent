package com.h.backend.chat.application;

import com.h.backend.chat.application.impl.ImageGenerationTaskService;
import com.h.backend.chat.domain.model.ChatMessagePayload;
import com.h.backend.chat.infrastructure.persistence.mapper.ImageGenerationTaskMapper;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageGenerationTaskServiceTest {
    @Test
    void queuedImageKeepsSubmittingTraceAndRestoresWorkerAfterFailure() throws Exception {
        var fixture = new Fixture();
        var transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(call ->
                ((org.springframework.transaction.support.TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        when(fixture.sessions.appendResourceMessage(any(), any(), anyString(), eq("IMAGE"), anyList()))
                .thenReturn(message("PENDING"));
        var service = new ImageGenerationTaskService(fixture.tasks, fixture.sessions, fixture.generator,
                transaction, fixture.json);
        var observedTrace = new java.util.concurrent.atomic.AtomicReference<String>();
        when(fixture.generator.generateImage(fixture.command, 501L)).thenAnswer(call -> {
            observedTrace.set(org.slf4j.MDC.get("traceId"));
            throw new IllegalStateException("provider failed");
        });
        try {
            org.slf4j.MDC.put("traceId", "submitting-trace");
            org.slf4j.MDC.put("spanId", "submitting-span");
            service.submit(fixture.command);
        } finally {
            org.slf4j.MDC.clear();
        }
        var queued = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(fixture.tasks).enqueue(eq(501L), queued.capture());
        when(fixture.tasks.claim()).thenReturn(new ImageGenerationTaskMapper.Task(501L, queued.getValue()));
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                org.slf4j.MDC.put("traceId", "worker-trace");
                try {
                    service.processNext();
                    assertEquals("worker-trace", org.slf4j.MDC.get("traceId"));
                } finally {
                    org.slf4j.MDC.clear();
                }
            }).get();
        }
        assertEquals("submitting-trace", observedTrace.get());
        verify(fixture.tasks).finish(501L, "FAILED");
    }

    @Test
    void submissionReturnsPendingMessageWithoutCallingProviderForTextOrReferenceImage() {
        var sessions = mock(ChatSessionService.class);
        var generator = mock(ImageGenerationService.class);
        var tasks = mock(ImageGenerationTaskMapper.class);
        var transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(call ->
                ((org.springframework.transaction.support.TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        var pending = new ChatSessionMessageDto("501", "assistant", "IMAGE", "图片生成中，请继续聊天。", null, List.of(), LocalDateTime.now());
        when(sessions.appendResourceMessage(eq(1L), eq("session"), anyString(), eq("IMAGE"), eq(List.of()))).thenReturn(pending);
        when(sessions.updateImageMessage(eq(1L), eq("session"), eq(501L), anyString(), any(), eq(List.of()))).thenReturn(pending);
        var service = new ImageGenerationTaskService(tasks, sessions, generator, transaction, new ObjectMapper());

        for (String reference : new String[]{null, "reference-1"}) {
            assertSame(pending, service.submit(new ImageGenerationService.ImageGenerationCommand(
                    1L, "session", null, "白猫", "TOOL", reference, null, "GENERATE")));
        }
        verifyNoInteractions(generator);
        verify(tasks, times(2)).enqueue(eq(501L), anyString());
        var payload = org.mockito.ArgumentCaptor.forClass(ChatMessagePayload.class);
        verify(sessions, times(2)).updateImageMessage(eq(1L), eq("session"), eq(501L), anyString(), payload.capture(), eq(List.of()));
        assertEquals("PENDING", payload.getValue().getStatus());
        assertEquals("reference-1", payload.getValue().getSourceResourceId());
    }

    @Test
    void workerUsesReservedMessageAndPreservesReferenceImage() {
        var fixture = new Fixture();
        fixture.service.processNext();
        verify(fixture.generator).generateImage(fixture.command, 501L);
        verify(fixture.tasks).finish(501L, "SUCCEEDED");
        verify(fixture.sessions, never()).appendImageMessage(any(), any(), any(), any(), any());
    }

    @Test
    void failedGenerationIsShownInChatWithoutResubmittingProviderRequest() {
        var fixture = new Fixture();
        when(fixture.generator.generateImage(fixture.command, 501L)).thenThrow(new IllegalStateException("provider unavailable"));
        fixture.service.processNext();
        verify(fixture.tasks).finish(501L, "FAILED");
        verify(fixture.generator, times(1)).generateImage(fixture.command, 501L);
    }

    @Test
    void networkTimeoutIsReportedAsUncertainWithoutRetrying() {
        var fixture = new Fixture();
        when(fixture.generator.generateImage(fixture.command, 501L))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("timed out"));
        fixture.service.processNext();
        verify(fixture.tasks).finish(501L, "SUBMISSION_UNKNOWN");
        verify(fixture.generator, times(1)).generateImage(fixture.command, 501L);
    }

    @Test
    void interruptedWorkBecomesUnknownWithoutAutomaticPaidRetry() {
        var fixture = new Fixture();
        when(fixture.tasks.findStale(3600)).thenReturn(List.of(fixture.task));
        fixture.service.recoverStale(3600);
        verify(fixture.tasks).finish(501L, "SUBMISSION_UNKNOWN");
        verifyNoInteractions(fixture.generator);
    }

    @Test
    void recoveryKeepsAlreadyCommittedImageWhenQueueCompletionWasInterrupted() {
        var fixture = new Fixture();
        when(fixture.tasks.findStale(3600)).thenReturn(List.of(fixture.task));
        doReturn(message("READY")).when(fixture.sessions)
                .updateImageMessage(any(), any(), any(), anyString(), any(), anyList());
        fixture.service.recoverStale(3600);
        verify(fixture.tasks).finish(501L, "SUCCEEDED");
        verifyNoInteractions(fixture.generator);
    }

    private static ChatSessionMessageDto message(String status) {
        return new ChatSessionMessageDto("501", "assistant", "IMAGE", "白猫",
                new com.h.backend.chat.interfaces.dto.ChatMessagePayloadDto("白猫", "MINIMAX", null, null, null,
                        status, "TOOL", "reference-1", null, "GENERATE"), List.of(), LocalDateTime.now());
    }

    private static class Fixture {
        final ChatSessionService sessions = mock(ChatSessionService.class);
        final ImageGenerationService generator = mock(ImageGenerationService.class);
        final ImageGenerationTaskMapper tasks = mock(ImageGenerationTaskMapper.class);
        final ObjectMapper json = new ObjectMapper();
        final ImageGenerationService.ImageGenerationCommand command = new ImageGenerationService.ImageGenerationCommand(
                1L, "session", null, "白猫", "TOOL", "reference-1", null, "GENERATE");
        final ImageGenerationTaskMapper.Task task = new ImageGenerationTaskMapper.Task(501L, json.writeValueAsString(command));
        final ImageGenerationTaskService service = new ImageGenerationTaskService(tasks, sessions, generator,
                mock(TransactionTemplate.class), json);

        Fixture() {
            when(tasks.claim()).thenReturn(task);
            when(sessions.updateImageMessage(any(), any(), any(), anyString(), any(), anyList()))
                    .thenAnswer(call -> message(((ChatMessagePayload) call.getArgument(4)).getStatus()));
        }
    }
}
