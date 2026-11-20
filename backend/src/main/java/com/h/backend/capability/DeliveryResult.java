package com.h.backend.capability;

/**
 * 交付结果。projectionHandle 是交付实现私有的实时投影凭据
 * （产品聊天为 ChatSessionMessageDto），交给 LiveProjectionPort 原样回传，
 * 能力层不解释其内容；无投影的实现返回 null。
 */
public record DeliveryResult(DeliveredArtifact artifact, Object projectionHandle) {
}