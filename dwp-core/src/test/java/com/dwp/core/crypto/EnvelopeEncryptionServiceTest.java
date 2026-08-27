package com.dwp.core.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeEncryptionServiceTest {

    private static final KeyContext CONTEXT = new KeyContext(
            "local", "dwp-agent", "payload", 42, "message", "m-7", "body");

    @Test
    void canonicalAadIsStableAcrossRuntimes() {
        assertThat(new String(CONTEXT.canonicalAad(), StandardCharsets.US_ASCII))
                .isEqualTo("dwp-aad-v1.bG9jYWw.ZHdwLWFnZW50.cGF5bG9hZA.NDI."
                        + "bWVzc2FnZQ.bS03.Ym9keQ.Mg");
        assertThat(CONTEXT.providerContext())
                .doesNotContainKeys("resourceId", "field")
                .containsEntry("tenantId", "42");
    }

    @Test
    void encryptsWithANewDekAndNonceForEveryWrite() {
        LocalKeyProvider provider = provider(
                LocalKeyProvider.INLINE_PROVIDER, "local://agent/payload", "v1", 1);
        EnvelopeEncryptionService service = new EnvelopeEncryptionService(provider);

        EnvelopeCiphertext first = service.encrypt(bytes("same content"), CONTEXT);
        EnvelopeCiphertext second = service.encrypt(bytes("same content"), CONTEXT);

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.keySlot().wrappedDek()).isNotEqualTo(second.keySlot().wrappedDek());
        assertThat(service.decrypt(first, CONTEXT)).isEqualTo(bytes("same content"));
        assertThat(service.decrypt(second, CONTEXT)).isEqualTo(bytes("same content"));
    }

    @Test
    void rejectsRowExchangeAndMetadataProviderSelection() {
        LocalKeyProvider provider = provider(
                LocalKeyProvider.INLINE_PROVIDER, "local://agent/payload", "v1", 2);
        EnvelopeEncryptionService service = new EnvelopeEncryptionService(provider);
        EnvelopeCiphertext envelope = service.encrypt(bytes("restricted"), CONTEXT);
        KeyContext exchanged = new KeyContext(
                "local", "dwp-agent", "payload", 43, "message", "m-7", "body");

        assertThatThrownBy(() -> service.decrypt(envelope, exchanged))
                .isInstanceOf(KeyProviderException.class)
                .hasMessageContaining("trusted resource");

        KeySlot attackerSlot = new KeySlot(
                envelope.keySlot().provider(),
                "https://attacker.invalid/key",
                envelope.keySlot().keyVersion(),
                envelope.keySlot().wrapAlgorithm(),
                envelope.keySlot().wrappedDek());
        EnvelopeCiphertext attackerEnvelope = copyWithSlot(envelope, attackerSlot);

        assertThatThrownBy(() -> service.decrypt(attackerEnvelope, CONTEXT))
                .isInstanceOf(KeyProviderException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void readsPreviousWrappingVersionsAndRewrapsWithoutChangingContentBytes() {
        LocalKeyProvider oldProvider = provider(
                LocalKeyProvider.INLINE_PROVIDER, "local://agent/payload", "v1", 3);
        EnvelopeEncryptionService oldService = new EnvelopeEncryptionService(oldProvider);
        EnvelopeCiphertext oldEnvelope = oldService.encrypt(bytes("retained"), CONTEXT);

        LocalKeyProvider rotatedProvider = new LocalKeyProvider(
                LocalKeyProvider.INLINE_PROVIDER,
                "local://agent/payload",
                "v2",
                Map.of("v1", key(3), "v2", key(4)));
        EnvelopeEncryptionService rotatedService = new EnvelopeEncryptionService(rotatedProvider);
        assertThat(rotatedService.decrypt(oldEnvelope, CONTEXT)).isEqualTo(bytes("retained"));

        EnvelopeCiphertext rewrapped = rotatedService.rewrap(oldEnvelope, CONTEXT, rotatedProvider);
        assertThat(rewrapped.keySlot().keyVersion()).isEqualTo("v2");
        assertThat(rewrapped.nonce()).isEqualTo(oldEnvelope.nonce());
        assertThat(rewrapped.ciphertext()).isEqualTo(oldEnvelope.ciphertext());
        assertThat(rotatedService.decrypt(rewrapped, CONTEXT)).isEqualTo(bytes("retained"));
    }

    @Test
    void failsClosedWhenWrappedKeyOrTagIsTampered() {
        LocalKeyProvider provider = provider(
                LocalKeyProvider.INLINE_PROVIDER, "local://agent/payload", "v1", 5);
        EnvelopeEncryptionService service = new EnvelopeEncryptionService(provider);
        EnvelopeCiphertext envelope = service.encrypt(bytes("protected"), CONTEXT);
        byte[] wrapped = envelope.keySlot().wrappedDek();
        wrapped[wrapped.length - 1] ^= 1;
        EnvelopeCiphertext tamperedKey = copyWithSlot(envelope, new KeySlot(
                envelope.keySlot().provider(),
                envelope.keySlot().immutableKeyId(),
                envelope.keySlot().keyVersion(),
                envelope.keySlot().wrapAlgorithm(),
                wrapped));
        assertThatThrownBy(() -> service.decrypt(tamperedKey, CONTEXT))
                .isInstanceOf(KeyProviderException.class);

        byte[] ciphertext = envelope.ciphertext();
        ciphertext[ciphertext.length - 1] ^= 1;
        EnvelopeCiphertext tamperedContent = new EnvelopeCiphertext(
                envelope.formatVersion(),
                envelope.contentAlgorithm(),
                envelope.aadProfile(),
                envelope.keySlot(),
                envelope.nonce(),
                ciphertext,
                envelope.aadSha256());
        assertThatThrownBy(() -> service.decrypt(tamperedContent, CONTEXT))
                .isInstanceOf(KeyProviderException.class);
    }

    private static EnvelopeCiphertext copyWithSlot(
            EnvelopeCiphertext envelope, KeySlot keySlot) {
        return new EnvelopeCiphertext(
                envelope.formatVersion(),
                envelope.contentAlgorithm(),
                envelope.aadProfile(),
                keySlot,
                envelope.nonce(),
                envelope.ciphertext(),
                envelope.aadSha256());
    }

    private static LocalKeyProvider provider(
            String provider, String keyId, String version, int fill) {
        return new LocalKeyProvider(provider, keyId, version, Map.of(version, key(fill)));
    }

    private static byte[] key(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
