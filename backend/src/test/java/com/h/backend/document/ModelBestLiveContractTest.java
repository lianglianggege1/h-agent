package com.h.backend.document;

import static org.junit.jupiter.api.Assertions.*;

import com.h.backend.document.infrastructure.config.DocumentEnvironmentPostProcessor;
import com.h.backend.document.infrastructure.config.DocumentProperties;
import com.h.backend.document.infrastructure.parsing.OcrCoverage;
import com.h.backend.document.infrastructure.provider.ModelBestDocumentClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

import tools.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.time.Instant;

/**
 * Opt-in paid/external probe using synthetic content only. It never changes production validation
 * settings.
 */
@EnabledIfSystemProperty(named = "document.live-test", matches = "true")
class ModelBestLiveContractTest {
    @Test
    @EnabledIfSystemProperty(named = "document.replay-dir", matches = ".+")
    void originalUserImageHasScoredCoverageReviewForItsMissingLine() throws Exception {
        var environment = new StandardEnvironment();
        DocumentEnvironmentPostProcessor.apply(environment, java.nio.file.Path.of(""));
        var config =
                Binder.get(environment)
                        .bind("chat.documents", DocumentProperties.class)
                        .orElseThrow(() -> new IllegalStateException("未读取到文档配置"));
        var json = new ObjectMapper();
        var directory = java.nio.file.Path.of(System.getProperty("document.replay-dir"));
        var response =
                json.readTree(
                        java.nio.file.Files.readString(
                                directory.resolve("document-job-response.json")));
        var image =
                javax.imageio.ImageIO.read(
                        directory.resolve("document-original-page.png").toFile());
        var client = new ModelBestDocumentClient(config, json);
        for (var block : OcrCoverage.blocks(response, image, 1, 10000)) {
            assertEquals("UNVERIFIED", block.getVerdict());
            var score = client.verify(OcrCoverage.crop(image, block), block);
            assertEquals("scored", score.path("verdict").asText());
            System.out.printf(
                    "Replay Jev block=%s coverage=%s score=%.6f%n",
                    block.getId(), block.isCoverageCheck(), score.path("prob_yes").asDouble());
            if (block.isCoverageCheck())
                assertTrue(
                        score.path("prob_yes").asDouble() < config.getThreshold(),
                        "原图漏行必须经 Jev 低分进入核对");
            else
                assertTrue(
                        score.path("prob_yes").asDouble() >= config.getThreshold(), "返回文字块应通过 Jev");
        }
    }

    @Test
    void uploadedPageHasCompleteGeometryAndValidJevScores() throws Exception {
        var environment = new StandardEnvironment();
        DocumentEnvironmentPostProcessor.apply(environment, java.nio.file.Path.of(""));
        var config =
                Binder.get(environment)
                        .bind("chat.documents", DocumentProperties.class)
                        .orElseGet(DocumentProperties::new);
        assertFalse(config.getModelbestApiKey().isBlank(), "请在本地 .env 中配置 ModelBest 凭据");
        config.setModelbestBaseUrl(
                System.getenv().getOrDefault("MODELBEST_BASE_URL", config.getModelbestBaseUrl()));
        var client = new ModelBestDocumentClient(config, new ObjectMapper());
        var image = new BufferedImage(900, 300, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 900, 300);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 32));
        graphics.drawString("Document acceptance test", 40, 90);
        graphics.drawString("Total amount: 128.00", 40, 160);
        graphics.dispose();
        String fileId = client.upload(image);
        String jobId = client.submit(fileId, "h-document-probe-" + java.util.UUID.randomUUID());
        var result = client.query(jobId);
        Instant deadline = Instant.now().plusSeconds(180);
        while ("Running".equals(result.path("status").asText())
                && Instant.now().isBefore(deadline)) {
            Thread.sleep(1500);
            result = client.query(jobId);
        }
        var blocks = OcrCoverage.blocks(result, image, 1, 10000);
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.stream().anyMatch(block -> block.getText().contains("128")));
        for (var block : blocks) {
            assertEquals("UNVERIFIED", block.getVerdict(), "Cloud geometry or coverage failed");
            var score = client.verify(OcrCoverage.crop(image, block), block);
            assertEquals("scored", score.path("verdict").asText());
            assertTrue(score.path("prob_yes").isNumber());
            assertTrue(
                    score.path("prob_yes").asDouble() >= 0
                            && score.path("prob_yes").asDouble() <= 1);
        }
    }
}
