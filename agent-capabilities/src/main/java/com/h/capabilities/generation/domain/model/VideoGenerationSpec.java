package com.h.capabilities.generation.domain.model;

public sealed interface VideoGenerationSpec permits TextToVideoSpec, ImageToVideoSpec, VideoV2Spec {

    GenerationType generationType();
}
