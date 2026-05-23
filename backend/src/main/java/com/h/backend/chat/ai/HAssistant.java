package com.h.backend.chat.ai;

import com.h.backend.chat.guardrail.ViolenceInputGuardrail;
import com.h.backend.chat.guardrail.ViolenceOutputGuardrail;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.guardrail.InputGuardrails;
import dev.langchain4j.service.guardrail.OutputGuardrails;

public interface HAssistant {

    @InputGuardrails({ViolenceInputGuardrail.class})
    @OutputGuardrails({ViolenceOutputGuardrail.class})
    TokenStream streamChat(@MemoryId String sessionId, @UserMessage String userMessage);


}
