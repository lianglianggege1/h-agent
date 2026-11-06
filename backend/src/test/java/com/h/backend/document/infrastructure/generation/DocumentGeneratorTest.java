package com.h.backend.document.infrastructure.generation;

import static org.junit.jupiter.api.Assertions.*;

import com.h.backend.document.infrastructure.config.DocumentProperties;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

class DocumentGeneratorTest {
    final DocumentProperties config = new DocumentProperties();
    final String markdown =
            """
            # 文档能力验收报告

            这是一段中文正文。**关键金额为128元**，需要保留完整内容。

            ## 处理步骤

            1. 解析原文件
            2. 复核识别结果
            3. 交付新文档

            | 项目 | 金额 |
            | --- | --- |
            | 测试费用 | 128元 |
            | 合计 | 128元 |
            """;

    @Test
    void chineseDocxAndPdfAreRenderedAndPreserveTablesAndContent() throws Exception {
        var generator = new DocumentGenerator(config);
        var word = generator.generate(markdown, "docx");
        try (var doc = new XWPFDocument(new ByteArrayInputStream(word.bytes()))) {
            assertEquals(1, doc.getTables().size());
            assertTrue(
                    doc.getParagraphs().stream().anyMatch(p -> p.getText().contains("文档能力验收报告")));
        }
        var pdf = generator.generate(markdown, "pdf");
        try (var doc = Loader.loadPDF(pdf.bytes())) {
            assertTrue(new PDFTextStripper().getText(doc).contains("测试费用"));
            Path directory = Path.of("target/document-qa");
            Files.createDirectories(directory);
            Files.write(directory.resolve("report.pdf"), pdf.bytes());
            javax.imageio.ImageIO.write(
                    new PDFRenderer(doc).renderImageWithDPI(0, 100),
                    "png",
                    directory.resolve("report.png").toFile());
            Files.write(directory.resolve("report.docx"), word.bytes());
        }
    }

    @Test
    void textExtractionCannotHideMissingGlyphs(@org.junit.jupiter.api.io.TempDir Path directory)
            throws Exception {
        Path path = directory.resolve("missing-glyph.pdf");
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            var font =
                    new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
            var encoding = new org.apache.pdfbox.cos.COSDictionary();
            encoding.setItem(
                    org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.ENCODING);
            var differences = new org.apache.pdfbox.cos.COSArray();
            differences.add(org.apache.pdfbox.cos.COSInteger.ONE);
            differences.add(org.apache.pdfbox.cos.COSName.getPDFName(".notdef"));
            encoding.setItem(org.apache.pdfbox.cos.COSName.DIFFERENCES, differences);
            font.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.ENCODING, encoding);
            var unicode = new org.apache.pdfbox.pdmodel.common.PDStream(pdf);
            try (var output = unicode.createOutputStream()) {
                output.write(
                        ("/CIDInit /ProcSet findresource begin 12 dict begin begincmap"
                             + " /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0"
                             + " >> def /CMapName /Test def /CMapType 2 def 1 begincodespacerange"
                             + " <00> <FF> endcodespacerange 1 beginbfchar <01> <4E2D> endbfchar"
                             + " endcmap CMapName currentdict /CMap defineresource pop end end")
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.TO_UNICODE, unicode);
            var resources = new org.apache.pdfbox.pdmodel.PDResources();
            resources.put(org.apache.pdfbox.cos.COSName.getPDFName("F1"), font);
            page.setResources(resources);
            var content = new org.apache.pdfbox.pdmodel.common.PDStream(pdf);
            try (var output = content.createOutputStream()) {
                output.write(
                        "BT /F1 12 Tf 50 700 Td <01> Tj ET"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            page.setContents(content);
            pdf.save(path.toFile());
        }
        try (var pdf = Loader.loadPDF(path.toFile())) {
            assertTrue(new PDFTextStripper().getText(pdf).contains("中"));
        }
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new DocumentGenerator(config).validatePdf(path, "中"));
        assertTrue(error.getMessage().contains("字形"));
    }

    @Test
    void markdownIsExactAndUnknownFormatsFail() throws Exception {
        var generator = new DocumentGenerator(config);
        assertEquals(
                markdown,
                new String(
                        generator.generate(markdown, "md").bytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(markdown, "exe"));
    }
}
