package com.h.backend.generation.application.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.generation.application.result.SubmitGenerationResult;
import com.h.backend.generation.interfaces.tool.*;

import dev.langchain4j.agent.tool.ToolSpecifications;

import io.agentscope.core.tool.Toolkit;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

class VideoToolRegistrationTest {
    @Test
    void disconnectedChatCannotTurnAnAcceptedTaskIntoARetryableToolFailure() {
        var videos = mock(VideoV2Service.class);
        var sessions = mock(ChatSessionService.class);
        when(videos.submit(1L, "s", "generation", "test", null, null, null, null, List.of(), false))
                .thenReturn(new SubmitGenerationResult("accepted", "v2:provider", 9L));
        when(sessions.getOwnedMessage(1L, "s", 9L))
                .thenThrow(new IllegalStateException("disconnected"));
        var actions =
                new VideoToolActions(
                        videos, sessions, mock(ChatStreamEventBridge.class), new ObjectMapper());
        assertTrue(
                actions.submit(
                                1L,
                                "s",
                                "memory",
                                "test",
                                "[]",
                                "generation",
                                null,
                                null,
                                null,
                                null)
                        .contains("accepted"));
        verify(videos, times(1))
                .submit(1L, "s", "generation", "test", null, null, null, null, List.of(), false);
    }

    @Test
    void malformedReferenceObjectCannotSilentlyDropTheUsersInputs() {
        var videos = mock(VideoV2Service.class);
        var actions =
                new VideoToolActions(
                        videos,
                        mock(ChatSessionService.class),
                        mock(ChatStreamEventBridge.class),
                        new ObjectMapper());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        actions.submit(
                                1L, "s", null, "test", "{}", "generation", null, null, null, null));
        verifyNoInteractions(videos);
    }

    @Test
    void bothAgentRuntimesExposeTheSameVideoOperations() {
        var actions = mock(VideoToolActions.class);
        var specifications =
                ToolSpecifications.toolSpecificationsFrom(
                        new VideoGenerationTool(actions, new ChatMemoryIdFactory()));
        var expected =
                Set.of(
                        "generate_video",
                        "query_video_task",
                        "list_video_tasks",
                        "cancel_video_task",
                        "delete_video_task_record",
                        "regenerate_video2_k",
                        "list_video_inputs");
        assertEquals(
                expected, specifications.stream().map(s -> s.name()).collect(Collectors.toSet()));
        var toolkit = new Toolkit();
        toolkit.registerTool(new AgentScopeVideoTool(actions));
        assertTrue(toolkit.getToolNames().containsAll(expected));
    }
}
