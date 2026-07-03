package com.h.otheragents.a2a.export;

public record A2AAgentExport(
        String id,
        Object agentBean,
        Class<?> agentInterface,
        A2AExportMethod method
) {
}
