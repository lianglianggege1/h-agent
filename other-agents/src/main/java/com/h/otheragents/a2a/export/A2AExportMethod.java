package com.h.otheragents.a2a.export;

import java.lang.reflect.Method;
import java.util.List;

public record A2AExportMethod(
        Method method,
        List<String> inputKeys,
        Integer memoryIdParameterIndex,
        String outputKey,
        String publicName,
        String publicDescription
) {
}
