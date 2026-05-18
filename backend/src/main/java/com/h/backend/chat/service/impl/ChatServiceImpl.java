package com.h.backend.chat.service.impl;

import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.service.ChatService;
import com.h.backend.common.exception.BusinessException;
import dev.langchain4j.model.ModelDisabledException;
import org.springframework.stereotype.Service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class ChatServiceImpl implements ChatService {

    private final HAssistant hAssistant;

    public ChatServiceImpl(HAssistant hAssistant) {
        this.hAssistant = hAssistant;
    }

    @Override
    public String streamChat(String sessionId, String userMessage, Consumer<String> onChunk) {
        StringBuilder replyBuilder = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // h-agent的runtime loop
        hAssistant.chat(sessionId, userMessage)
                .onPartialResponse(chunk -> {
                    replyBuilder.append(chunk);
                    onChunk.accept(chunk);
                })
                .onCompleteResponse(ignored -> latch.countDown())
                .onError(error -> {
                    errorRef.set(error);
                    latch.countDown();
                })
                .start();

        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50002, "AI 响应被中断");
        }

        Throwable error = errorRef.get();
        if (error != null) {
            if (error instanceof ModelDisabledException) {
                throw new BusinessException(50001, "AI 服务未配置 OPENAI_API_KEY");
            }
            throw new BusinessException(50003, "AI 服务调用失败");
        }

        String reply = replyBuilder.toString();
        if (reply.isBlank()) {
            throw new BusinessException(50004, "AI 未返回有效内容");
        }
        return reply;
    }
}