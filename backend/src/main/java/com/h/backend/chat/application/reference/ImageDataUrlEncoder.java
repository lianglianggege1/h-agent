package com.h.backend.chat.application.reference;

import java.util.Base64;

public final class ImageDataUrlEncoder {
    private ImageDataUrlEncoder() {
    }

    public static String encode(ResolvedReferenceImage image) {
        return "data:%s;base64,%s".formatted(
                image.mimeType(),
                Base64.getEncoder().encodeToString(image.content())
        );
    }
}
