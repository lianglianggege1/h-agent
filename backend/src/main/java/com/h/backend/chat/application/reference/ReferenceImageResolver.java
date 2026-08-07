package com.h.backend.chat.application.reference;

public interface ReferenceImageResolver {

    ResolvedReferenceImage resolve(Long userId, String resourceId);
}
