package com.dwp.core.crypto;

import java.util.Objects;

/** Opaque wrapped data-key metadata persisted next to ciphertext. */
public record KeySlot(
        String provider,
        String immutableKeyId,
        String keyVersion,
        String wrapAlgorithm,
        byte[] wrappedDek) {

    public KeySlot {
        provider = required(provider, "provider", 64);
        immutableKeyId = required(immutableKeyId, "immutable key ID", 1024);
        if (keyVersion != null) {
            keyVersion = required(keyVersion, "key version", 128);
        }
        wrapAlgorithm = required(wrapAlgorithm, "wrap algorithm", 64);
        Objects.requireNonNull(wrappedDek, "wrappedDek");
        if (wrappedDek.length < 16 || wrappedDek.length > 16_384) {
            throw new IllegalArgumentException("Wrapped data key length is invalid.");
        }
        wrappedDek = wrappedDek.clone();
    }

    @Override
    public byte[] wrappedDek() {
        return wrappedDek.clone();
    }

    private static String required(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Key slot " + field + " is invalid.");
        }
        return value;
    }
}
