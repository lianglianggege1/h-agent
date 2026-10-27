package com.h.backend.generation.domain.model;

public sealed interface VideoGenerationSpec extends GenerationSpec permits TextToVideoSpec, ImageToVideoSpec, VideoV2Spec {

    GenerationType generationType();
}
