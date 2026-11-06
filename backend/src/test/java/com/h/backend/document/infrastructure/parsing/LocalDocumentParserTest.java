package com.h.backend.document.infrastructure.parsing;

import static org.junit.jupiter.api.Assertions.*;

import com.h.backend.document.domain.DocumentTask;
import com.h.backend.document.infrastructure.config.DocumentProperties;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

class LocalDocumentParserTest {
    final LocalDocumentParser parser = new LocalDocumentParser(new DocumentProperties());

    DocumentTask task(String name) {
        var t = new DocumentTask();
        t.setStartPage(1);
        t.setFileName(name);
        return t;
    }

    @Test
    void htmlExtractsHeadingAndTableWithoutScriptOrExternalImages() throws Exception {
        var task = task("sample.html");
        parser.parse(
                task,
                "<html><body><h1>项目报告</h1><p>中文内容可以正常读取。</p><script>window.secret='DO_NOT_READ'</script><img src='http://127.0.0.1:1/no-network'><table><tr><th>费用</th><td>128元</td></tr></table></body></html>"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("GOOD", task.getStatus());
        String text = task.getPages().getFirst().getLocalText();
        assertTrue(text.contains("项目报告"));
        assertTrue(text.contains("<table"));
        assertFalse(text.contains("DO_NOT_READ"));
        assertFalse(text.contains("127.0.0.1"));
    }

    @Test
    void docxKeepsHeadingsAndTables() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("文档能力验收报告");
            var table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("金额");
            table.getRow(0).getCell(1).setText("128元");
            doc.write(output);
        }
        var task = task("report.docx");
        parser.parse(task, output.toByteArray());
        assertEquals("GOOD", task.getStatus());
        assertTrue(task.getPages().getFirst().getLocalText().contains("128元"));
        assertTrue(task.getPages().getFirst().getLocalText().contains("<table"));
    }

    @Test
    void mixedPdfDistinguishesNativeTextScannedPageAndBlankPage() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var doc = new PDDocument()) {
            var first = new PDPage();
            doc.addPage(first);
            try (var stream = new PDPageContentStream(doc, first)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("A reliable native document page.");
                stream.endText();
            }
            var second = new PDPage();
            doc.addPage(second);
            var image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 300, 200);
            g.setColor(Color.BLACK);
            g.drawString("SCANNED", 20, 40);
            g.dispose();
            try (var stream = new PDPageContentStream(doc, second)) {
                stream.drawImage(LosslessFactory.createFromImage(doc, image), 0, 0, 300, 200);
            }
            doc.addPage(new PDPage());
            doc.save(output);
        }
        var task = task("mixed.pdf");
        parser.parse(task, output.toByteArray());
        assertEquals("LOW", task.getStatus());
        assertEquals(3, task.getTotalPages());
        assertEquals("GOOD", task.getPages().get(0).getQuality());
        assertEquals("LOW", task.getPages().get(1).getQuality());
        assertEquals("GOOD", task.getPages().get(2).getQuality());
        var scoped = task("mixed.pdf");
        scoped.setStartPage(1);
        scoped.setEndPage(1);
        parser.parse(scoped, output.toByteArray());
        assertEquals("GOOD", scoped.getStatus());
        assertEquals(1, scoped.getPages().size());
    }

    @Test
    void corruptionIsLowAndRequestedRangeIsEnforced() {
        assertEquals("LOW", LocalDocumentParser.quality("这是一段包含�乱码的正文。"));
        assertEquals("EMPTY", LocalDocumentParser.quality(""));
    }
}
