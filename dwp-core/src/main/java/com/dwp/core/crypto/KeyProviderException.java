package com.dwp.core.crypto;

/** Safe configuration or cryptographic boundary failure without key material details. */
public class KeyProviderException extends RuntimeException {

    public KeyProviderException(String message) {
        super(message);
    }

    public KeyProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
