package com.h.capabilities.generation.application.port.out;

import com.h.capabilities.generation.domain.model.VideoV2Spec;

import java.util.List;
import java.util.Map;

public interface VideoInputResolver {
    List<Map<String, Object>> resolve(Long userId, VideoV2Spec spec);
}
