package com.h.backend.document.infrastructure.parsing;

import com.h.backend.document.domain.DocumentTask;

import tools.jackson.databind.JsonNode;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** 校验 OCR 区域完整性；像素疑似遗漏仅触发整页 Jev 检查，由有效评分决定是否需要人工核对。 */
public final class OcrCoverage {
    private OcrCoverage() {}

    public static List<DocumentTask.Block> blocks(
            JsonNode result, BufferedImage original, int pageNumber, int maxCharacters) {
        if (!"Success".equals(result.path("status").asText())
                || result.path("pages_done").asInt() != 1
                || result.path("pages_total").asInt() != 1
                || result.path("pages").size() != 1)
            throw new IllegalArgumentException("OCR 作业未完整返回所请求的页面");
        JsonNode page = result.path("pages").path(0);
        JsonNode ocr = page.path("ocr");
        int width = ocr.path("image_width").asInt(), height = ocr.path("image_height").asInt();
        if (width < 1
                || height < 1
                || ocr.path("rotation").path("applied").asBoolean(false)
                || Math.abs(
                                (double) width / height
                                        - (double) original.getWidth() / original.getHeight())
                        > 0.02) throw new IllegalArgumentException("OCR 页面尺寸或方向与原图不匹配");
        List<DocumentTask.Block> blocks = new ArrayList<>();
        List<List<Double>> allRegions = new ArrayList<>();
        int characters = 0;
        for (JsonNode node : ocr.path("blocks")) {
            var block = new DocumentTask.Block();
            block.setId("p" + pageNumber + "-b" + blocks.size());
            block.setImageWidth(width);
            block.setImageHeight(height);
            block.setText(node.path("content").asText(""));
            characters += block.getText().length();
            if (characters > maxCharacters || blocks.size() > 2000)
                throw new IllegalArgumentException("OCR 结果超过处理上限");
            String label = node.path("label").asText("");
            block.setCategory(
                    label.contains("table")
                            ? "table"
                            : label.contains("formula") ? "formula" : "text");
            for (JsonNode region : node.path("regions"))
                block.getRegions().add(region(region, width, height));
            if (block.getRegions().isEmpty())
                block.getRegions().add(region(node.path("bbox"), width, height));
            allRegions.addAll(block.getRegions());
            if (List.of("image", "picture", "figure", "header_image", "footer_image")
                            .contains(label)
                    && block.getText().isBlank()) continue;
            if (!"ok".equals(node.path("status").asText()) || block.getText().isBlank())
                block.setVerdict("OCR_FAILED");
            blocks.add(block);
        }
        if (uncovered(original, allRegions, width, height)
                || blocks.isEmpty() && !LocalDocumentParser.blank(original)) {
            var block = new DocumentTask.Block();
            block.setId("p" + pageNumber + "-coverage");
            block.setCoverageCheck(true);
            block.setText(
                    blocks.stream()
                            .map(DocumentTask.Block::getText)
                            .filter(text -> !text.isBlank())
                            .collect(java.util.stream.Collectors.joining("\n\n")));
            // A pixel heuristic is only a signal for a model check, never a reason to force manual
            // editing.
            block.setVerdict(block.getText().isBlank() ? "OCR_FAILED" : "UNVERIFIED");
            block.setRegions(List.of(List.of(0d, 0d, (double) width, (double) height)));
            block.setImageWidth(width);
            block.setImageHeight(height);
            blocks.add(block);
        }
        return blocks;
    }

    private static List<Double> region(JsonNode node, int width, int height) {
        if (!node.isArray() || node.size() != 4) throw new IllegalArgumentException("OCR 缺少有效区域坐标");
        var values = new ArrayList<Double>();
        for (var coordinate : node) {
            if (!coordinate.isNumber() || !Double.isFinite(coordinate.asDouble()))
                throw new IllegalArgumentException("OCR 坐标无效");
            values.add(coordinate.asDouble());
        }
        if (values.get(0) < 0
                || values.get(1) < 0
                || values.get(2) > width
                || values.get(3) > height
                || values.get(2) <= values.get(0)
                || values.get(3) <= values.get(1)) throw new IllegalArgumentException("OCR 区域越界");
        return values;
    }

    private static boolean uncovered(
            BufferedImage image, List<List<Double>> regions, int width, int height) {
        int dark = 0, missed = 0;
        for (int y = 0; y < image.getHeight(); y += 4)
            for (int x = 0; x < image.getWidth(); x += 4) {
                int rgb = image.getRGB(x, y);
                if (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255) > 300) continue;
                dark++;
                double px = (double) x * width / image.getWidth(),
                        py = (double) y * height / image.getHeight();
                boolean covered = false;
                for (var r : regions)
                    if (px >= r.get(0) - 3
                            && px <= r.get(2) + 3
                            && py >= r.get(1) - 3
                            && py <= r.get(3) + 3) {
                        covered = true;
                        break;
                    }
                if (!covered) missed++;
            }
        return missed > Math.max(10, dark * 0.03);
    }

    public static BufferedImage crop(BufferedImage image, DocumentTask.Block block) {
        List<BufferedImage> crops = new ArrayList<>();
        int width = 0, height = 0;
        for (var r : block.getRegions()) {
            int x =
                    Math.max(
                            0,
                            (int) Math.floor(r.get(0) * image.getWidth() / block.getImageWidth()));
            int y =
                    Math.max(
                            0,
                            (int)
                                    Math.floor(
                                            r.get(1) * image.getHeight() / block.getImageHeight()));
            int endX =
                    Math.min(
                            image.getWidth(),
                            (int) Math.ceil(r.get(2) * image.getWidth() / block.getImageWidth()));
            int endY =
                    Math.min(
                            image.getHeight(),
                            (int) Math.ceil(r.get(3) * image.getHeight() / block.getImageHeight()));
            if (endX <= x || endY <= y) throw new IllegalArgumentException("OCR 原图裁剪区域无效");
            var crop = image.getSubimage(x, y, endX - x, endY - y);
            crops.add(crop);
            width = Math.max(width, crop.getWidth());
            height += crop.getHeight();
        }
        if (width == 0 || height == 0 || (long) width * height > 16_000_000)
            throw new IllegalArgumentException("OCR 原图区域超过限制");
        var joined = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = joined.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        int y = 0;
        for (var crop : crops) {
            graphics.drawImage(crop, 0, y, null);
            y += crop.getHeight();
        }
        graphics.dispose();
        return joined;
    }
}
