package com.h.backend.skill.domain.tar;

import com.h.backend.skill.domain.SkillFileSet;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class DeterministicSkillTarBuilder {

    public static final String BUILDER_VERSION = "skill-tar-v1";

    private static final int BLOCK_SIZE = 512;
    private static final int RECORD_SIZE = 10240;
    private static final int MAX_PATH_BYTES = 100;

    private final ObjectMapper objectMapper;

    public DeterministicSkillTarBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] build(SkillFileSet fileSet) {
        try {
            SkillBundleManifest manifest = buildManifest(fileSet);
            byte[] manifestBytes = objectMapper.writeValueAsBytes(manifest);

            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.min(fileSet.totalBytes() + manifestBytes.length + 8192L, Integer.MAX_VALUE));
            for (String path : fileSet.sortedPaths()) {
                writeEntry(out, path, fileSet.get(path));
            }
            writeEntry(out, SkillBundleManifest.MANIFEST_PATH, manifestBytes);

            long contentEnd = out.size();
            int endPadding = contentEnd % RECORD_SIZE == 0
                    ? RECORD_SIZE
                    : (int) (RECORD_SIZE - (contentEnd % RECORD_SIZE));
            out.write(new byte[endPadding], 0, endPadding);
            return out.toByteArray();
        } catch (JacksonException ex) {
            throw new IllegalStateException("构建 Skill bundle 失败", ex);
        }
    }

    private SkillBundleManifest buildManifest(SkillFileSet fileSet) {
        return new SkillBundleManifest(
                SkillBundleManifest.SCHEMA_VERSION,
                fileSet.sortedPaths().stream()
                        .map(path -> new SkillBundleManifest.Entry(
                                path,
                                fileSet.get(path).length,
                                sha256Hex(fileSet.get(path))))
                        .toList());
    }

    private void writeEntry(ByteArrayOutputStream out, String path, byte[] content) {
        byte[] nameBytes = path.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > MAX_PATH_BYTES) {
            throw new IllegalArgumentException("Skill 文件路径过长: " + path);
        }
        byte[] entryHeader = header(path, nameBytes, content.length);
        out.write(entryHeader, 0, entryHeader.length);
        out.write(content, 0, content.length);
        int padding = content.length % BLOCK_SIZE == 0 ? 0 : BLOCK_SIZE - (content.length % BLOCK_SIZE);
        out.write(new byte[padding], 0, padding);
    }

    private byte[] header(String path, byte[] nameBytes, int size) {
        byte[] header = new byte[BLOCK_SIZE];
        fill(header, 0, nameBytes);
        putOctal(header, 100, 8, 0_644L);
        putOctal(header, 108, 8, 0L);
        putOctal(header, 116, 8, 0L);
        putOctal(header, 124, 12, size);
        putOctal(header, 136, 12, 0L);
        header[148] = ' ';
        header[149] = ' ';
        header[150] = ' ';
        header[151] = ' ';
        header[152] = ' ';
        header[153] = ' ';
        header[154] = ' ';
        header[155] = ' ';
        header[156] = '0';
        byte[] magic = "ustar".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        header[262] = 0;
        header[263] = '0';
        header[264] = '0';

        long checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        String checksumText = String.format("%06o", checksum);
        byte[] checksumBytes = checksumText.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(checksumBytes, 0, header, 148, checksumBytes.length);
        header[148 + checksumBytes.length] = 0;
        header[148 + checksumBytes.length + 1] = ' ';
        return header;
    }

    private void fill(byte[] target, int offset, byte[] source) {
        System.arraycopy(source, 0, target, offset, source.length);
    }

    private void putOctal(byte[] target, int offset, int length, long value) {
        String text = Long.toOctalString(value);
        int padding = length - 1 - text.length();
        for (int i = 0; i < padding; i++) {
            target[offset + i] = '0';
        }
        byte[] textBytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(textBytes, 0, target, offset + padding, textBytes.length);
        target[offset + length - 1] = 0;
    }

    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
