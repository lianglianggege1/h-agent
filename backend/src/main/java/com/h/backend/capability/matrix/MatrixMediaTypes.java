package com.h.backend.capability.matrix;

import java.util.Locale;

/** mxc 媒体没有 MIME 元数据，按魔数嗅探 H3/图片工具支持的格式。 */
public final class MatrixMediaTypes {

    private MatrixMediaTypes() {
    }

    public static String sniff(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return null;
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47)) return "image/png";
        if (startsWith(bytes, 'R', 'I', 'F', 'F') && at(bytes, 8, 'W', 'E', 'B', 'P')) return "image/webp";
        if (startsWith(bytes, 'R', 'I', 'F', 'F') && at(bytes, 8, 'W', 'A', 'V', 'E')) return "audio/wav";
        if (startsWith(bytes, 'I', 'D', '3')
                || (bytes[0] == (byte) 0xFF && (bytes[1] & 0xE0) == 0xE0)) return "audio/mpeg";
        if (at(bytes, 4, 'f', 't', 'y', 'p')) {
            String brand = ascii(bytes, 8, 4);
            return switch (brand) {
                case "heic", "heix", "hevc", "hevx", "mif1", "msf1" -> "image/heic";
                case "qt  " -> "video/quicktime";
                default -> "video/mp4";
            };
        }
        return null;
    }

    public static String extension(String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/heic", "image/heif" -> ".heic";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "video/quicktime" -> ".mov";
            case "video/mp4" -> ".mp4";
            default -> ".bin";
        };
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        return at(bytes, 0, prefix);
    }

    private static boolean at(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != (expected[i] & 0xFF)) return false;
        }
        return true;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        StringBuilder text = new StringBuilder();
        for (int i = offset; i < offset + length && i < bytes.length; i++) {
            text.append((char) (bytes[i] & 0xFF));
        }
        return text.toString();
    }
}