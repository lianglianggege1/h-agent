package com.h.backend.generation.application.port.out;

/** Only a safe, user-visible message is carried across the provider boundary. */
public class MusicProviderException extends RuntimeException {
    private final boolean resultUnknown;

    public MusicProviderException(String message, boolean resultUnknown) {
        super(message);
        this.resultUnknown = resultUnknown;
    }

    public boolean resultUnknown() { return resultUnknown; }
}
