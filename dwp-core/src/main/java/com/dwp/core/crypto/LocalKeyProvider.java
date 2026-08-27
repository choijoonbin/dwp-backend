package com.dwp.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Local-only KEK keyring used by ignored developer configuration and runtime files. */
public final class LocalKeyProvider implements KeyProvider, AutoCloseable {

    public static final String INLINE_PROVIDER = "local-inline";
    public static final String FILE_PROVIDER = "local-file";
    public static final String WRAP_ALGORITHM = "A256GCMKW";

    private static final int DEK_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String providerId;
    private final String immutableKeyId;
    private final String activeVersion;
    private final Map<String, byte[]> keys;
    private final SecureRandom secureRandom;

    public LocalKeyProvider(
            String providerId,
            String immutableKeyId,
            String activeVersion,
            Map<String, byte[]> keys) {
        this(providerId, immutableKeyId, activeVersion, keys, new SecureRandom());
    }

    LocalKeyProvider(
            String providerId,
            String immutableKeyId,
            String activeVersion,
            Map<String, byte[]> keys,
            SecureRandom secureRandom) {
        if (!INLINE_PROVIDER.equals(providerId) && !FILE_PROVIDER.equals(providerId)) {
            throw new IllegalArgumentException("Local key provider type is invalid.");
        }
        this.providerId = providerId;
        this.immutableKeyId = required(immutableKeyId, "immutable key ID", 1024);
        this.activeVersion = required(activeVersion, "active version", 128);
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        if (keys == null || keys.isEmpty() || !keys.containsKey(activeVersion)) {
            throw new IllegalArgumentException("Local keyring does not contain its active version.");
        }
        Map<String, byte[]> copies = new LinkedHashMap<>();
        keys.forEach((version, key) -> {
            String validVersion = required(version, "key version", 128);
            if (key == null || key.length != DEK_BYTES) {
                throw new IllegalArgumentException("Local wrapping keys must contain 32 bytes.");
            }
            if (copies.put(validVersion, key.clone()) != null) {
                throw new IllegalArgumentException("Local keyring versions must be unique.");
            }
        });
        this.keys = copies;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public KeySlot wrapDek(byte[] plaintextDek, KeyContext context) {
        if (plaintextDek == null || plaintextDek.length != DEK_BYTES) {
            throw new KeyProviderException("Data encryption key must contain 32 bytes.");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(activeVersion), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(wrapAad(context));
            byte[] encrypted = cipher.doFinal(plaintextDek);
            byte[] wrapped = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, wrapped, 0, nonce.length);
            System.arraycopy(encrypted, 0, wrapped, nonce.length, encrypted.length);
            return new KeySlot(
                    providerId, immutableKeyId, activeVersion, WRAP_ALGORITHM, wrapped);
        } catch (GeneralSecurityException exception) {
            throw new KeyProviderException("Local data-key wrapping failed.", exception);
        }
    }

    @Override
    public byte[] unwrapDek(KeySlot keySlot, KeyContext context) {
        Objects.requireNonNull(keySlot, "keySlot");
        if (!providerId.equals(keySlot.provider())
                || !immutableKeyId.equals(keySlot.immutableKeyId())
                || !WRAP_ALGORITHM.equals(keySlot.wrapAlgorithm())) {
            throw new KeyProviderException("Wrapped data key is outside the configured allowlist.");
        }
        byte[] wrappingKey = keys.get(keySlot.keyVersion());
        if (wrappingKey == null) {
            throw new KeyProviderException("Required wrapping key version is unavailable.");
        }
        byte[] wrapped = keySlot.wrappedDek();
        if (wrapped.length != NONCE_BYTES + DEK_BYTES + TAG_BITS / 8) {
            throw new KeyProviderException("Wrapped data key length is invalid.");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(wrappingKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, wrapped, 0, NONCE_BYTES));
            cipher.updateAAD(wrapAad(context));
            byte[] plaintext = cipher.doFinal(
                    wrapped, NONCE_BYTES, wrapped.length - NONCE_BYTES);
            if (plaintext.length != DEK_BYTES) {
                Arrays.fill(plaintext, (byte) 0);
                throw new KeyProviderException("Unwrapped data key length is invalid.");
            }
            return plaintext;
        } catch (GeneralSecurityException exception) {
            throw new KeyProviderException("Local data-key unwrapping failed.", exception);
        }
    }

    private SecretKeySpec key(String version) {
        return new SecretKeySpec(keys.get(version), "AES");
    }

    private byte[] wrapAad(KeyContext context) {
        byte[] contextAad = Objects.requireNonNull(context, "context").canonicalAad();
        byte[] domain = "dwp-key-wrap-v1\n".getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[domain.length + contextAad.length];
        System.arraycopy(domain, 0, result, 0, domain.length);
        System.arraycopy(contextAad, 0, result, domain.length, contextAad.length);
        return result;
    }

    @Override
    public void close() {
        keys.values().forEach(key -> Arrays.fill(key, (byte) 0));
        keys.clear();
    }

    private static String required(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.length() > maximumLength || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Local " + field + " is invalid.");
        }
        return value;
    }
}
