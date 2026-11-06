package com.h.backend.document.domain;

/**
 * Server-only context. A document continuation keeps the original persisted user-message anchor.
 */
public record DocumentContinuation(String taskId, Long userMessageId) {}
