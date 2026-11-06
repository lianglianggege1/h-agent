package com.h.backend.chat.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import tools.jackson.databind.ObjectMapper;

/** Compacts HTTP log output without changing the request or the framework's header masking. */
public final class CompactHttpLogger extends LegacyAbstractLogger {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Logger delegate;

    public CompactHttpLogger(Logger delegate) {
        this.delegate = delegate;
        this.name = delegate.getName();
    }

    @Override public boolean isTraceEnabled() { return delegate.isTraceEnabled(); }
    @Override public boolean isDebugEnabled() { return delegate.isDebugEnabled(); }
    @Override public boolean isInfoEnabled() { return delegate.isInfoEnabled(); }
    @Override public boolean isWarnEnabled() { return delegate.isWarnEnabled(); }
    @Override public boolean isErrorEnabled() { return delegate.isErrorEnabled(); }

    @Override
    protected String getFullyQualifiedCallerName() {
        return CompactHttpLogger.class.getName();
    }

    @Override
    protected void handleNormalizedLoggingCall(
            Level level, Marker marker, String pattern, Object[] arguments, Throwable cause) {
        Object[] compact = arguments == null ? new Object[0] : arguments.clone();
        for (int i = 0; i < compact.length; i++)
            if (compact[i] instanceof String text) compact[i] = compact(text);
        var event = delegate.atLevel(level).setCause(cause);
        if (marker != null) event.addMarker(marker);
        event.log(pattern == null ? null : pattern.replaceAll("\\s*\\R\\s*", " ").trim(), compact);
    }

    private static String compact(String text) {
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.writeValueAsString(JSON.readTree(text));
            } catch (Exception notJson) {
                // Headers, SSE data and plain response bodies need no JSON parsing.
            }
        }
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }
}
