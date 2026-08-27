package com.dwp.core.crypto;

import java.security.SecureRandom;
import java.util.Arrays;

/** Provider-independent KEK boundary. Implementations never expose key-encryption keys. */
public interface KeyProvider {

    String providerId();

    KeySlot wrapDek(byte[] plaintextDek, KeyContext context);

    byte[] unwrapDek(KeySlot keySlot, KeyContext context);

    default void probe(KeyContext context) {
        byte[] expected = new byte[32];
        byte[] actual = null;
        new SecureRandom().nextBytes(expected);
        try {
            KeySlot slot = wrapDek(expected, context);
            actual = unwrapDek(slot, context);
            if (!java.security.MessageDigest.isEqual(expected, actual)) {
                throw new KeyProviderException("Key provider cryptographic probe failed.");
            }
        } finally {
            Arrays.fill(expected, (byte) 0);
            if (actual != null) Arrays.fill(actual, (byte) 0);
        }
    }
}
