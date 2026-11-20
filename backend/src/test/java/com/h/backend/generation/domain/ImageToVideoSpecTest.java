package com.h.backend.generation.domain;

import com.h.capabilities.chat.application.reference.ResolvedReferenceImage;
import com.h.capabilities.generation.domain.model.GenerationType;
import com.h.capabilities.generation.domain.model.ImageToVideoSpec;
import com.h.capabilities.generation.domain.service.ImageToVideoSourceValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageToVideoSpecTest {
    @Test
    void createsDefaultImageToVideoSpec() {
        ImageToVideoSpec spec = ImageToVideoSpec.withDefaults(
                "image-1", "让人物回头微笑", "人物缓慢回头微笑，[推进]", null, null, null,
                false, false, false
        );

        assertEquals(GenerationType.IMAGE_TO_VIDEO, spec.generationType());
        assertEquals("MiniMax-Hailuo-2.3", spec.model());
        assertEquals(6, spec.durationSeconds());
        assertEquals("768P", spec.resolution());
    }

    @Test
    void validatesMiniMaxImageRequirements() {
        ImageToVideoSourceValidator validator = new ImageToVideoSourceValidator();
        validator.validate(new ResolvedReferenceImage("image-1", "image/png", new byte[10], 10, 512, 512));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                validator.validate(new ResolvedReferenceImage("image-1", "image/png", new byte[10], 10, 300, 512))
        );

        assertEquals("图生视频参考图片短边必须大于 300px", error.getMessage());
    }
}
