package com.h.agent.observability;

public final class HAttrs {

    public static final String SCHEMA_VERSION = "h.schema_version";
    public static final String KIND = "h.kind";
    public static final String RUNTIME = "h.runtime";
    public static final String AGENT_ID = "h.agent_id";
    public static final String AGENT_SESSION_ID = "h.agent_session_id";
    public static final String ROOT_RUN_ID = "h.root_run_id";
    public static final String ENTRY_KIND = "h.entry_kind";
    public static final String TOOL_NAME = "h.tool_name";
    public static final String OUTCOME = "h.outcome";
    public static final String CONTENT_CAPTURE_MODE = "h.content.capture_mode";
    public static final String CONTENT_CAPTURE_STATE = "h.content.capture_state";

    public static final String LANGFUSE_SESSION_ID = "langfuse.session.id";
    public static final String LANGFUSE_USER_ID = "langfuse.user.id";
    public static final String LANGFUSE_TRACE_NAME = "langfuse.trace.name";
    public static final String LANGFUSE_TRACE_TAGS = "langfuse.trace.tags";
    public static final String LANGFUSE_OBSERVATION_TYPE = "langfuse.observation.type";
    public static final String LANGFUSE_OBSERVATION_USAGE_DETAILS = "langfuse.observation.usage_details";

    public static final String GEN_AI_SYSTEM = "gen_ai.system";
    public static final String GEN_AI_REQUEST_MODEL = "gen_ai.request.model";
    public static final String GEN_AI_RESPONSE_MODEL = "gen_ai.response.model";
    public static final String GEN_AI_USAGE_PROMPT_TOKENS = "gen_ai.usage.prompt_tokens";
    public static final String GEN_AI_USAGE_COMPLETION_TOKENS = "gen_ai.usage.completion_tokens";
    public static final String GEN_AI_USAGE_TOTAL_TOKENS = "gen_ai.usage.total_tokens";

    public static final String INPUT = "langfuse.observation.input";
    public static final String OUTPUT = "langfuse.observation.output";

    public static final String BAGGAGE_SESSION_ID = "langfuse.session.id";
    public static final String BAGGAGE_USER_ID = "langfuse.user.id";
    public static final String BAGGAGE_TRACE_NAME = "langfuse.trace.name";
    public static final String BAGGAGE_TRACE_TAGS = "langfuse.trace.tags";

    private HAttrs() {
    }
}
