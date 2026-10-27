package com.h.backend.generation.application.service;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.generation.application.result.SubmitGenerationResult;
import com.h.backend.generation.domain.model.MusicGenerationSpec;
import com.h.backend.generation.interfaces.tool.MusicGenerationTool;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicToolRegistrationTest {
    @Test
    void exposesGeneralAssistantOperationsAndPreservesAcceptedTaskOnDisconnect() {
        var music = mock(MusicGenerationService.class);
        var sessions = mock(ChatSessionService.class);
        when(music.submit(1L, "session", new MusicGenerationSpec("女声", "原文", null)))
                .thenReturn(new SubmitGenerationResult("song", "music:song", 9L));
        when(sessions.getOwnedMessage(1L, "session", 9L)).thenThrow(new IllegalStateException("disconnected"));
        var tool = new MusicGenerationTool(music, new ChatMemoryIdFactory(), sessions,
                mock(ChatStreamEventBridge.class), new ObjectMapper());
        assertEquals(Set.of("generate_music", "query_music_task"), ToolSpecifications.toolSpecificationsFrom(tool)
                .stream().map(spec -> spec.name()).collect(Collectors.toSet()));
        assertTrue(tool.generateMusic("1:2:session", "原文", "女声", null).contains("song"));
        verify(music, times(1)).submit(1L, "session", new MusicGenerationSpec("女声", "原文", null));
    }
}
