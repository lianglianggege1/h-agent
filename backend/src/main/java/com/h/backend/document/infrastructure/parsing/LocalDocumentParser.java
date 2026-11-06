package com.h.backend.document.infrastructure.parsing;

import com.h.backend.document.domain.DocumentTask;
import com.h.backend.document.infrastructure.config.DocumentProperties;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.html.DefaultHtmlMapper;
import org.apache.tika.parser.html.HtmlMapper;
import org.apache.tika.sax.ToXMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

/** 本地解析适配器：通过 LangChain4j Tika 扩展保留正文结构，使用 PDFBox 执行逐页检查与原图渲染。 */
@Component
public class LocalDocumentParser {
    private final java.util.concurrent.ExecutorService workers =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final DocumentProperties properties;

    public LocalDocumentParser(DocumentProperties properties) {
        this.properties = properties;
    }

    public DocumentTask parseWithDeadline(DocumentTask original, byte[] bytes) throws Exception {
        var task = new DocumentTask();
        task.setFileName(original.getFileName());
        task.setStartPage(original.getStartPage());
        task.setEndPage(original.getEndPage());
        var future =
                workers.submit(
                        () -> {
                            parse(task, bytes);
                            return task;
                        });
        try {
            return future.get(
                    Math.min(90, properties.getBudgetSeconds()),
                    java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        } catch (java.util.concurrent.TimeoutException failure) {
            throw new IllegalArgumentException("本地解析超时，请缩小范围或转换格式", failure);
        } finally {
            future.cancel(true);
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        workers.shutdownNow();
    }

    public void parse(DocumentTask task, byte[] bytes) throws Exception {
        if (isPdf(bytes)) {
            pdf(task, bytes);
            return;
        }
        if (!isPdf(bytes) && imageDimensions(bytes) != null) {
            task.setTotalPages(1);
            range(task, 1);
            task.setPages(new ArrayList<>(List.of(page(1, "", "LOW", "图片需要 OCR"))));
            task.setStatus("LOW");
            return;
        }
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, task.getFileName());
        ParseContext context = new ParseContext();
        // Embedded binaries and remote resources never enter the conversation or trigger network
        // access.
        context.set(
                EmbeddedDocumentExtractor.class,
                new EmbeddedDocumentExtractor() {
                    public boolean shouldParseEmbedded(Metadata m) {
                        return false;
                    }

                    public void parseEmbedded(
                            java.io.InputStream s,
                            org.xml.sax.ContentHandler h,
                            Metadata m,
                            boolean output) {}
                });
        context.set(HtmlMapper.class, new DefaultHtmlMapper());
        var out = new java.io.ByteArrayOutputStream();
        var handler = new ToXMLContentHandler(out, "UTF-8");
        var bounded =
                new org.apache.tika.sax.WriteOutContentHandler(
                        handler, properties.getMaxCharacters());
        try {
            new dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser(
                            AutoDetectParser::new,
                            () -> bounded,
                            () -> metadata,
                            () -> context,
                            false)
                    .parse(new ByteArrayInputStream(bytes));
        } catch (RuntimeException failure) {
            // The extension wraps checked Tika failures; retain encryption and format error
            // semantics.
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
        var html = Jsoup.parse(out.toString(java.nio.charset.StandardCharsets.UTF_8));
        html.select("script,style,iframe,object,embed,link,img,audio,video").remove();
        List<DocumentTask.Page> pages = new ArrayList<>();
        var physical = html.select("div.page");
        if (physical.isEmpty()) pages.add(page(1, markdown(html.body()), null, ""));
        else
            for (Element node : physical)
                pages.add(page(pages.size() + 1, markdown(node), null, ""));
        task.setTotalPages(pages.size());
        range(task, pages.size());
        task.setPages(new ArrayList<>(pages.subList(task.getStartPage() - 1, task.getEndPage())));
        task.setStatus(
                task.getPages().stream().allMatch(p -> "GOOD".equals(p.getQuality()))
                        ? "GOOD"
                        : "UNSUPPORTED");
        if (!"GOOD".equals(task.getStatus())) task.setDetail("此格式未获得可靠正文，请解密、转换为 PDF/图片或提交核对后的文本");
    }

    private void pdf(DocumentTask task, byte[] bytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            task.setTotalPages(doc.getNumberOfPages());
            range(task, doc.getNumberOfPages());
            List<DocumentTask.Page> pages = new ArrayList<>();
            int characters = 0;
            for (int n = task.getStartPage(); n <= task.getEndPage(); n++) {
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(n);
                stripper.setEndPage(n);
                String text = stripper.getText(doc).strip();
                characters += text.length();
                if (characters > properties.getMaxCharacters())
                    throw new IllegalArgumentException("正文超过处理上限，请缩小页码范围");
                boolean image =
                        hasImages(doc.getPage(n - 1).getResources(), new java.util.HashSet<>());
                String quality = quality(text);
                String diagnostic = "";
                if (image) {
                    quality = "LOW";
                    diagnostic = "页面含图像区域，需要 OCR 核对覆盖情况";
                }
                if (text.isBlank()
                        && !image
                        && blank(new PDFRenderer(doc).renderImageWithDPI(n - 1, 36))) {
                    quality = "GOOD";
                    diagnostic = "空白页";
                }
                pages.add(page(n, text, quality, diagnostic));
            }
            task.setPages(pages);
            task.setStatus(
                    pages.stream().allMatch(p -> "GOOD".equals(p.getQuality())) ? "GOOD" : "LOW");
        }
    }

    private boolean hasImages(
            org.apache.pdfbox.pdmodel.PDResources resources, java.util.Set<Object> visited)
            throws java.io.IOException {
        if (resources == null || !visited.add(resources.getCOSObject())) return false;
        for (var name : resources.getXObjectNames()) {
            var object = resources.getXObject(name);
            if (object instanceof PDImageXObject) return true;
            if (object instanceof org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form
                    && hasImages(form.getResources(), visited)) return true;
        }
        return false;
    }

    private void range(DocumentTask task, int total) {
        int end = task.getEndPage() == 0 ? total : task.getEndPage();
        if (task.getStartPage() < 1 || end < task.getStartPage() || end > total)
            throw new IllegalArgumentException("页码范围无效，原文件共 " + total + " 页");
        if (end - task.getStartPage() + 1 > properties.getMaxPages())
            throw new IllegalArgumentException("本次范围超过页数上限，请指定更小范围");
        task.setEndPage(end);
    }

    static DocumentTask.Page page(int n, String text, String quality, String diagnostic) {
        var page = new DocumentTask.Page();
        page.setNumber(n);
        page.setLocalText(text);
        page.setQuality(quality == null ? quality(text) : quality);
        page.setDiagnostic(diagnostic);
        return page;
    }

    static String quality(String text) {
        if (text.isBlank()) return "EMPTY";
        long bad =
                text.codePoints()
                        .filter(
                                c ->
                                        c == 0xfffd
                                                || c == 0
                                                || Character.isISOControl(c)
                                                        && !Character.isWhitespace(c))
                        .count();
        long usable = text.codePoints().filter(Character::isLetterOrDigit).count();
        return bad > 0 || usable < Math.min(8, text.strip().length()) ? "LOW" : "GOOD";
    }

    public static boolean isPdf(byte[] bytes) {
        return bytes.length > 4
                && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)
                        .equals("%PDF-");
    }

    public BufferedImage image(byte[] bytes, int page) throws Exception {
        BufferedImage result;
        if (isPdf(bytes)) {
            try (var doc = Loader.loadPDF(bytes)) {
                var box = doc.getPage(page - 1).getCropBox();
                float dpi = Math.min(150, 2400f * 72 / Math.max(box.getWidth(), box.getHeight()));
                result = new PDFRenderer(doc).renderImageWithDPI(page - 1, dpi);
            }
        } else {
            int[] dimensions = imageDimensions(bytes);
            if (dimensions == null) throw new IllegalArgumentException("此格式无法生成 OCR 原图");
            result = ImageIO.read(new ByteArrayInputStream(bytes));
        }
        if (result == null) throw new IllegalArgumentException("此文件无法转换为 OCR 原图");
        if ((long) result.getWidth() * result.getHeight() > 16_000_000L)
            throw new IllegalArgumentException("图片像素超过处理上限，请缩小图片");
        return result;
    }

    private int[] imageDimensions(byte[] bytes) throws java.io.IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 16_000_000L)
                    throw new IllegalArgumentException("图片像素超过处理上限，请缩小图片");
                if (reader.getNumImages(true) > 1)
                    throw new IllegalArgumentException("多页图片请转换为 PDF 后读取，避免遗漏页面");
                return new int[] {width, height};
            } finally {
                reader.dispose();
            }
        }
    }

    public static boolean blank(BufferedImage image) {
        int dark = 0, total = 0;
        for (int y = 0; y < image.getHeight(); y += 3)
            for (int x = 0; x < image.getWidth(); x += 3) {
                total++;
                int c = image.getRGB(x, y);
                if (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255) < 660) dark++;
            }
        return dark < Math.max(2, total / 2000);
    }

    static String markdown(Element node) {
        String tag = node.tagName().toLowerCase(Locale.ROOT);
        if (tag.matches("h[1-6]"))
            return "#".repeat(Integer.parseInt(tag.substring(1))) + " " + node.text() + "\n\n";
        if (tag.equals("table")) return node.outerHtml() + "\n\n";
        if (tag.equals("li")) return "- " + node.text() + "\n";
        if (tag.equals("p") || tag.equals("pre")) return node.wholeText().strip() + "\n\n";
        if (node.children().isEmpty()) return node.text();
        StringBuilder value = new StringBuilder();
        for (var child : node.childNodes()) {
            if (child instanceof Element element) value.append(markdown(element));
            else if (child instanceof org.jsoup.nodes.TextNode text) value.append(text.text());
        }
        return value.toString();
    }
}
