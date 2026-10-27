package com.h.backend.generation.domain.model;

public sealed interface GenerationSpec permits VideoGenerationSpec, MusicGenerationSpec {
    GenerationType generationType();
}
