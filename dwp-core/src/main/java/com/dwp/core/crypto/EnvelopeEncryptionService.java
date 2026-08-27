package com.dwp.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/** Application-layer envelope encryption with one CSPRNG data key per write. */
public final class EnvelopeEncryptionService {

    private static final int DEK_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final KeyProvider keyProvider;
    private final SecureRandom secureRandom;

    public EnvelopeEncryptionService(KeyProvider keyProvider) {
        this(keyProvider, new SecureRandom());
    }

    EnvelopeEncryptionService(KeyProvider keyProvider, SecureRandom secureRandom) {
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public EnvelopeCiphertext encrypt(byte[] plaintext, KeyContext context) {
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] aad = Objects.requireNonNull(context, "context").canonicalAad();
        byte[] dek = new byte[DEK_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(dek);
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = contentCipher(Cipher.ENCRYPT_MODE, dek, nonce, aad);
            byte[] ciphertext = cipher.doFinal(plaintext);
            KeySlot keySlot = keyProvider.wrapDek(dek, context);
            return new EnvelopeCiphertext(
                    EnvelopeCiphertext.FORMAT_VERSION,
                    EnvelopeCiphertext.CONTENT_ALGORITHM,
                    KeyContext.AAD_PROFILE,
                    keySlot,
                    nonce,
                    ciphertext,
                    sha256(aad));
        } catch (GeneralSecurityException exception) {
            throw new KeyProviderException("Envelope content encryption failed.", exception);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public byte[] decrypt(EnvelopeCiphertext envelope, KeyContext context) {
        Objects.requireNonNull(envelope, "envelope");
        byte[] aad = Objects.requireNonNull(context, "context").canonicalAad();
        if (!MessageDigest.isEqual(envelope.aadSha256(), sha256(aad))) {
            throw new KeyProviderException("Envelope context does not match the trusted resource.");
        }
        byte[] dek = keyProvider.unwrapDek(envelope.keySlot(), context);
        try {
            Cipher cipher = contentCipher(Cipher.DECRYPT_MODE, dek, envelope.nonce(), aad);
            return cipher.doFinal(envelope.ciphertext());
        } catch (GeneralSecurityException exception) {
            throw new KeyProviderException("Envelope content decryption failed.", exception);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Rewraps only the DEK; content bytes and their stable AAD remain unchanged. */
    public EnvelopeCiphertext rewrap(
            EnvelopeCiphertext envelope,
            KeyContext context,
            KeyProvider targetProvider) {
        Objects.requireNonNull(targetProvider, "targetProvider");
        byte[] aad = Objects.requireNonNull(context, "context").canonicalAad();
        if (!MessageDigest.isEqual(envelope.aadSha256(), sha256(aad))) {
            throw new KeyProviderException("Envelope context does not match the trusted resource.");
        }
        byte[] dek = keyProvider.unwrapDek(envelope.keySlot(), context);
        try {
            return new EnvelopeCiphertext(
                    envelope.formatVersion(),
                    envelope.contentAlgorithm(),
                    envelope.aadProfile(),
                    targetProvider.wrapDek(dek, context),
                    envelope.nonce(),
                    envelope.ciphertext(),
                    envelope.aadSha256());
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public void probe(KeyContext context) {
        keyProvider.probe(context);
    }

    private Cipher contentCipher(int mode, byte[] key, byte[] nonce, byte[] aad)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher;
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
