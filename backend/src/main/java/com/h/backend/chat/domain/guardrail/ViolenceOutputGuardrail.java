package com.h.backend.chat.domain.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

@Slf4j
public class ViolenceOutputGuardrail implements OutputGuardrail {

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        String text = responseFromLLM.text();
        String thinking = responseFromLLM.thinking();
        log.info("text: {}, thinking: {}", text, thinking);
        if (text != null && text.contains("李白")) {
            return this.failure("李白-本系统无法评价");
        }else {
            return this.success();
        }
    }
}
