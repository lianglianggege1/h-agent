package com.h.backend.chat.application;

import com.h.backend.chat.interfaces.dto.CreateSystemPromptRequest;
import com.h.backend.chat.interfaces.dto.SystemPromptResponse;
import com.h.backend.chat.interfaces.dto.UpdateSystemPromptRequest;

import java.util.List;

public interface SystemPromptService {

    List<SystemPromptResponse> listPrompts(Long userId);

    SystemPromptResponse createPrompt(Long userId, CreateSystemPromptRequest request);

    SystemPromptResponse updatePrompt(Long userId, Long promptId, UpdateSystemPromptRequest request);

    void deletePrompt(Long userId, Long promptId);

    SystemPromptResponse setDefaultPrompt(Long userId, Long promptId);

    Long resolvePromptId(Long userId, Long promptId);

    String getSystemPrompt(Long userId, Long promptId);
}
