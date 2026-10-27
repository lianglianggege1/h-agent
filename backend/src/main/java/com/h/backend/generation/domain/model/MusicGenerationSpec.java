package com.h.backend.generation.domain.model;

/** An absent lyric means that MiniMax must write it before composing the song. */
public record MusicGenerationSpec(String prompt, String lyrics, String title) implements GenerationSpec {
    public MusicGenerationSpec {
        if ((lyrics == null || lyrics.isBlank()) && (prompt == null || prompt.isBlank())) {
            throw new IllegalArgumentException("请提供已有歌词，或要创作的歌曲主题");
        }
        if (lyrics != null && lyrics.isBlank()) {
            throw new IllegalArgumentException("已有歌词不能为空；从主题创作时请省略 lyrics");
        }
        requireLength(prompt, 2000, "歌曲描述");
        requireLength(lyrics, 3500, "歌词（含段落标签）");
    }

    public static void requireLyrics(String lyrics) {
        if (lyrics == null || lyrics.isBlank()) throw new IllegalArgumentException("MiniMax 未返回有效歌词");
        requireLength(lyrics, 3500, "歌词（含段落标签）");
    }

    private static void requireLength(String text, int max, String field) {
        if (text != null && text.codePointCount(0, text.length()) > max) {
            throw new IllegalArgumentException(field + "不能超过 " + max + " 字符，请缩短后重新提交；不会自动截断");
        }
    }

    @Override
    public GenerationType generationType() { return GenerationType.TEXT_TO_AUDIO; }
}
