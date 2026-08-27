package com.dwp.core.crypto;

import java.util.Objects;

/** Strict DWP envelope v2. Context is reconstructed from trusted application data. */
public record EnvelopeCiphertext(
        int formatVersion,
        String contentAlgorithm,
        String aadProfile,
        KeySlot keySlot,
        byte[] nonce,
        byte[] ciphertext,
        byte[] aadSha256) {

    public static final int FORMAT_VERSION = 2;
    public static final String CONTENT_ALGORITHM = "A256GCM";
    private static final int MAX_CIPHERTEXT_BYTES = 16 * 1024 * 1024;

    public EnvelopeCiphertext {
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported encryption envelope version.");
        }
        if (!CONTENT_ALGORITHM.equals(contentAlgorithm)) {
            throw new IllegalArgumentException("Unsupported content encryption algorithm.");
        }
        if (!KeyContext.AAD_PROFILE.equals(aadProfile)) {
            throw new IllegalArgumentException("Unsupported encryption AAD profile.");
        }
        Objects.requireNonNull(keySlot, "keySlot");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(aadSha256, "aadSha256");
        if (nonce.length != 12) {
            throw new IllegalArgumentException("Envelope nonce must contain 12 bytes.");
        }
        if (ciphertext.length < 16 || ciphertext.length > MAX_CIPHERTEXT_BYTES) {
            throw new IllegalArgumentException("Envelope ciphertext length is invalid.");
        }
        if (aadSha256.length != 32) {
            throw new IllegalArgumentException("Envelope AAD digest must contain 32 bytes.");
        }
        nonce = nonce.clone();
        ciphertext = ciphertext.clone();
        aadSha256 = aadSha256.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] aadSha256() {
        return aadSha256.clone();
    }
}
