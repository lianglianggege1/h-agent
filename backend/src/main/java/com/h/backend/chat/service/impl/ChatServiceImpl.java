package com.h.backend.chat.service.impl;

import com.h.backend.chat.service.ChatService;
import com.h.backend.common.exception.BusinessException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ModelDisabledException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class ChatServiceImpl implements ChatService {

    private static final SystemMessage SYSTEM_MESSAGE = SystemMessage.from(
            """
            你是 H-Agent 的 AI 助手。
            请使用简洁、自然、友好的中文回答。
            如果用户的问题信息不足，先给出最小可执行建议，再提示可以补充的信息。
            """
    );

    private final StreamingChatModel streamingChatModel;
    private final Map<Long, ChatMemory> memoryByUser = new ConcurrentHashMap<>();

    public ChatServiceImpl(StreamingChatModel streamingChatModel) {
        this.streamingChatModel = streamingChatModel;
    }

    @Override
    public String streamChat(Long userId, String userMessage, Consumer<String> onChunk) {
        ChatMemory memory = memoryByUser.computeIfAbsent(
                userId,
                ignored -> MessageWindowChatMemory.withMaxMessages(20)
        );

        if (memory.messages().isEmpty()) {
            memory.add(SYSTEM_MESSAGE);
        }

        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>(memory.messages());
        UserMessage userMessageObject = UserMessage.from(userMessage);
        messages.add(userMessageObject);

        StringBuilder replyBuilder = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChatResponse> completeResponseRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try {
            streamingChatModel.chat(messages, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    replyBuilder.append(partialResponse);
                    onChunk.accept(partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    completeResponseRef.set(completeResponse);
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            });

            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50002, "AI 响应被中断");
        } catch (ModelDisabledException ex) {
            throw new BusinessException(50001, "AI 服务未配置 OPENAI_API_KEY");
        }

        Throwable error = errorRef.get();
        if (error != null) {
            if (error instanceof ModelDisabledException) {
                throw new BusinessException(50001, "AI 服务未配置 OPENAI_API_KEY");
            }
            throw new BusinessException(50003, "AI 服务调用失败");
        }

        ChatResponse completeResponse = completeResponseRef.get();
        if (completeResponse == null || completeResponse.aiMessage() == null) {
            throw new BusinessException(50004, "AI 未返回有效内容");
        }

        AiMessage aiMessage = completeResponse.aiMessage();
        memory.add(userMessageObject);
        memory.add(aiMessage);
        return aiMessage.text();
    }
}
