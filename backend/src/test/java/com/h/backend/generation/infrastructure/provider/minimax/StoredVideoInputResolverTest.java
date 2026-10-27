package com.h.backend.generation.infrastructure.provider.minimax;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.infrastructure.storage.*;
import com.h.backend.generation.domain.model.VideoV2Spec;
import com.h.backend.generation.infrastructure.config.GenerationProperties;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

class StoredVideoInputResolverTest {
    @Test
    void acceptsDocumented362FrameBaseVideoAndRetainsOriginalImage() throws Exception {
        try {
            Assumptions.assumeTrue(
                    new ProcessBuilder("ffmpeg", "-version")
                                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                    .start()
                                    .waitFor()
                            == 0);
        } catch (IOException e) {
            Assumptions.abort("ffmpeg not installed");
        }
        var file = Files.createTempFile("h-agent-h3-base-fixture-", ".mp4");
        byte[] video;
        try {
            var process =
                    new ProcessBuilder(
                                    "ffmpeg",
                                    "-v",
                                    "error",
                                    "-y",
                                    "-f",
                                    "lavfi",
                                    "-i",
                                    "color=c=blue:s=768x768:r=24",
                                    "-f",
                                    "lavfi",
                                    "-i",
                                    "anullsrc=r=48000:cl=stereo",
                                    "-frames:v",
                                    "362",
                                    "-t",
                                    "15.083333",
                                    "-c:v",
                                    "libx264",
                                    "-preset",
                                    "ultrafast",
                                    "-pix_fmt",
                                    "yuv420p",
                                    "-c:a",
                                    "aac",
                                    file.toString())
                            .redirectError(ProcessBuilder.Redirect.INHERIT)
                            .start();
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
            video = Files.readAllBytes(file);
        } finally {
            Files.deleteIfExists(file);
        }
        var pngOutput = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB), "png", pngOutput);
        byte[] png = pngOutput.toByteArray();
        var rows = mock(ChatMessageResourceMapper.class);
        var storage = mock(ResourceStorage.class);
        for (String id : List.of("original", "base")) {
            var row = new ChatMessageResourceEntity();
            row.setUserId(1L);
            row.setStorageKey(id);
            row.setMimeType(id.equals("base") ? "video/mp4" : "image/png");
            when(rows.selectByResourceId(id)).thenReturn(row);
        }
        when(storage.open(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            boolean base = invocation.getArgument(0).equals("base");
                            byte[] bytes = base ? video : png;
                            return new ResourceContent(
                                    new ByteArrayInputStream(bytes),
                                    base ? "video/mp4" : "image/png",
                                    bytes.length,
                                    bytes.length,
                                    0,
                                    false);
                        });
        var resolver =
                new StoredVideoInputResolver(
                        rows, storage, new GenerationProperties(), new ObjectMapper());
        var content =
                resolver.resolve(
                        1L,
                        new VideoV2Spec(
                                "regeneration",
                                "最终描述",
                                "MiniMax-H3",
                                15,
                                "2K",
                                "adaptive",
                                List.of(
                                        new VideoV2Spec.Input("original", "first_frame"),
                                        new VideoV2Spec.Input("base", "base_video")),
                                null));
        assertEquals(3, content.size());
        assertEquals("first_frame", content.get(1).get("role"));
        assertEquals("base_video", content.get(2).get("role"));
        assertEquals("video_url", content.get(2).get("type"));
    }

    @Test
    void verifiesOwnershipBeforeReadingMedia() {
        var rows = mock(ChatMessageResourceMapper.class);
        var storage = mock(ResourceStorage.class);
        var row = new ChatMessageResourceEntity();
        row.setUserId(2L);
        when(rows.selectByResourceId("foreign")).thenReturn(row);
        var resolver =
                new StoredVideoInputResolver(
                        rows, storage, new GenerationProperties(), new ObjectMapper());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        resolver.resolve(
                                1L,
                                VideoV2Spec.defaults(
                                        "generation",
                                        "test",
                                        null,
                                        6,
                                        null,
                                        null,
                                        List.of(new VideoV2Spec.Input("foreign", "first_frame")))));
        verifyNoInteractions(storage);
    }

    @Test
    void realFfprobeValidatesImagePixelsInsteadOfTrustingStoredDimensions() throws Exception {
        try {
            Assumptions.assumeTrue(
                    new ProcessBuilder("ffprobe", "-version")
                                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                    .start()
                                    .waitFor()
                            == 0);
        } catch (IOException e) {
            Assumptions.abort("ffprobe not installed");
        }
        var rows = mock(ChatMessageResourceMapper.class);
        var storage = mock(ResourceStorage.class);
        var row = new ChatMessageResourceEntity();
        row.setUserId(1L);
        row.setMimeType("image/png");
        row.setStorageKey("key");
        row.setWidth(512);
        row.setHeight(512);
        when(rows.selectByResourceId("image")).thenReturn(row);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB), "png", bytes);
        byte[] png = bytes.toByteArray();
        when(storage.open(eq("key"), any()))
                .thenAnswer(
                        i ->
                                new ResourceContent(
                                        new ByteArrayInputStream(png),
                                        "image/png",
                                        png.length,
                                        png.length,
                                        0,
                                        false));
        var resolver =
                new StoredVideoInputResolver(
                        rows, storage, new GenerationProperties(), new ObjectMapper());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        resolver.resolve(
                                1L,
                                VideoV2Spec.defaults(
                                        "generation",
                                        "test",
                                        null,
                                        6,
                                        null,
                                        null,
                                        List.of(new VideoV2Spec.Input("image", "first_frame")))));
    }
}
