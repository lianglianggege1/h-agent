package com.h.backend.generation.application.service;

import com.h.capabilities.generation.application.service.VideoV2Service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.capabilities.chat.domain.memory.ChatMemoryIdFactory;
import com.h.capabilities.generation.application.result.SubmitGenerationResult;
import com.h.capabilities.generation.domain.model.VideoV2Spec;
import com.h.capabilities.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.capabilities.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.capabilities.memory.application.AgentMemoryContext;
import dev.langchain4j.invocation.InvocationParameters;
import io.agentscope.core.agent.RuntimeContext;
import com.h.backend.generation.interfaces.tool.*;
import com.h.capabilities.generation.interfaces.tool.*;

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
                                null,
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
                                1L, "s", null, null, "test", "{}", "generation", null, null, null, null));
        verifyNoInteractions(videos);
    }

    @Test
    void readsAndSubmitsMediaFromTheTrustedTurnInBothRuntimes() {
        var sessions = mock(ChatSessionService.class);
        var videos = mock(VideoV2Service.class);
        var actions = new VideoToolActions(videos, sessions, mock(ChatStreamEventBridge.class), new ObjectMapper());
        var document = new ChatMessageResourceDto("pdf", "FILE", "ATTACHMENT", null, null,
                "source.pdf", "application/pdf", null, null, null);
        var video = new ChatMessageResourceDto("clip", "FILE", "REFERENCE", null, null,
                "clip.mp4", "video/mp4", null, null, null);
        var audio = new ChatMessageResourceDto("sound", "FILE", "REFERENCE", null, null,
                "sound.wav", "audio/wav", null, null, null);
        when(sessions.getOwnedMessage(1L, "s", 42L)).thenReturn(new ChatSessionMessageDto(
                "42", "user", "USER", "test", null, List.of(document, video, audio), null));
        var inputs = List.of(new VideoV2Spec.Input("clip", "reference_video"),
                new VideoV2Spec.Input("sound", "reference_audio"));
        when(videos.submit(1L, "s", "generation", "test", null, null, null, null, inputs, false))
                .thenReturn(new SubmitGenerationResult("accepted", "provider", null));
        var identity = new AgentMemoryContext(1L, "s", "s", "test", "test", 42L, "exec");
        var parameters = InvocationParameters.from(AgentMemoryContext.KEY, identity);
        var runtime = RuntimeContext.builder().userId("1").sessionId("s").put(AgentMemoryContext.class, identity).build();
        var langchain = new VideoGenerationTool(actions, new ChatMemoryIdFactory());
        var harness = new AgentScopeVideoTool(actions);

        String listed = langchain.listVideoInputs("1:2:s", parameters);
        assertTrue(listed.contains("video/mp4"));
        assertTrue(listed.contains("audio/wav"));
        assertFalse(listed.contains("source.pdf"));
        assertEquals(listed, harness.listVideoInputs(runtime));
        assertEquals("[]", langchain.listVideoInputs("1:2:other", parameters));
        assertTrue(langchain.generateVideo("1:2:s", parameters, "test", null, null, null, null, null, null)
                .contains("accepted"));
        assertTrue(harness.generateVideo(runtime, "test", null, null, null, null, null, null).contains("accepted"));
        verify(videos, times(2)).submit(1L, "s", "generation", "test", null, null, null, null, inputs, false);
        verify(sessions, times(4)).getOwnedMessage(1L, "s", 42L);
        verifyNoMoreInteractions(sessions);
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
