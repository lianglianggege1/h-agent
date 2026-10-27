package com.h.backend.generation.application.port.out;

import com.h.backend.generation.domain.model.VideoV2Spec;

import java.util.List;
import java.util.Map;

public interface VideoInputResolver {
    List<Map<String, Object>> resolve(Long userId, VideoV2Spec spec);
}
