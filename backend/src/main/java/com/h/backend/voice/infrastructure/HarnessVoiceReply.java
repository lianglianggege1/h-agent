package com.h.backend.voice.infrastructure;

import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.domain.agent.HarnessRuntime;
import com.h.backend.chat.infrastructure.config.ChatModelEnvironment;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.application.VoiceReply;
import com.h.backend.voice.application.VoiceReplyContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class HarnessVoiceReply implements VoiceReply {
    private final HarnessRuntime harnessRuntime;
    private final HarnessAgent phoneAgent;
    private final ChatModelEnvironment environment;

    public HarnessVoiceReply(
            HarnessRuntime harnessRuntime,
            @Qualifier("phoneHarnessAgent") HarnessAgent phoneAgent
    ) {
        this.harnessRuntime = harnessRuntime;
        this.phoneAgent = phoneAgent;
        this.environment = ChatModelEnvironment.load(Path.of("")).orElse(null);
    }

    @Override
    public String modelName() {
        if (environment == null) throw new BusinessException(50300, "Harness 模型尚未配置");
        return environment.modelName();
    }

    @Override
    public boolean supports(String agentId) {
        return ChatAgentIds.HARNESS.equals(agentId);
    }

    @Override
    public Execution prepare(VoiceReplyContext ctx, Consumer<String> text, Consumer<String> terminal) {
        var runtimeContext = RuntimeContext.builder()
                .userId("phone-" + ctx.userId())
                // ReActAgent persists state automatically. A run-scoped identity makes drafts
                // unreachable from every later turn while committed history is supplied below.
                .sessionId("phone-run-" + ctx.runId())
                .build();

        var started = new AtomicBoolean();
        var cancelled = new AtomicBoolean();
        var emittedText = new AtomicBoolean();
        var resultText = new AtomicReference<String>();
        var subscription = new AtomicReference<Disposable>();

        return new Execution() {
            @Override
            public void start() {
                if (!started.compareAndSet(false, true)) return;
                try {
                    var events = harnessRuntime.streamParent(phoneAgent, committedMessages(ctx), runtimeContext);
                    subscription.set(events.subscribe(event -> {
                        if (cancelled.get()) return;
                        onEvent(event, text, emittedText, resultText);
                    }, error -> terminal.accept("FAILED"), () -> {
                        if (cancelled.get()) return;
                        if (!emittedText.get() && resultText.get() != null) {
                            text.accept(resultText.get());
                        }
                        terminal.accept("GENERATED");
                    }));
                } catch (RuntimeException ex) {
                    terminal.accept("FAILED");
                }
            }

            @Override
            public void cancel() {
                if (!cancelled.compareAndSet(false, true)) return;
                var sub = subscription.get();
                if (sub != null && !sub.isDisposed()) sub.dispose();
                // Disposing a Reactor subscription does not invoke onComplete.  The turn
                // module needs an explicit terminal fact to settle the interrupted turn.
                terminal.accept("CANCELLED");
            }
        };
    }

    @Override
    public Execution prepare(String systemPrompt, java.util.List<dev.langchain4j.data.message.ChatMessage> history, Consumer<String> text, Consumer<String> terminal) {
        throw new UnsupportedOperationException("HarnessVoiceReply requires full context");
    }

    private void onEvent(AgentEvent event, Consumer<String> text, AtomicBoolean emittedText, AtomicReference<String> resultText) {
        if (!isParent(event)) return;
        if (event instanceof TextBlockDeltaEvent textEvent) {
            emittedText.set(true);
            text.accept(textEvent.getDelta());
        } else if (event instanceof AgentResultEvent resultEvent) {
            Msg result = resultEvent.getResult();
            if (result != null && result.getTextContent() != null && !result.getTextContent().isBlank()) {
                resultText.set(result.getTextContent());
            }
        }
    }

    private boolean isParent(AgentEvent event) {
        return event.getSource() == null || event.getSource().isBlank();
    }

    private List<Msg> committedMessages(VoiceReplyContext ctx) {
        List<Msg> messages = new ArrayList<>();
        if (ctx.systemPrompt() != null && !ctx.systemPrompt().isBlank()) {
            messages.add(Msg.builder()
                    .name("call_brief")
                    .role(MsgRole.SYSTEM)
                    .textContent(ctx.systemPrompt())
                    .build());
        }
        for (var message : ctx.history()) {
            switch (message.type()) {
                case USER -> messages.add(Msg.builder()
                        .name("customer")
                        .role(MsgRole.USER)
                        .textContent(((dev.langchain4j.data.message.UserMessage) message).singleText())
                        .build());
                case AI -> {
                    String content = ((dev.langchain4j.data.message.AiMessage) message).text();
                    if (content != null && !content.isBlank()) {
                        messages.add(Msg.builder()
                                .name("assistant")
                                .role(MsgRole.ASSISTANT)
                                .textContent(content)
                                .build());
                    }
                }
                default -> { /* PHONE never imports system/tool messages from product history. */ }
            }
        }
        if (ctx.userMessageId() == null || messages.isEmpty()) {
            messages.add(Msg.builder()
                    .name("customer")
                    .role(MsgRole.USER)
                    .textContent(ctx.userMessage())
                    .build());
        }
        return List.copyOf(messages);
    }
}
