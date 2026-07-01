package com.h.backend.chat.infrastructure.ai.carrentalassistant.services;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.MemoryId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CarRentalAssistantMemoryIdTest {

    @Test
    void aiServicesWithScopedChatMemoryProviderDeclareMemoryId() {
        List<Class<?>> aiServices = List.of(
                CustomerInfoExtractionService.class
        );

        for (Class<?> aiService : aiServices) {
            for (Method agentMethod : agentMethods(aiService)) {
                assertTrue(
                        hasMemoryId(agentMethod),
                        () -> aiService.getSimpleName() + "." + agentMethod.getName()
                                + " must declare @MemoryId because AgentConfig configures scoped chatMemoryProvider"
                );
            }
        }
    }

    private static List<Method> agentMethods(Class<?> aiService) {
        return Stream.of(aiService.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Agent.class))
                .toList();
    }

    private static boolean hasMemoryId(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(MemoryId.class)) {
                return true;
            }
        }
        return false;
    }
}
