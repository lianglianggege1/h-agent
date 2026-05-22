package com.h.backend.chat.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;

public class ViolenceInputGuardrail implements InputGuardrail {

    @Override
    public InputGuardrailResult validate(InputGuardrailRequest request) {
        UserMessage userMessage = request.userMessage();
        if (userMessage.singleText().contains("杀人")) {
            return this.failure("系统提醒您：请勿使用暴力");
        } else {
            return InputGuardrailResult.success();
        }
    }
}
