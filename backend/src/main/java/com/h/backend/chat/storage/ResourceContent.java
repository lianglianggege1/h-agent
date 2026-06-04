package com.h.backend.chat.storage;

import java.io.InputStream;

public record ResourceContent(
        InputStream inputStream,
        String mimeType,
        Long fileSize
) {
}
