package com.h.backend.chat.infrastructure.content;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Locale;
import java.util.Objects;

/**
 * 轻量文件签名校验器（新计划 §6.3 / §10 任务 4）。
 *
 * <p>只校验有上限的文件头（{@link #DEFAULT_HEADER_LIMIT_BYTES} 字节），
 * 不读完整文件、不落盘、不引入 Apache Tika。检查过的头字节被缓冲并与
 * 剩余原流拼接为<b>回放流</b>（{@link Inspection#replayStream()}），
 * 供调用方在「上传前校验 → 校验通过后流式保存」的单次消费链路中继续使用，
 * 避免「先校验再保存」需要两次读源流（计划 §6.3：检查流只缓存并回放
 * 有上限的文件头）。
 *
 * <p>签名库（魔数）：JPEG（FF D8 FF）、PNG（89 50 4E 47 0D 0A 1A 0A）、
 * WebP（RIFF....WEBP）、MP4（offset 4 处 ftyp box）、MP3（ID3 前缀或帧同步）、
 * M4A（ftyp major brand = "M4A "）、WAV（RIFF....WAVE）、
 * WebM Audio（1A 45 DF A3 EBML 容器头）。
 *
 * <p>MP3 无 ID3 时的帧头判定说明（宽松度取舍）：MPEG 音频帧头为 11 位帧同步
 * （FF Ex / FF Fx），这里仅凭前两字节判定并排除保留值——version bits = 01
 * 与 layer bits = 00 是 MPEG 规范的保留组合，予以排除；其余组合
 * （MPEG1/2/2.5 各 layer）一律接受。误报面极小（没有任何白名单图片/视频魔数
 * 以 FF Ex 开头），而漏报会退化为 UNKNOWN → 非白名单 attachment 兜底，安全。
 *
 * <p>文本主动内容检测（HTML/SVG/JS）只在<b>无任何魔数命中</b>时进行
 * （魔数命中优先于文本猜测，二者互斥）。JS 特征刻意选取高置信度 token
 * （javascript: 协议、(function、document.write、window.location、
 * document.cookie、onerror=、onload=），不使用 let/var/const 等自然语言
 * 常见词：误报会把合法文本附件错杀，漏报则走 UNKNOWN → attachment 兜底，
 * 仍是安全的。
 *
 * <p>提示 MIME 只是提示（拒绝方案 10）：检测结果不因提示而改变，
 * {@link InspectionResult#matchesHint()} 只如实报告二者是否一致，
 * 冲突与否的处置由应用层 {@code ResourceContentPolicy} 决定。
 */
@Component
public final class ResourceContentInspector {

    /** 头字节缓冲上限：足够覆盖全部签名（最长 12 字节）与文本特征采样。 */
    public static final int DEFAULT_HEADER_LIMIT_BYTES = 256;

    public ResourceContentInspector() {
    }

    /**
     * 读取有上限的文件头并做签名/主动内容分析。
     *
     * @param source       单次可消费输入流（本次调用只读走头字节上限内的数据）
     * @param hintMimeType 调用方提供的提示 MIME（用户声明/模型声明/probe），仅作比对
     * @return 检测结果与回放流；回放流从文件头第一个字节开始完整还原原内容
     */
    public Inspection inspect(InputStream source, String hintMimeType) throws IOException {
        Objects.requireNonNull(source, "source must not be null");

        byte[] header = source.readNBytes(DEFAULT_HEADER_LIMIT_BYTES);
        InspectionResult result = analyze(header, hintMimeType);
        InputStream replayStream = new SequenceInputStream(
                new ByteArrayInputStream(header), source);
        return new Inspection(result, replayStream);
    }

    private InspectionResult analyze(byte[] header, String hintMimeType) {
        String detectedType = detectBinarySignature(header);
        if (detectedType != null) {
            return new InspectionResult(
                    detectedType,
                    categoryOf(detectedType),
                    matches(detectedType, hintMimeType));
        }
        if (looksLikeActiveContent(header)) {
            return new InspectionResult(null, ContentCategory.ACTIVE_CONTENT, false);
        }
        return new InspectionResult(null, ContentCategory.UNKNOWN, false);
    }

    // ------------------------------------------------------------------
    // 二进制签名（魔数优先，命中即返回）
    // ------------------------------------------------------------------

    private static String detectBinarySignature(byte[] h) {
        // JPEG: FF D8 FF
        if (h.length >= 3
                && (h[0] & 0xFF) == 0xFF
                && (h[1] & 0xFF) == 0xD8
                && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (startsWith(h, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        // RIFF 容器：offset 8 处子类型区分 WEBP / WAVE，其余 RIFF 不猜测
        if (startsWith(h, 0x52, 0x49, 0x46, 0x46)) { // "RIFF"
            if (h.length >= 12 && asciiAt(h, 8, "WEBP")) {
                return "image/webp";
            }
            if (h.length >= 12 && asciiAt(h, 8, "WAVE")) {
                return "audio/wav";
            }
            return null;
        }
        // ISO BMFF（MP4 家族）：offset 4 处 "ftyp"；major brand "M4A " → audio/mp4，
        // 其余 brand（isom/iso2/mp41/mp42/avc1/dash 等）按白名单用途归为 video/mp4。
        if (h.length >= 8 && asciiAt(h, 4, "ftyp")) {
            if (h.length >= 12 && asciiAt(h, 8, "M4A ")) {
                return "audio/mp4";
            }
            return "video/mp4";
        }
        // MP3: ID3v2 前缀优先
        if (h.length >= 3 && asciiAt(h, 0, "ID3")) {
            return "audio/mpeg";
        }
        // MP3: 无 ID3 时的 MPEG 音频帧同步（宽松度见类注释）
        if (isMp3FrameSync(h)) {
            return "audio/mpeg";
        }
        // WebM Audio: EBML 容器头（白名单 MIME 为 audio/webm；EBML 头不区分音视频，
        // 按白名单用途归为 audio/webm——video/webm 不在允许集合中）
        if (startsWith(h, 0x1A, 0x45, 0xDF, 0xA3)) {
            return "audio/webm";
        }
        return null;
    }

    private static boolean isMp3FrameSync(byte[] h) {
        if (h.length < 2) {
            return false;
        }
        int b0 = h[0] & 0xFF;
        int b1 = h[1] & 0xFF;
        if (b0 != 0xFF) {
            return false;
        }
        if ((b1 & 0xE0) != 0xE0) {
            return false; // 11 位帧同步不完整
        }
        if ((b1 & 0x18) == 0x08) {
            return false; // version bits 01 = 保留
        }
        if ((b1 & 0x06) == 0x00) {
            return false; // layer bits 00 = 保留
        }
        return true;
    }

    private static ContentCategory categoryOf(String detectedType) {
        return switch (detectedType) {
            case "image/jpeg", "image/png", "image/webp" -> ContentCategory.IMAGE;
            case "video/mp4" -> ContentCategory.VIDEO;
            default -> ContentCategory.AUDIO;
        };
    }

    private static boolean matches(String detectedType, String hintMimeType) {
        return detectedType.equals(normalizeMimeType(hintMimeType));
    }

    // ------------------------------------------------------------------
    // 文本主动内容（仅在无魔数命中时；与魔数检测互斥）
    // ------------------------------------------------------------------

    private static boolean looksLikeActiveContent(byte[] header) {
        if (header.length == 0) {
            return false;
        }
        // 单字节宽松解码（ISO-8859-1 映射任意字节不抛异常），小写化后做特征匹配
        String text = new String(header, java.nio.charset.StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        // HTML
        if (containsAny(text, "<html", "<!doctype", "<script", "<head", "<body", "<iframe")) {
            return true;
        }
        // SVG
        if (text.contains("<svg")) {
            return true;
        }
        if (text.contains("<?xml") && text.contains("svg")) {
            return true;
        }
        // JavaScript（特征取舍见类注释：宁缺勿滥，漏报走 UNKNOWN→attachment 兜底）
        return containsAny(text,
                "javascript:",
                "(function",
                "document.write",
                "window.location",
                "document.cookie",
                "onerror=",
                "onload=");
    }

    private static boolean containsAny(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 字节工具
    // ------------------------------------------------------------------

    private static boolean startsWith(byte[] h, int... prefix) {
        if (h.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((h[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiAt(byte[] h, int offset, String expected) {
        if (h.length < offset + expected.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if ((h[offset + i] & 0xFF) != (expected.charAt(i) & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** MIME 归一化：strip、去参数（; 之后）、小写；空白/缺失返回 null。 */
    public static String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return null;
        }
        String value = mimeType.strip();
        int parameterSeparator = value.indexOf(';');
        if (parameterSeparator >= 0) {
            value = value.substring(0, parameterSeparator).strip();
        }
        if (value.isEmpty()) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 结果类型
    // ------------------------------------------------------------------

    /** 检测结果 + 回放流：回放流自文件头完整还原原内容，交由后续保存流程消费。 */
    public record Inspection(InspectionResult result, InputStream replayStream) {
    }

    /**
     * 签名检测结果。
     *
     * @param detectedType 签名命中的 MIME（如 image/jpeg）；未命中为 null
     * @param category     内容安全类别；魔数命中为 IMAGE/VIDEO/AUDIO，
     *                     文本主动内容为 ACTIVE_CONTENT，其余（空文件、短头、
     *                     纯文本、未知二进制）为 UNKNOWN
     * @param matchesHint  签名与提示 MIME（归一化后）是否一致；UNKNOWN/ACTIVE_CONTENT 恒 false
     */
    public record InspectionResult(String detectedType, ContentCategory category, boolean matchesHint) {
    }

    public enum ContentCategory {
        IMAGE,
        VIDEO,
        AUDIO,
        ACTIVE_CONTENT,
        UNKNOWN
    }
}
