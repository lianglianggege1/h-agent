package com.h.otheragents.a2a.server;

import com.h.otheragents.a2a.export.A2AAgentExport;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.util.List;

public class LangChain4jAgentMethodInvoker {

    public String invoke(A2AAgentExport export, A2AInvocationContext context, List<String> textParts) {
        Object[] args = new Object[export.method().method().getParameterCount()];
        Parameter[] parameters = export.method().method().getParameters();
        int textIndex = 0;
        for (int i = 0; i < parameters.length; i++) {
            if (export.method().memoryIdParameterIndex() != null && export.method().memoryIdParameterIndex() == i) {
                args[i] = context.memoryKey();
            } else {
                if (textIndex >= textParts.size()) {
                    throw new IllegalArgumentException("message parts must contain required text");
                }
                args[i] = textParts.get(textIndex++);
            }
        }
        try {
            Object result = export.method().method().invoke(export.agentBean(), args);
            return result == null ? "" : result.toString();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("A2A agent method is not accessible: " + export.id(), e);
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("A2A agent method failed: " + export.id(), target);
        }
    }
}
