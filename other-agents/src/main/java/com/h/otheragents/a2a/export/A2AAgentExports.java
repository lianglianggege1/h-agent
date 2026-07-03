package com.h.otheragents.a2a.export;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import dev.langchain4j.service.MemoryId;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

public class A2AAgentExports {

    private final List<A2AAgentExport> exports;

    private A2AAgentExports(List<A2AAgentExport> exports) {
        this.exports = List.copyOf(exports);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<A2AAgentExport> list() {
        return exports;
    }

    public static class Builder {

        private final List<A2AAgentExport> exports = new ArrayList<>();

        public Builder export(String id, Object agentBean, Class<?> agentInterface, String methodName) {
            Method method = findMethod(agentInterface, methodName);
            exports.add(new A2AAgentExport(id, agentBean, agentInterface, exportMethod(method, id)));
            return this;
        }

        public A2AAgentExports build() {
            return new A2AAgentExports(exports);
        }

        private static Method findMethod(Class<?> agentInterface, String methodName) {
            Method[] methods = agentInterface.getMethods();
            Method found = null;
            for (Method method : methods) {
                if (method.getName().equals(methodName)) {
                    if (found != null) {
                        throw new IllegalArgumentException("ambiguous A2A export method: " + methodName);
                    }
                    found = method;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("A2A export method not found: " + methodName);
            }
            return found;
        }

        private static A2AExportMethod exportMethod(Method method, String fallbackName) {
            List<String> inputKeys = new ArrayList<>();
            Integer memoryIdParameterIndex = null;
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                V variable = parameters[i].getAnnotation(V.class);
                if (variable != null) {
                    inputKeys.add(variable.value());
                }
                if (parameters[i].getAnnotation(MemoryId.class) != null) {
                    memoryIdParameterIndex = i;
                }
            }

            Agent agent = method.getAnnotation(Agent.class);
            String outputKey = agent != null && !agent.outputKey().isBlank() ? agent.outputKey() : "response";
            String publicName = agent != null && !agent.name().isBlank() ? agent.name() : fallbackName;
            String publicDescription = agent != null && !agent.description().isBlank() ? agent.description() : fallbackName;
            return new A2AExportMethod(method, List.copyOf(inputKeys), memoryIdParameterIndex, outputKey, publicName, publicDescription);
        }
    }
}
