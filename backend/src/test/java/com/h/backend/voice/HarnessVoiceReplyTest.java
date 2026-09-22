package com.h.backend.voice;

import com.h.backend.chat.domain.agent.HarnessRuntime;
import com.h.backend.voice.application.VoiceReplyContext;
import com.h.backend.voice.infrastructure.HarnessVoiceReply;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarnessVoiceReplyTest {

    @Test
    void usesRestrictedAgentAndOnlyCommittedHistory() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessAgent phoneAgent = mock(HarnessAgent.class);
        Object ordinaryAgent = new Object();
        when(runtime.streamParent(eq(phoneAgent), any(List.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        var reply = new HarnessVoiceReply(runtime, phoneAgent);
        var terminals = new ArrayList<String>();
        var context = new VoiceReplyContext(
                7L, "session-private", null, "harness", ordinaryAgent,
                "本通电话沟通目标：确认客户预约时间。",
                List.of(
                        UserMessage.from("您好"),
                        AiMessage.from("已经播放的答复"),
                        UserMessage.from("继续")
                ),
                "继续", 42L, 9L, "model"
        );

        reply.prepare(context, ignored -> {}, terminals::add).start();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<RuntimeContext> runtimeContext = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(runtime).streamParent(eq(phoneAgent), messages.capture(), runtimeContext.capture());
        assertThat(messages.getValue())
                .extracting(Msg::getRole, Msg::getTextContent)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MsgRole.SYSTEM, "本通电话沟通目标：确认客户预约时间。"),
                        org.assertj.core.groups.Tuple.tuple(MsgRole.USER, "您好"),
                        org.assertj.core.groups.Tuple.tuple(MsgRole.ASSISTANT, "已经播放的答复"),
                        org.assertj.core.groups.Tuple.tuple(MsgRole.USER, "继续")
                );
        assertThat(runtimeContext.getValue().getUserId()).isEqualTo("phone-7");
        assertThat(runtimeContext.getValue().getSessionId()).isEqualTo("phone-run-42");
        assertThat(terminals).containsExactly("GENERATED");
    }

    @Test
    void cancelAlwaysPublishesTerminalFact() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessAgent phoneAgent = mock(HarnessAgent.class);
        when(runtime.streamParent(eq(phoneAgent), any(List.class), any(RuntimeContext.class)))
                .thenReturn(Flux.never());
        var reply = new HarnessVoiceReply(runtime, phoneAgent);
        var terminals = new ArrayList<String>();
        var context = new VoiceReplyContext(
                7L, "session-private", null, "harness", new Object(), "",
                List.of(), "开始对话", 43L, null, "model"
        );

        var execution = reply.prepare(context, ignored -> {}, terminals::add);
        execution.start();
        execution.cancel();

        assertThat(terminals).containsExactly("CANCELLED");
    }
}
