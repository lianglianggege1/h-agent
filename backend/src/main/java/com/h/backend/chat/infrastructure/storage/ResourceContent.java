package com.h.backend.chat.infrastructure.storage;

import java.io.InputStream;

public record ResourceContent(
        InputStream inputStream,
        String mimeType,
        Long fileSize
) {
}
