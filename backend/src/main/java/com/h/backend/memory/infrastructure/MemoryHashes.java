package com.h.backend.memory.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 记忆文本/幂等键哈希；日志与本地控制索引只保存 hash，不保存正文。 */
public final class MemoryHashes {

    private MemoryHashes() {
    }

    public static String sha256Hex(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public static String shortHash(String value) {
        String hex = sha256Hex(value);
        return hex == null ? null : hex.substring(0, 12);
    }
}
