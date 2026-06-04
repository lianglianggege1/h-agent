package com.h.backend.knowledge.config;

import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

final class SafeRetrievalAugmentor implements RetrievalAugmentor {

    private static final Logger log = LoggerFactory.getLogger(SafeRetrievalAugmentor.class);

    private final RetrievalAugmentor delegate;

    SafeRetrievalAugmentor(RetrievalAugmentor delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public AugmentationResult augment(AugmentationRequest augmentationRequest) {
        try {
            return delegate.augment(augmentationRequest);
        } catch (RuntimeException ex) {
            if (augmentationRequest == null) {
                throw ex;
            }
            log.warn("RAG augmentation failed; continuing without retrieved content: {}", ex.toString());
            log.debug("RAG augmentation failure details", ex);
            return AugmentationResult.builder()
                    .chatMessage(augmentationRequest.chatMessage())
                    .contents(List.of())
                    .build();
        }
    }
}
