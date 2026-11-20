package com.h.capabilities.chat.application.reference;

public interface ReferenceImageResolver {

    ResolvedReferenceImage resolve(Long userId, String resourceId);
}
