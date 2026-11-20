package com.h.backend.capability;

/** 锚点消息（文档续跑用）；团队房间无续跑锚点，实现可返回 null。 */
public record AnchorMessage(Long id, Long userId, String contentText) {
}