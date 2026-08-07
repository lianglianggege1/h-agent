package com.h.backend.generation.application.port.out;

/**
 * Indicates that the provider permanently rejected a generation request.
 * These failures must not be sent back through the polling retry loop.
 */
public final class ProviderTaskRejectedException extends RuntimeException {
    private final int providerStatusCode;

    public ProviderTaskRejectedException(int providerStatusCode, String message) {
        super(message);
        this.providerStatusCode = providerStatusCode;
    }

    public int providerStatusCode() {
        return providerStatusCode;
    }
}
