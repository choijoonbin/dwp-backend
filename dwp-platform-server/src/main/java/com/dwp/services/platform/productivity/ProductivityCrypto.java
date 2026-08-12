package com.dwp.services.platform.productivity;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class ProductivityCrypto {

    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String VERSION = "v1";

    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] keyBytes;

    @Autowired
    public ProductivityCrypto(@Value("${dwp.platform.productivity.data-key:}") String encodedKey) {
        this.keyBytes = decodeKey(encodedKey);
    }

    ProductivityCrypto(byte[] keyBytes) {
        this.keyBytes = keyBytes == null ? new byte[0] : keyBytes.clone();
        validateLength(this.keyBytes);
    }

    public boolean available() {
        return keyBytes.length == 32;
    }

    public String encrypt(String plaintext, String aad) {
        requireAvailable();
        if (plaintext == null) return null;
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(requiredAad(aad));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Productivity data encryption failed.", exception);
        }
    }

    public String decrypt(String encoded, String aad) {
        requireAvailable();
        if (encoded == null) return null;
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("Unsupported encrypted productivity value.");
        }
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[2]);
            if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("Invalid encrypted value.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(requiredAad(aad));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Productivity data decryption failed.", exception);
        }
    }

    public String fingerprint(String value) {
        requireAvailable();
        if (value == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Productivity fingerprint generation failed.", exception);
        }
    }

    private SecretKey aesKey() {
        return new SecretKeySpec(keyBytes, "AES");
    }

    private byte[] requiredAad(String aad) {
        if (aad == null || aad.isBlank()) throw new IllegalArgumentException("Encryption context is required.");
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    private void requireAvailable() {
        if (!available()) throw new IllegalStateException("Productivity encryption key is not configured.");
    }

    private static byte[] decodeKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) return new byte[0];
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedKey.trim());
            validateLength(decoded);
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "DWP_PRODUCTIVITY_DATA_KEY must be a Base64 encoded 256-bit key.", exception);
        }
    }

    private static void validateLength(byte[] key) {
        if (key.length != 0 && key.length != 32) {
            throw new IllegalStateException("Productivity encryption key must be 256 bits.");
        }
    }
}
