package com.h.backend.generation.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.h.backend.generation.domain.model.VideoV2Spec;

import org.junit.jupiter.api.Test;

import java.util.List;

class VideoV2SpecTest {
    @Test
    void supportsFifteenSecondsWithoutChangingModelsOrDroppingConflictingInputs() {
        var text = VideoV2Spec.defaults("generation", "海边的15秒镜头", null, 15, null, null, List.of());
        assertEquals("MiniMax-H3", text.model());
        assertEquals("768P", text.resolution());
        assertEquals("16:9", text.ratio());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VideoV2Spec.defaults(
                                "generation",
                                "test",
                                "MiniMax-H3-Highspeed",
                                15,
                                "2K",
                                null,
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        VideoV2Spec.defaults(
                                "generation",
                                "test",
                                null,
                                6,
                                null,
                                null,
                                List.of(
                                        new VideoV2Spec.Input("image", "first_frame"),
                                        new VideoV2Spec.Input("audio", "reference_audio"))));
        var frames =
                VideoV2Spec.defaults(
                        "generation",
                        "test",
                        null,
                        6,
                        null,
                        "16:9",
                        List.of(new VideoV2Spec.Input("image", "first_frame")));
        assertEquals("adaptive", frames.ratio());
    }
}
