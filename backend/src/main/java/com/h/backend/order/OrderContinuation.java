package com.h.backend.order;

/** Server-only Reactor context; the browser cannot provide this through forwardedProps. */
public record OrderContinuation(Long userMessageId) { }
