package com.h.agent.observability;

import java.util.Map;

public record ObservationSpec(
        String name,
        HObsKind kind,
        String runtime,
        Map<String, String> attributes
) {

    public static ObservationSpec of(String name, HObsKind kind, String runtime) {
        return new ObservationSpec(name, kind, runtime, Map.of());
    }

    public static ObservationSpec of(String name, HObsKind kind, String runtime, Map<String, String> attributes) {
        return new ObservationSpec(name, kind, runtime, attributes);
    }
}
