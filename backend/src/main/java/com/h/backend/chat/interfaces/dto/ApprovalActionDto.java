package com.h.backend.chat.interfaces.dto;

/** 聊天卡片可展示的安全工具动作。 */
public record ApprovalActionDto(String toolCallId, String toolName, String summary) {
}
