package com.h.backend.document.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.awt.image.BufferedImage;

class OcrCoverageTest {
    final ObjectMapper json = new ObjectMapper();

    BufferedImage original() {
        var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 100);
        g.setColor(Color.BLACK);
        g.fillRect(70, 70, 20, 20);
        g.dispose();
        return image;
    }

    String result(String box, String status) {
        return """
        {"status":"Success","pages_done":1,"pages_total":1,"pages":[{"ocr":{"image_width":100,"image_height":100,
        "blocks":[{"label":"text","content":"候选内容","status":"%s","bbox":%s}]}}]}
        """
                .formatted(status, box);
    }

    @Test
    void missedInkCannotPassByScoringOnlyReturnedBlocks() {
        var blocks =
                OcrCoverage.blocks(
                        json.readTree(result("[0,0,20,20]", "ok")), original(), 1, 10000);
        assertTrue(
                blocks.stream()
                        .anyMatch(b -> b.isCoverageCheck() && "UNVERIFIED".equals(b.getVerdict())));
    }

    @Test
    void graySecurityBackgroundDoesNotForceWholePageManualReview() {
        var image = original();
        var graphics = image.createGraphics();
        graphics.setColor(new Color(170, 170, 170));
        graphics.fillRect(0, 0, 50, 50);
        graphics.dispose();
        var blocks =
                OcrCoverage.blocks(json.readTree(result("[70,70,90,90]", "ok")), image, 1, 10000);
        assertEquals(1, blocks.size());
        assertFalse(blocks.getFirst().isCoverageCheck());
    }

    @Test
    void failedBlockAndIncompleteJobCannotBecomeVerified() {
        var blocks =
                OcrCoverage.blocks(
                        json.readTree(result("[0,0,100,100]", "failed")), original(), 1, 10000);
        assertEquals("OCR_FAILED", blocks.getFirst().getVerdict());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OcrCoverage.blocks(
                                json.readTree("{\"status\":\"Running\",\"pages_done\":1}"),
                                original(),
                                1,
                                10000));
    }

    @Test
    void boundsAndDimensionsAreCheckedBeforeCropping() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        OcrCoverage.blocks(
                                json.readTree(result("[0,0,101,100]", "ok")),
                                original(),
                                1,
                                10000));
        var blocks =
                OcrCoverage.blocks(
                        json.readTree(result("[0,0,100,100]", "ok")), original(), 1, 10000);
        assertEquals(100, OcrCoverage.crop(original(), blocks.getFirst()).getWidth());
    }

    @Test
    void decorativeHeaderImageMustNotBecomeFailedOcrText() {
        var result =
                json.readTree(
                        """
                        {"status":"Success","pages_done":1,"pages_total":1,"pages":[{"ocr":{"image_width":100,"image_height":100,
                        "blocks":[{"label":"header_image","content":"","status":"skipped_no_vlm","bbox":[0,0,100,100]},
                        {"label":"text","content":"已识别的文字","status":"ok","bbox":[70,70,90,90]}]}}]}
                        """);
        var blocks = OcrCoverage.blocks(result, original(), 1, 10000);
        assertEquals(1, blocks.size());
        assertEquals("UNVERIFIED", blocks.getFirst().getVerdict());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
            named = "document.replay-dir",
            matches = ".+")
    void originalUploadedImageDoesNotFailOnDecorationsAndBackground() throws Exception {
        var directory = java.nio.file.Path.of(System.getProperty("document.replay-dir"));
        var response =
                json.readTree(
                        java.nio.file.Files.readString(
                                directory.resolve("document-job-response.json")));
        var image =
                javax.imageio.ImageIO.read(
                        directory.resolve("document-original-page.png").toFile());
        var blocks = OcrCoverage.blocks(response, image, 1, 10000);
        assertFalse(blocks.stream().anyMatch(block -> "OCR_FAILED".equals(block.getVerdict())));
        assertFalse(blocks.stream().anyMatch(block -> "COVERAGE_ERROR".equals(block.getVerdict())));
        assertEquals(3, blocks.stream().filter(block -> !block.isCoverageCheck()).count());
        assertTrue(blocks.stream().allMatch(block -> "UNVERIFIED".equals(block.getVerdict())));
    }
}
