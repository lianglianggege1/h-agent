package com.h.backend.generation.domain.model;

public sealed interface VideoGenerationSpec permits TextToVideoSpec, ImageToVideoSpec {

    GenerationType generationType();
}
