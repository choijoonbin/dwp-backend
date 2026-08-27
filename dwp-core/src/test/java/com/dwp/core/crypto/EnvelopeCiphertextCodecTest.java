package com.dwp.core.crypto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeCiphertextCodecTest {

    private static final String GOLDEN_ENVELOPE =
            "dwp2.RFdQMgAAAAdBMjU2R0NNAAAACmR3cC1hYWQtdjEAAAAMbG9jYWwtaW5saW5l"
            + "AAAAGWxvY2FsOi8vZHdwLWFnZW50L3BheWxvYWQAAAAIbG9jYWwtdjEAAAAJQTI1"
            + "NkdDTUtXAAAAPBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEB"
            + "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEAAAAAwgICAgICAgICAgICAAAAAQMDAwMD"
            + "AwMDAwMDAwMDAwMAAAACBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQA";

    private final EnvelopeCiphertextCodec codec = new EnvelopeCiphertextCodec();

    @Test
    void preservesCanonicalEnvelopeMetadata() {
        EnvelopeCiphertext envelope = fixture();

        String encoded = codec.encode(envelope);
        EnvelopeCiphertext decoded = codec.decode(encoded);

        assertThat(encoded).startsWith(EnvelopeCiphertextCodec.PREFIX);
        assertThat(decoded.formatVersion()).isEqualTo(2);
        assertThat(decoded.contentAlgorithm()).isEqualTo("A256GCM");
        assertThat(decoded.aadProfile()).isEqualTo("dwp-aad-v1");
        assertThat(decoded.keySlot().provider()).isEqualTo("local-inline");
        assertThat(decoded.keySlot().immutableKeyId()).isEqualTo("local://dwp-agent/payload");
        assertThat(decoded.keySlot().keyVersion()).isEqualTo("local-v1");
        assertThat(decoded.keySlot().wrappedDek()).isEqualTo(envelope.keySlot().wrappedDek());
        assertThat(decoded.nonce()).isEqualTo(envelope.nonce());
        assertThat(decoded.ciphertext()).isEqualTo(envelope.ciphertext());
        assertThat(decoded.aadSha256()).isEqualTo(envelope.aadSha256());
    }

    @Test
    void matchesThePythonGoldenEnvelope() {
        assertThat(codec.encode(fixture())).isEqualTo(GOLDEN_ENVELOPE);
        assertThat(codec.encode(codec.decode(GOLDEN_ENVELOPE))).isEqualTo(GOLDEN_ENVELOPE);
    }

    @Test
    void rejectsUnknownPrefixTruncationAndTrailingData() {
        String encoded = codec.encode(fixture());

        assertThatThrownBy(() -> codec.decode("v1." + encoded))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(encoded.substring(0, encoded.length() - 2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(encoded + "AA"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static EnvelopeCiphertext fixture() {
        return new EnvelopeCiphertext(
                2,
                "A256GCM",
                "dwp-aad-v1",
                new KeySlot(
                        "local-inline",
                        "local://dwp-agent/payload",
                        "local-v1",
                        "A256GCMKW",
                        filled(60, 0x10)),
                filled(12, 0x20),
                filled(16, 0x30),
                filled(32, 0x40));
    }

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
