package com.h.otheragents.a2a.export;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class A2AAgentExportRegistry {

    private final Map<String, A2AAgentExport> exportsById;

    public A2AAgentExportRegistry(A2AAgentExports exports) {
        Map<String, A2AAgentExport> map = new LinkedHashMap<>();
        for (A2AAgentExport export : exports.list()) {
            A2AAgentExport previous = map.putIfAbsent(export.id(), export);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate A2A agent id: " + export.id());
            }
        }
        this.exportsById = Map.copyOf(map);
    }

    public A2AAgentExport require(String agentId) {
        A2AAgentExport export = exportsById.get(agentId);
        if (export == null) {
            throw new IllegalArgumentException("A2A agent not found: " + agentId);
        }
        return export;
    }

    public Collection<A2AAgentExport> list() {
        return exportsById.values();
    }
}
