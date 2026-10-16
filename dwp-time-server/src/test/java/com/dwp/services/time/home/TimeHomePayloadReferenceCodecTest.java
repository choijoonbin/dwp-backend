package com.dwp.services.time.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.services.time.home.TimeHomePayloadReferenceCodec.InvalidPayloadReferenceException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TimeHomePayloadReferenceCodecTest {

    private final TimeHomePayloadReferenceCodec codec =
            new TimeHomePayloadReferenceCodec("s".repeat(32).getBytes());

    @Test
    void roundTripsCanonicalTenantIssuedAtAndDigestWithinOpaqueBound() {
        Instant issuedAt = Instant.parse("2026-10-16T09:00:00Z");

        String reference = codec.issue(7, issuedAt, "a".repeat(64));
        var decoded = codec.decode(reference);

        assertThat(reference).matches("^href_[A-Za-z0-9_-]{16,128}$");
        assertThat(decoded.tenantId()).isEqualTo(7);
        assertThat(decoded.issuedAt()).isEqualTo(issuedAt);
        assertThat(decoded.payloadDigest()).isEqualTo("a".repeat(64));
    }

    @Test
    void rejectsTamperingMalformedReferencesAndWeakKeys() {
        String reference = codec.issue(7, TimeHomeTestFixtures.NOW, "b".repeat(64));
        char replacement = reference.endsWith("A") ? 'B' : 'A';
        String tampered = reference.substring(0, reference.length() - 1) + replacement;

        assertThatThrownBy(() -> codec.decode(tampered))
                .isInstanceOf(InvalidPayloadReferenceException.class);
        assertThatThrownBy(() -> codec.decode("href_not-canonical"))
                .isInstanceOf(InvalidPayloadReferenceException.class);
        assertThatThrownBy(() -> new TimeHomePayloadReferenceCodec(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
