package com.h.backend.chat.application.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.h.backend.chat.interfaces.dto.CreateSystemPromptRequest;
import com.h.backend.chat.interfaces.dto.SystemPromptResponse;
import com.h.backend.chat.interfaces.dto.UpdateSystemPromptRequest;
import com.h.backend.chat.infrastructure.persistence.entity.SystemPromptEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.SystemPromptMapper;
import com.h.backend.chat.application.SystemPromptService;
import com.h.backend.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SystemPromptServiceImpl implements SystemPromptService {

    private static final String DEFAULT_PROMPT_NAME = "默认助手";
    private static final String DEFAULT_PROMPT_CONTENT = """
            你是 H-Agent 的 AI 助手。
            请使用简洁、自然、友好的中文回答。
            如果用户的问题信息不足，先给出最小可执行建议，再提示可以补充的信息。
            """;

    private final SystemPromptMapper systemPromptMapper;

    public SystemPromptServiceImpl(SystemPromptMapper systemPromptMapper) {
        this.systemPromptMapper = systemPromptMapper;
    }

    @Override
    @Transactional
    public List<SystemPromptResponse> listPrompts(Long userId) {
        ensureDefaultPrompt(userId);
        return systemPromptMapper.selectList(userPromptsQuery(userId))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public SystemPromptResponse createPrompt(Long userId, CreateSystemPromptRequest request) {
        ensureDefaultPrompt(userId);
        SystemPromptEntity entity = new SystemPromptEntity();
        entity.setUserId(userId);
        entity.setName(request.name().trim());
        entity.setContent(request.content().trim());
        entity.setIsDefault(false);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        systemPromptMapper.insert(entity);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public SystemPromptResponse updatePrompt(Long userId, Long promptId, UpdateSystemPromptRequest request) {
        SystemPromptEntity entity = getOwnedPrompt(userId, promptId);
        entity.setName(request.name().trim());
        entity.setContent(request.content().trim());
        entity.setUpdatedAt(LocalDateTime.now());
        systemPromptMapper.updateById(entity);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public void deletePrompt(Long userId, Long promptId) {
        SystemPromptEntity entity = getOwnedPrompt(userId, promptId);
        systemPromptMapper.deleteById(entity.getId());
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            SystemPromptEntity next = systemPromptMapper.selectList(userPromptsQuery(userId)).stream().findFirst().orElse(null);
            if (next != null) {
                markDefault(userId, next.getId());
            }
        }
    }

    @Override
    @Transactional
    public SystemPromptResponse setDefaultPrompt(Long userId, Long promptId) {
        SystemPromptEntity entity = getOwnedPrompt(userId, promptId);
        markDefault(userId, entity.getId());
        entity.setIsDefault(true);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public Long resolvePromptId(Long userId, Long promptId) {
        if (promptId != null) {
            return getOwnedPrompt(userId, promptId).getId();
        }
        return ensureDefaultPrompt(userId).getId();
    }

    @Override
    @Transactional
    public String getSystemPrompt(Long userId, Long promptId) {
        return getOwnedPrompt(userId, promptId).getContent();
    }

    private SystemPromptEntity ensureDefaultPrompt(Long userId) {
        SystemPromptEntity defaultPrompt = systemPromptMapper.selectList(new LambdaQueryWrapper<SystemPromptEntity>()
                        .eq(SystemPromptEntity::getUserId, userId)
                        .eq(SystemPromptEntity::getIsDefault, true)
                        .orderByAsc(SystemPromptEntity::getId))
                .stream()
                .findFirst()
                .orElse(null);
        if (defaultPrompt != null) {
            return defaultPrompt;
        }

        SystemPromptEntity firstPrompt = systemPromptMapper.selectList(userPromptsQuery(userId)).stream().findFirst().orElse(null);
        if (firstPrompt != null) {
            markDefault(userId, firstPrompt.getId());
            firstPrompt.setIsDefault(true);
            return firstPrompt;
        }

        SystemPromptEntity entity = new SystemPromptEntity();
        entity.setUserId(userId);
        entity.setName(DEFAULT_PROMPT_NAME);
        entity.setContent(DEFAULT_PROMPT_CONTENT);
        entity.setIsDefault(true);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        systemPromptMapper.insert(entity);
        return entity;
    }

    private SystemPromptEntity getOwnedPrompt(Long userId, Long promptId) {
        if (promptId == null) {
            throw new BusinessException(40001, "提示词不存在");
        }
        SystemPromptEntity entity = systemPromptMapper.selectList(new LambdaQueryWrapper<SystemPromptEntity>()
                        .eq(SystemPromptEntity::getId, promptId)
                        .eq(SystemPromptEntity::getUserId, userId))
                .stream()
                .findFirst()
                .orElse(null);
        if (entity == null) {
            throw new BusinessException(40001, "提示词不存在");
        }
        return entity;
    }

    private void markDefault(Long userId, Long promptId) {
        List<SystemPromptEntity> prompts = systemPromptMapper.selectList(userPromptsQuery(userId));
        LocalDateTime now = LocalDateTime.now();
        for (SystemPromptEntity prompt : prompts) {
            boolean isDefault = prompt.getId().equals(promptId);
            if (!Boolean.valueOf(isDefault).equals(prompt.getIsDefault())) {
                prompt.setIsDefault(isDefault);
                prompt.setUpdatedAt(now);
                systemPromptMapper.updateById(prompt);
            }
        }
    }

    private LambdaQueryWrapper<SystemPromptEntity> userPromptsQuery(Long userId) {
        return new LambdaQueryWrapper<SystemPromptEntity>()
                .eq(SystemPromptEntity::getUserId, userId)
                .orderByDesc(SystemPromptEntity::getIsDefault)
                .orderByAsc(SystemPromptEntity::getCreatedAt)
                .orderByAsc(SystemPromptEntity::getId);
    }

    private SystemPromptResponse toResponse(SystemPromptEntity entity) {
        return new SystemPromptResponse(
                entity.getId(),
                entity.getName(),
                entity.getContent(),
                entity.getIsDefault()
        );
    }
}
