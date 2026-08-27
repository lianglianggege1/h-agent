package com.h.backend.chat.infrastructure.content;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轻量文件签名校验器测试（新计划 §6.3 / §10 任务 4）。
 *
 * <p>覆盖：白名单八类魔数、文本主动内容（HTML/SVG/JS）、空文件与短头、
 * 提示 MIME 与签名的冲突/归一化、头字节回放流完整性（校验后流仍可完整读出，
 * 不两次读源流——计划 §6.3「检查流只缓存并回放有上限的文件头」）。
 */
class ResourceContentInspectorTest {

    private final ResourceContentInspector inspector = new ResourceContentInspector();

    // ------------------------------------------------------------------
    // 魔数识别（计划 §6.3：JPEG/PNG/WebP/MP4/MP3/M4A/WAV/WebM Audio）
    // ------------------------------------------------------------------

    @Test
    void shouldDetectJpegMagicNumber() throws IOException {
        var inspection = inspector.inspect(stream(jpeg()), "image/jpeg");

        assertEquals("image/jpeg", inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.IMAGE, inspection.result().category());
        assertTrue(inspection.result().matchesHint(), "提示 MIME 与签名一致时 matchesHint=true");
    }

    @Test
    void shouldDetectPngMagicNumber() throws IOException {
        var inspection = inspector.inspect(stream(png()), "image/png");

        assertEquals("image/png", inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.IMAGE, inspection.result().category());
        assertTrue(inspection.result().matchesHint());
    }

    @Test
    void shouldDetectWebpMagicNumber() throws IOException {
        var inspection = inspector.inspect(stream(webp()), "image/webp");

        assertEquals("image/webp", inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.IMAGE, inspection.result().category());
        assertTrue(inspection.result().matchesHint());
    }

    @Test
    void shouldDetectMp4FtypBox() throws IOException {
        var inspection = inspector.inspect(stream(mp4("isom")), "video/mp4");

        assertEquals("video/mp4", inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.VIDEO, inspection.result().category());
        assertTrue(inspection.result().matchesHint());
    }

    @Test
    void shouldDetectM4aByFtypMajorBrand() throws IOException {
        var inspection = inspector.inspect(stream(mp4("M4A ")), "audio/mp4");

        assertEquals("audio/mp4", inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.AUDIO, inspection.result().category());
        assertTrue(inspection.result().matchesHint());
    }

    @Test
    void shouldDetectMp3WithId3Tag() throws IOException {
        var inspection = inspector.inspect(stream(bytes('I', 'D', '3', 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)), "audio/mpeg");

        assertEquals("audio/mpeg", inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.AUDIO, inspection.result().category());
        assertTrue(inspection.result().matchesHint());
    }

    @Test
    void shouldDetectMp3FrameSyncWithoutId3Tag() throws IOException {
        // MPEG1 Layer III 帧同步 FF FB（无 ID3 前缀时的帧头判定）
        var inspection = inspector.inspect(stream(bytes(0xFF, 0xFB, 0x90, 0x00, 0x00, 0x00)), "audio/mpeg");

        assertEquals("audio/mpeg", inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.AUDIO, inspection.result().category());
    }

    @Test
    void shouldDetectMp3Mpeg2FrameSyncVariant() throws IOException {
        // MPEG2 Layer III 帧同步 FF F3
        var inspection = inspector.inspect(stream(bytes(0xFF, 0xF3, 0x40, 0x00)), "audio/mpeg");

        assertEquals("audio/mpeg", inspection.result().detectedType());
    }

    @Test
    void shouldDetectWavRiffWaveContainer() throws IOException {
        var inspection = inspector.inspect(stream(riffContainer("WAVE")), "audio/wav");

        assertEquals("audio/wav", inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.AUDIO, inspection.result().category());
    }

    @Test
    void shouldDetectWebmAudioEbmlHeader() throws IOException {
        var inspection = inspector.inspect(stream(ebml()), "audio/webm");

        assertEquals("audio/webm", inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.AUDIO, inspection.result().category());
    }

    @Test
    void riffContainerWithoutKnownSubtypeIsUnknown() throws IOException {
        // RIFF 容器但既非 WEBP 也非 WAVE：不做猜测
        var inspection = inspector.inspect(stream(riffContainer("AVI ")), "video/mp4");

        assertNull(inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.UNKNOWN, inspection.result().category());
        assertFalse(inspection.result().matchesHint());
    }

    // ------------------------------------------------------------------
    // 文本主动内容（HTML / SVG / JavaScript）
    // ------------------------------------------------------------------

    @Test
    void shouldFlagHtmlTextAsActiveContent() throws IOException {
        var inspection = inspector.inspect(stream("<html><body>hi</body></html>"), "text/plain");

        assertEquals(ResourceContentInspector.ContentCategory.ACTIVE_CONTENT, inspection.result().category());
        assertNull(inspection.result().detectedType());
    }

    @Test
    void shouldFlagDoctypeHtmlAsActiveContent() throws IOException {
        var inspection = inspector.inspect(stream("<!DOCTYPE html><html>"), "text/html");

        assertEquals(ResourceContentInspector.ContentCategory.ACTIVE_CONTENT, inspection.result().category());
    }

    @Test
    void shouldFlagScriptTagAsActiveContent() throws IOException {
        var inspection = inspector.inspect(stream("<script>alert(1)</script>"), "image/png");

        assertEquals(ResourceContentInspector.ContentCategory.ACTIVE_CONTENT, inspection.result().category());
    }

    @Test
    void shouldFlagSvgMarkupAsActiveContent() throws IOException {
        var inspection = inspector.inspect(stream("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"), "image/svg+xml");

        assertEquals(ResourceContentInspector.ContentCategory.ACTIVE_CONTENT, inspection.result().category());
    }

    @Test
    void shouldFlagXmlWithSvgReferenceAsActiveContent() throws IOException {
        var inspection = inspector.inspect(stream("<?xml version=\"1.0\"?><svg></svg>"), "image/svg+xml");

        assertEquals(ResourceContentInspector.ContentCategory.ACTIVE_CONTENT, inspection.result().category());
    }

    @Test
    void shouldFlagJavaScriptTextAsActiveContent() throws IOException {
        var inspection = inspector.inspect(stream("(function(){ document.write('x'); })();"), "text/javascript");

        assertEquals(ResourceContentInspector.ContentCategory.ACTIVE_CONTENT, inspection.result().category());
    }

    @Test
    void shouldFlagJavascriptUriAsActiveContent() throws IOException {
        var inspection = inspector.inspect(stream("javascript:alert(document.cookie)"), "text/plain");

        assertEquals(ResourceContentInspector.ContentCategory.ACTIVE_CONTENT, inspection.result().category());
    }

    @Test
    void plainTextWithoutActiveContentMarkersIsUnknown() throws IOException {
        var inspection = inspector.inspect(stream("hello world, plain text payload"), "text/plain");

        assertEquals(ResourceContentInspector.ContentCategory.UNKNOWN, inspection.result().category());
        assertNull(inspection.result().detectedType());
        assertFalse(inspection.result().matchesHint());
    }

    @Test
    void binarySignatureWinsOverTextGuess() throws IOException {
        // 魔数命中优先于文本猜测：PNG 魔数后跟 script 文本仍是 IMAGE（互斥）
        byte[] pngWithScript = concat(png(), "<script>".getBytes(StandardCharsets.ISO_8859_1));

        var inspection = inspector.inspect(stream(pngWithScript), "image/png");

        assertEquals("image/png", inspection.result().detectedType());
        assertEquals(ResourceContentInspector.ContentCategory.IMAGE, inspection.result().category());
    }

    // ------------------------------------------------------------------
    // 空文件 / 短头
    // ------------------------------------------------------------------

    @Test
    void emptyFileIsUndeterminable() throws IOException {
        var inspection = inspector.inspect(stream(new byte[0]), "image/png");

        assertEquals(ResourceContentInspector.ContentCategory.UNKNOWN, inspection.result().category());
        assertNull(inspection.result().detectedType());
        assertFalse(inspection.result().matchesHint());
    }

    @Test
    void truncatedHeaderIsUndeterminable() throws IOException {
        // 2 字节无法构成任何已知魔数
        var inspection = inspector.inspect(stream(bytes(0x00, 0x01)), "image/jpeg");

        assertEquals(ResourceContentInspector.ContentCategory.UNKNOWN, inspection.result().category());
        assertNull(inspection.result().detectedType());
    }

    // ------------------------------------------------------------------
    // 提示 MIME：只是提示，不能覆盖检测结果（拒绝方案 10）
    // ------------------------------------------------------------------

    @Test
    void hintMismatchIsReportedNotTrusted() throws IOException {
        // 实际 JPEG 字节，提示声明 PNG：检测结果以签名为准
        var inspection = inspector.inspect(stream(jpeg()), "image/png");

        assertEquals("image/jpeg", inspection.result().detectedType());
        assertFalse(inspection.result().matchesHint(), "签名与提示冲突时 matchesHint=false");
    }

    @Test
    void hintIsNormalizedCaseAndParameters() throws IOException {
        var inspection = inspector.inspect(stream(jpeg()), "IMAGE/JPEG; charset=binary");

        assertEquals("image/jpeg", inspection.result().detectedType());
        assertTrue(inspection.result().matchesHint(), "提示 MIME 归一化（大小写/参数）后比对");
    }

    @Test
    void nullHintNeverMatches() throws IOException {
        var inspection = inspector.inspect(stream(jpeg()), null);

        assertEquals("image/jpeg", inspection.result().detectedType());
        assertFalse(inspection.result().matchesHint());
    }

    // ------------------------------------------------------------------
    // 回放流：校验后流仍可完整读出，不两次读源流（计划 §6.3）
    // ------------------------------------------------------------------

    @Test
    void replayStreamPreservesFullContentForLargeFile() throws IOException {
        // 大于头缓冲上限的文件：头 256 字节已缓冲，剩余字节仍在原流中
        byte[] payload = new byte[700];
        payload[0] = (byte) 0xFF;
        payload[1] = (byte) 0xD8;
        payload[2] = (byte) 0xFF;
        for (int i = 3; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }

        var inspection = inspector.inspect(stream(payload), "image/jpeg");
        assertEquals("image/jpeg", inspection.result().detectedType());

        byte[] replayed = inspection.replayStream().readAllBytes();
        assertArrayEquals(payload, replayed, "回放流必须完整还原原文件字节");
    }

    @Test
    void replayStreamPreservesSmallFile() throws IOException {
        byte[] payload = png();

        var inspection = inspector.inspect(stream(payload), "image/png");

        byte[] replayed = inspection.replayStream().readAllBytes();
        assertArrayEquals(payload, replayed);
    }

    @Test
    void replayStreamPreservesEmptyFile() throws IOException {
        var inspection = inspector.inspect(stream(new byte[0]), null);

        assertEquals(-1, inspection.replayStream().read(), "空文件回放流应立即 EOF");
    }

    @Test
    void headerReadIsBounded() throws IOException {
        // 头读取有上限（默认 256 字节）：校验不会读完整大文件
        byte[] payload = new byte[10_000];
        payload[0] = (byte) 0x89;
        System.arraycopy(png(), 1, payload, 1, 7);

        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(payload));
        var inspection = inspector.inspect(counting, "image/png");

        assertEquals("image/png", inspection.result().detectedType());
        assertTrue(counting.readDirectly() <= ResourceContentInspector.DEFAULT_HEADER_LIMIT_BYTES,
                "inspect 只允许通过回放缓冲读取头字节上限");
    }

    @Test
    void nullSourceIsRejected() {
        assertThrows(NullPointerException.class, () -> inspector.inspect(null, "image/png"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] jpeg() {
        return bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00);
    }

    private static byte[] png() {
        return bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R');
    }

    private static byte[] webp() {
        return riffContainer("WEBP");
    }

    /** RIFF 容器：RIFF + 4 字节大小 + 子类型（WEBP/WAVE/AVI 等）。 */
    private static byte[] riffContainer(String subtype) {
        byte[] head = "RIFF".getBytes(StandardCharsets.ISO_8859_1);
        byte[] tail = subtype.getBytes(StandardCharsets.ISO_8859_1);
        byte[] result = new byte[12];
        System.arraycopy(head, 0, result, 0, 4);
        result[4] = 0x24;
        result[5] = 0x00;
        result[6] = 0x00;
        result[7] = 0x00;
        System.arraycopy(tail, 0, result, 8, 4);
        return result;
    }

    /** ISO BMFF：4 字节 box 大小 + "ftyp" + major brand。 */
    private static byte[] mp4(String majorBrand) {
        byte[] result = new byte[16];
        result[3] = 0x20;
        System.arraycopy("ftyp".getBytes(StandardCharsets.ISO_8859_1), 0, result, 4, 4);
        System.arraycopy(majorBrand.getBytes(StandardCharsets.ISO_8859_1), 0, result, 8, 4);
        return result;
    }

    /** EBML/WebM 容器头：1A 45 DF A3 + 常见后续字节。 */
    private static byte[] ebml() {
        return bytes(0x1A, 0x45, 0xDF, 0xA3, 0x93, 0x42, 0x82, 0x85);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** 统计「绕过回放缓冲直接委托给底层 read 的字节」的探针流。 */
    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private int directReads;

        CountingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                directReads++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                directReads += read;
            }
            return read;
        }

        int readDirectly() {
            return directReads;
        }
    }
}
