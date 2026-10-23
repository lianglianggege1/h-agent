package com.h.backend.chat.infrastructure.ai.experts;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.HttpException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;

final class ExpertFailurePolicy {
    private ExpertFailurePolicy() {}

    static boolean recoverable(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (cause instanceof Error || cause instanceof CancellationException
                    || cause instanceof SecurityException || cause instanceof AuthenticationException
                    || cause instanceof HttpException http && (http.statusCode() == 401 || http.statusCode() == 403)
                    || cause instanceof ContentFilteredException || cause instanceof InvalidRequestException
                    || cause instanceof IllegalArgumentException || cause instanceof IllegalStateException
                    || cause instanceof ArithmeticException || cause instanceof NullPointerException
                    || cause instanceof ClassCastException || cause instanceof IndexOutOfBoundsException
                    || cause instanceof UnsupportedOperationException) {
                return false;
            }
        }
        return !Thread.currentThread().isInterrupted();
    }
}
