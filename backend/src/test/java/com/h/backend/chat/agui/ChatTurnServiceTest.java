package com.h.backend.chat.agui;

import com.h.backend.chat.application.*;
import com.h.backend.chat.application.agui.*;
import com.h.backend.chat.domain.agent.*;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionMessageEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.*;
import com.h.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatTurnServiceTest {
    ChatSessionMessageMapper messages = mock(ChatSessionMessageMapper.class);
    AguiRunSnapshotMapper snapshots = mock(AguiRunSnapshotMapper.class);
    ChatSessionMapper roots = mock(ChatSessionMapper.class);
    ChatMemorySnapshotService memory = mock(ChatMemorySnapshotService.class);
    ChatTurnService turns = new ChatTurnService(messages, snapshots, roots, memory,
            new ChatMemoryIdFactory(), JsonMapper.builder().build());
    AgentDefinition agent = new AgentDefinition("standard-chat", "助手", "通用", List.of(), "", null,
            AgentRuntimeType.STANDARD_STREAMING_CHAT, true);

    @Test
    void editKeepsOriginalIdentityAndSupersedesOldReplyWithoutDeletingResources() {
        var user = user(8L);
        when(messages.selectLatestUser("thread")).thenReturn(user);
        when(messages.selectVisibleHistory("thread")).thenReturn(List.of(user));
        when(snapshots.list(1L, "thread")).thenReturn(List.of());
        var operation = new ChatTurnOperation("edit", 8L, null, "new-run", "", null);
        assertEquals("改正后的文字", turns.prepare(1L, "thread", "1:2:thread", agent, operation, "改正后的文字"));
        verify(messages).reviseUser(8L, "改正后的文字");
        verify(messages).supersedeAfter("thread", 4);
        verify(roots).reviseLastUser(20L, "改正后的文字");
        verify(messages, never()).deleteById(anyLong());
        verify(memory).cacheMemory(any(), eq(List.of()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PAUSED", "FAILED"})
    void continuationCarriesExistingToolResultsWithoutAddingAUserMessage(String status) {
        var user = user(8L);
        var source = new AguiRunSnapshotMapper.Snapshot("paused", "thread", 1L, "hash", status, """
                {"continuable":true,"continuationContext":"更早暂停前的任务 first-task","anchorMessageId":"8","partialMessages":[{"id":"answer","content":"前半段"}],
                "activities":[{"content":{"steps":[{"name":"generateImage","result":"task-existing"}]}}]}
                """, null);
        when(messages.selectLatestUser("thread")).thenReturn(user);
        when(messages.selectVisibleHistory("thread")).thenReturn(List.of(user));
        var ancestor = new AguiRunSnapshotMapper.Snapshot("older-paused", "thread", 1L, "hash", "PAUSED",
                "{\"anchorMessageId\":\"8\",\"partialMessages\":[],\"activities\":[]}", null);
        when(snapshots.list(1L, "thread")).thenReturn(List.of(ancestor, source));
        when(snapshots.find(1L, "paused")).thenReturn(source);
        String prompt = turns.prepare(1L, "thread", "1:2:thread", agent,
                new ChatTurnOperation("continue", 8L, "paused", "new-run", "前半段", "answer"), "原文字");
        assertTrue(prompt.contains("原文字"));
        assertTrue(prompt.contains("前半段"));
        assertTrue(prompt.contains("task-existing"));
        assertTrue(prompt.contains("first-task"));
        verify(messages, never()).reviseUser(anyLong(), anyString());
        verify(messages, never()).supersedeAfter(anyString(), anyInt());
        verify(snapshots).update(eq("paused"), eq(1L), eq("SUPERSEDED"), contains("\"partialMessages\":[]"));
        verify(snapshots).update(eq("older-paused"), eq(1L), eq("SUPERSEDED"), anyString());
    }

    @Test
    void editingHarnessReplacesOldContextAndSummaryBeforeTheNewPrompt() {
        var harness = mock(io.agentscope.harness.agent.HarnessAgent.class);
        var delegate = mock(io.agentscope.core.ReActAgent.class);
        var store = mock(io.agentscope.core.state.AgentStateStore.class);
        when(harness.getDelegate()).thenReturn(delegate);
        when(delegate.getStateStore()).thenReturn(store);
        var old = io.agentscope.core.state.AgentState.builder().userId("1").sessionId("thread")
                .summary("错误的旧问题摘要").build();
        when(delegate.getAgentState("1", "thread")).thenReturn(old);
        var definition = new AgentDefinition("harness", "助手", "通用", List.of(), "", harness,
                AgentRuntimeType.HARNESS_STREAMING, true);
        var latest = user(8L);
        var previous = user(2L); previous.setSequenceNo(1); previous.setContentText("前一轮的问题");
        when(messages.selectLatestUser("thread")).thenReturn(latest);
        when(messages.selectVisibleHistory("thread")).thenReturn(List.of(previous, latest));
        turns.prepare(1L, "thread", "unused", definition,
                new ChatTurnOperation("edit", 8L, null, "new-run", "", null), "改正文字");
        var state = org.mockito.ArgumentCaptor.forClass(io.agentscope.core.state.AgentState.class);
        verify(store).save(eq("1"), eq("thread"), eq("agent_state"), state.capture());
        assertEquals("", state.getValue().getSummary());
        assertEquals(List.of("前一轮的问题"), state.getValue().getContext().stream()
                .map(io.agentscope.core.message.Msg::getTextContent).toList());
        verifyNoInteractions(memory);
    }

    @Test
    void sendingNewInputKeepsThePartialAnswerInContextButConsumesContinue() {
        var source = new AguiRunSnapshotMapper.Snapshot("paused", "thread", 1L, "hash", "PAUSED",
                "{\"partialMessages\":[{\"id\":\"answer\",\"content\":\"半截回答\"}],\"activities\":[]}", null);
        when(snapshots.list(1L, "thread")).thenReturn(List.of(source));
        when(messages.selectVisibleHistory("thread")).thenReturn(List.of(user(8L)));
        assertEquals("新问题", turns.prepare(1L, "thread", "1:2:thread", agent, null, "新问题"));
        verify(memory).cacheMemory(any(), eq(List.of(dev.langchain4j.data.message.UserMessage.from("原文字"),
                dev.langchain4j.data.message.AiMessage.from("半截回答"))));
        verify(snapshots).update(eq("paused"), eq(1L), eq("CANCELLED"), contains("半截回答"));
    }

    @Test
    void staleEditCannotRewriteAnotherTurnOrItsModelContext() {
        when(messages.selectLatestUser("thread")).thenReturn(user(9L));
        assertThrows(BusinessException.class, () -> turns.prepare(1L, "thread", "1:2:thread", agent,
                new ChatTurnOperation("edit", 8L, null, "new-run", "", null), "文字"));
        verify(messages, never()).supersedeAfter(anyString(), anyInt());
        verifyNoInteractions(memory);
    }

    private ChatSessionMessageEntity user(Long id) {
        var row = new ChatSessionMessageEntity();
        row.setId(id); row.setUserId(1L); row.setSessionId("thread"); row.setSessionRecordId(20L);
        row.setSequenceNo(4); row.setRoleCode("user"); row.setContentText("原文字");
        return row;
    }
}
