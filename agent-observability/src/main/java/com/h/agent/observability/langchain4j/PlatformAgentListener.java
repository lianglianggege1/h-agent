package com.h.agent.observability.langchain4j;

import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.BeforeAgentToolExecution;

/**
 * Combines the product listener (frontend step projection) with the observation listener.
 * Both see the exact same events; neither can change the other's behavior.
 */
public final class PlatformAgentListener implements AgentListener {

    private final AgentListener productListener;
    private final AgentListener observationListener;

    public PlatformAgentListener(AgentListener productListener, AgentListener observationListener) {
        this.productListener = productListener;
        this.observationListener = observationListener;
    }

    @Override
    public void beforeAgentInvocation(AgentRequest request) {
        productListener.beforeAgentInvocation(request);
        observationListener.beforeAgentInvocation(request);
    }

    @Override
    public void afterAgentInvocation(AgentResponse response) {
        productListener.afterAgentInvocation(response);
        observationListener.afterAgentInvocation(response);
    }

    @Override
    public void onAgentInvocationError(AgentInvocationError error) {
        productListener.onAgentInvocationError(error);
        observationListener.onAgentInvocationError(error);
    }

    @Override
    public void afterAgenticScopeCreated(AgenticScope scope) {
        productListener.afterAgenticScopeCreated(scope);
        observationListener.afterAgenticScopeCreated(scope);
    }

    @Override
    public void beforeAgenticScopeDestroyed(AgenticScope scope) {
        productListener.beforeAgenticScopeDestroyed(scope);
        observationListener.beforeAgenticScopeDestroyed(scope);
    }

    @Override
    public void beforeAgentToolExecution(BeforeAgentToolExecution execution) {
        productListener.beforeAgentToolExecution(execution);
        observationListener.beforeAgentToolExecution(execution);
    }

    @Override
    public void afterAgentToolExecution(AfterAgentToolExecution execution) {
        productListener.afterAgentToolExecution(execution);
        observationListener.afterAgentToolExecution(execution);
    }

    @Override
    public boolean inheritedBySubagents() {
        return true;
    }
}
