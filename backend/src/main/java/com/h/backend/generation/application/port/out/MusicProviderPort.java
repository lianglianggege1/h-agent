package com.h.backend.generation.application.port.out;

import com.h.backend.generation.domain.model.MusicGenerationSpec;

public interface MusicProviderPort {
    Lyrics writeLyrics(MusicGenerationSpec spec);
    byte[] sing(String lyrics, String prompt);

    record Lyrics(String title, String styleTags, String lyrics) {}
}
