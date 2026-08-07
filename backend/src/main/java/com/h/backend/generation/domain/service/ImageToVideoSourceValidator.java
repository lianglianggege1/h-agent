package com.h.backend.generation.domain.service;

import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.Locale;

@Component
public class ImageToVideoSourceValidator {
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    public void validate(ResolvedReferenceImage image) {
        if (!SUPPORTED_MIME_TYPES.contains(image.mimeType().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("图生视频仅支持 JPG、PNG 或 WebP 图片");
        }
        if (image.fileSize() >= MAX_FILE_SIZE) {
            throw new IllegalArgumentException("图生视频参考图片必须小于 20MB");
        }
        if (image.width() == null || image.height() == null) {
            throw new IllegalArgumentException("无法读取图生视频参考图片尺寸");
        }
        int shortSide = Math.min(image.width(), image.height());
        if (shortSide <= 300) {
            throw new IllegalArgumentException("图生视频参考图片短边必须大于 300px");
        }
        double ratio = (double) image.width() / image.height();
        if (ratio < 0.4 || ratio > 2.5) {
            throw new IllegalArgumentException("图生视频参考图片宽高比必须在 2:5 到 5:2 之间");
        }
    }
}
