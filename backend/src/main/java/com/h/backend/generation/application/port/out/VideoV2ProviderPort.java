package com.h.backend.generation.application.port.out;

import com.h.backend.generation.domain.model.VideoV2Spec;

import java.util.List;
import java.util.Map;

public interface VideoV2ProviderPort {
    String create(VideoV2Spec spec, List<Map<String, Object>> content);

    TaskInfo inspect(String providerId);

    List<TaskInfo> listOwned(
            List<String> providerIds, int page, int size, String status, String model, String type);

    Removal remove(String providerId);

    record TaskInfo(
            String taskId,
            String status,
            String taskType,
            String prompt,
            String url,
            String error) {}

    record Removal(String action, String status) {}
}
