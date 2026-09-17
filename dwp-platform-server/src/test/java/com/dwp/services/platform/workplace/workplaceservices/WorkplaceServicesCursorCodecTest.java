package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class WorkplaceServicesCursorCodecTest {
    private static final String SECRET = "screen18-cursor-test-secret-32-bytes-minimum";
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    @Test
    void cursorRoundTripsWithoutExposingScopeTenantUserOrOrderIdentifiers() {
        WorkplaceServicesCursorCodec codec = new WorkplaceServicesCursorCodec(
                SECRET, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        UUID orderId = UUID.fromString("92d2d639-73c4-462a-ae55-b35a04d42611");
        String scope = "events:user:18001:" + orderId;
        OffsetDateTime position = OffsetDateTime.ofInstant(NOW.minusSeconds(3), ZoneOffset.UTC);

        String token = codec.encode(9_958_001L, scope, position, orderId);
        assertThat(codec.decode(token, 9_958_001L, scope))
                .isEqualTo(new WorkplaceServicesCursorCodec.CursorPosition(position, orderId));

        String[] parts = token.split("\\.");
        String observable = token + new String(Base64.getUrlDecoder().decode(parts[1]),
                StandardCharsets.ISO_8859_1)
                + new String(Base64.getUrlDecoder().decode(parts[2]),
                StandardCharsets.ISO_8859_1);
        assertThat(observable)
                .doesNotContain("9958001")
                .doesNotContain("18001")
                .doesNotContain(orderId.toString())
                .doesNotContain("events:user");
    }

    @Test
    void cursorRejectsTamperingScopeTenantAndExpiry() {
        Clock encodedAt = Clock.fixed(NOW, ZoneOffset.UTC);
        UUID id = UUID.randomUUID();
        String scope = "orders:user:18001";
        String token = new WorkplaceServicesCursorCodec(SECRET, encodedAt)
                .encode(42, scope, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), id);

        assertInvalid(() -> new WorkplaceServicesCursorCodec(SECRET, encodedAt)
                .decode(token, 43, scope));
        assertInvalid(() -> new WorkplaceServicesCursorCodec(SECRET, encodedAt)
                .decode(token, 42, "orders:user:18002"));
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;
        assertInvalid(() -> new WorkplaceServicesCursorCodec(SECRET, encodedAt)
                .decode(tampered, 42, scope));
        assertInvalid(() -> new WorkplaceServicesCursorCodec(SECRET,
                Clock.fixed(NOW.plusSeconds(3601), ZoneOffset.UTC)).decode(token, 42, scope));
        assertInvalid(() -> new WorkplaceServicesCursorCodec(SECRET, encodedAt)
                .decode("x".repeat(2_049), 42, scope));
    }

    @Test
    void productionStartupFailsWithoutSecretWhileLocalRequestsRemainFailClosed() {
        WorkplaceServicesCursorCodec production = new WorkplaceServicesCursorCodec(
                "short", Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(), "production");
        assertThatThrownBy(production::validateProductionReadiness)
                .isInstanceOf(IllegalStateException.class);

        WorkplaceServicesCursorCodec local = new WorkplaceServicesCursorCodec(
                "", Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(), "local");
        assertThatCode(local::validateProductionReadiness).doesNotThrowAnyException();
        assertThatThrownBy(() -> local.encode(42, "orders:user:1",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), UUID.randomUUID()))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
    }

    private static void assertInvalid(ThrowingCallable work) {
        assertThatThrownBy(work)
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }
}
