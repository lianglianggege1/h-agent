package com.h.backend.document.infrastructure.generation;

import com.h.backend.document.infrastructure.config.DocumentProperties;
import com.h.backend.document.infrastructure.parsing.LocalDocumentParser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 生成适配器：将 Markdown 结构转换为 DOCX，经 LibreOffice 渲染后校验正文、字形和空白页面。 */
@Component
public class DocumentGenerator {
    private final DocumentProperties properties;

    public DocumentGenerator(DocumentProperties properties) {
        this.properties = properties;
    }

    public record Artifact(byte[] bytes, String mimeType, String extension) {}

    public Artifact generate(String markdown, String format) throws Exception {
        if (markdown == null
                || markdown.isBlank()
                || markdown.length() > properties.getMaxCharacters())
            throw new IllegalArgumentException("正文为空或超过文档生成上限");
        String type = format.toLowerCase(java.util.Locale.ROOT);
        if (type.equals("md"))
            return new Artifact(markdown.getBytes(StandardCharsets.UTF_8), "text/markdown", "md");
        if (!List.of("docx", "pdf").contains(type))
            throw new IllegalArgumentException("支持 DOCX、PDF 和 Markdown");
        var extensions = List.of(TablesExtension.create());
        String html =
                HtmlRenderer.builder()
                        .extensions(extensions)
                        .build()
                        .render(Parser.builder().extensions(extensions).build().parse(markdown));
        var body = Jsoup.parseBodyFragment(html).body();
        body.select("script,style,iframe,object,embed,img,link,audio,video").remove();
        Path directory = Files.createTempDirectory("h-document-");
        try {
            Path docx = directory.resolve("document.docx");
            try (var document = new XWPFDocument();
                    var output = Files.newOutputStream(docx)) {
                document.createStyles();
                for (var node : body.children()) append(document, node);
                document.write(output);
            }
            try (var reopened = new XWPFDocument(Files.newInputStream(docx))) {
                if (reopened.getBodyElements().isEmpty())
                    throw new IllegalArgumentException("生成的 Word 内容为空");
            }
            Path pdf = render(directory, docx);
            validatePdf(pdf, body.text());
            byte[] bytes = Files.readAllBytes(type.equals("pdf") ? pdf : docx);
            if (bytes.length > properties.getMaxBytes())
                throw new IllegalArgumentException("生成文件超过大小上限");
            return new Artifact(
                    bytes,
                    type.equals("pdf")
                            ? "application/pdf"
                            : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    type);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private void append(XWPFDocument document, Element element) {
        String tag = element.tagName();
        if (tag.equals("table")) {
            var rows = element.select("tr");
            if (rows.isEmpty()) return;
            int columns = rows.stream().mapToInt(row -> row.select("th,td").size()).max().orElse(1);
            var table = document.createTable(rows.size(), columns);
            table.setWidth("100%");
            for (int r = 0; r < rows.size(); r++) {
                var cells = rows.get(r).select("th,td");
                for (int c = 0; c < cells.size(); c++) {
                    var paragraph = table.getRow(r).getCell(c).getParagraphs().getFirst();
                    text(
                            paragraph,
                            cells.get(c).text(),
                            "th".equals(cells.get(c).tagName()),
                            false);
                }
            }
            return;
        }
        if (tag.equals("ul") || tag.equals("ol")) {
            int number = 1;
            for (var item : element.children()) {
                var p = document.createParagraph();
                text(p, (tag.equals("ol") ? number++ + ". " : "• ") + item.text(), false, false);
            }
            return;
        }
        if (tag.equals("hr")) {
            document.createParagraph().setBorderBottom(Borders.SINGLE);
            return;
        }
        if (tag.matches("h[1-6]")
                || tag.equals("p")
                || tag.equals("pre")
                || tag.equals("blockquote")) {
            var paragraph = document.createParagraph();
            paragraph.setSpacingAfter(140);
            if (tag.matches("h[1-6]")) {
                paragraph.setKeepNext(true);
                var run = text(paragraph, element.text(), true, false);
                run.setFontSize(Math.max(12, 22 - Integer.parseInt(tag.substring(1)) * 2));
            } else inline(paragraph, element, false, false);
            return;
        }
        if (element.children().isEmpty())
            text(document.createParagraph(), element.text(), false, false);
        else for (var child : element.children()) append(document, child);
    }

    private void inline(
            XWPFParagraph paragraph, org.jsoup.nodes.Node node, boolean bold, boolean italic) {
        if (node instanceof org.jsoup.nodes.TextNode value) {
            text(paragraph, value.getWholeText(), bold, italic);
            return;
        }
        if (node instanceof Element element && element.tagName().equals("br")) {
            paragraph.createRun().addBreak();
            return;
        }
        boolean nextBold = bold || node.nodeName().equals("strong") || node.nodeName().equals("b");
        boolean nextItalic = italic || node.nodeName().equals("em") || node.nodeName().equals("i");
        for (var child : node.childNodes()) inline(paragraph, child, nextBold, nextItalic);
    }

    private XWPFRun text(XWPFParagraph paragraph, String value, boolean bold, boolean italic) {
        var run = paragraph.createRun();
        run.setFontFamily(properties.getDocumentFont());
        run.setFontFamily(properties.getDocumentFont(), XWPFRun.FontCharRange.eastAsia);
        run.setFontSize(11);
        run.setBold(bold);
        run.setItalic(italic);
        String[] lines = value.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) run.addBreak();
            run.setText(lines[i]);
        }
        return run;
    }

    private Path render(Path directory, Path docx) throws Exception {
        Path log = directory.resolve("render.log");
        Path fonts = Files.createDirectory(directory.resolve("fonts"));
        try (var source = DocumentGenerator.class.getResourceAsStream("/fonts/NotoSansSC.ttf")) {
            if (source == null) throw new IllegalStateException("文档生成缺少中文字体资源");
            Files.copy(source, fonts.resolve("NotoSansSC.ttf"));
        }
        ProcessBuilder command =
                new ProcessBuilder(
                                properties.getSofficeCommand(),
                                "-env:UserInstallation=" + directory.resolve("profile").toUri(),
                                "--headless",
                                "--convert-to",
                                "pdf",
                                "--outdir",
                                directory.toString(),
                                docx.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        command.environment().put("SAL_FONTPATH_PRIVATE", fonts.toString());
        Path fontConfig = directory.resolve("fonts.conf");
        Files.writeString(
                fontConfig,
                "<?xml version=\"1.0\"?><!DOCTYPE fontconfig SYSTEM \"fonts.dtd\"><fontconfig><dir>"
                        + org.jsoup.nodes.Entities.escape(fonts.toString())
                        + "</dir><cachedir>"
                        + org.jsoup.nodes.Entities.escape(
                                directory.resolve("font-cache").toString())
                        + "</cachedir></fontconfig>");
        command.environment().put("FONTCONFIG_FILE", fontConfig.toString());
        Process process = command.start();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS))
                throw new IllegalArgumentException("文档渲染超时，请缩小文档范围");
            Path pdf = directory.resolve("document.pdf");
            if (process.exitValue() != 0 || !Files.isRegularFile(pdf))
                throw new IllegalArgumentException("文档渲染失败，请检查服务端 LibreOffice 和中文字体");
            return pdf;
        } finally {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroyForcibly();
        }
    }

    void validatePdf(Path path, String expectedText) throws Exception {
        try (var pdf = Loader.loadPDF(path.toFile())) {
            if (pdf.getNumberOfPages() == 0 || pdf.getNumberOfPages() > properties.getMaxPages())
                throw new IllegalArgumentException("生成文档页数为空或超过上限");
            var missingGlyph = new java.util.concurrent.atomic.AtomicBoolean();
            var stripper =
                    new PDFTextStripper() {
                        @Override
                        protected void processTextPosition(
                                org.apache.pdfbox.text.TextPosition position) {
                            if (position.getUnicode()
                                    .codePoints()
                                    .anyMatch(c -> !Character.isWhitespace(c))) {
                                for (int code : position.getCharacterCodes()) {
                                    var font = position.getFont();
                                    try {
                                        if (font
                                                        instanceof
                                                        org.apache.pdfbox.pdmodel.font.PDType0Font
                                                                composite
                                                && !composite.hasGlyph(code))
                                            missingGlyph.set(true);
                                        if (font
                                                instanceof
                                                org.apache.pdfbox.pdmodel.font.PDTrueTypeFont
                                                        trueType) {
                                            if (!trueType.hasGlyph(code)) missingGlyph.set(true);
                                        } else if (font
                                                instanceof
                                                org.apache.pdfbox.pdmodel.font.PDSimpleFont
                                                        simple) {
                                            String name = simple.getEncoding().getName(code);
                                            if (name == null
                                                    || name.equals(".notdef")
                                                    || !simple.hasGlyph(name))
                                                missingGlyph.set(true);
                                        }
                                    } catch (java.io.IOException error) {
                                        missingGlyph.set(true);
                                    }
                                }
                            }
                            super.processTextPosition(position);
                        }
                    };
            String actual = normalize(stripper.getText(pdf));
            if (missingGlyph.get()) throw new IllegalArgumentException("生成文件存在缺失字形，请检查文档字体");
            String expected = normalize(expectedText);
            // Compare character multiplicities to catch truncation and missing CJK text despite
            // line wrapping.
            var counts = new java.util.HashMap<Integer, Integer>();
            actual.codePoints().forEach(c -> counts.merge(c, 1, Integer::sum));
            for (int c : expected.codePoints().toArray()) {
                int available = counts.getOrDefault(c, 0);
                if (available < 1) throw new IllegalArgumentException("生成文档存在缺失文字或字体异常，已停止交付");
                counts.put(c, available - 1);
            }
            var renderer = new PDFRenderer(pdf);
            for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                if (LocalDocumentParser.blank(renderer.renderImageWithDPI(i, 40)))
                    throw new IllegalArgumentException("生成文档存在空白页面，已停止交付");
            }
        }
    }

    private static String normalize(String text) {
        return text.replaceAll("[\\s\\p{Z}]+", "");
    }
}
