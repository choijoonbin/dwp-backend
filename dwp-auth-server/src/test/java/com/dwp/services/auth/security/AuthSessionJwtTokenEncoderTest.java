package com.dwp.services.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AuthSessionJwtTokenEncoderTest {

    private static final String SECRET =
            "test_secret_key_that_is_at_least_256_bits_long_for_hs256";

    @Test
    void concurrentFirstUseEncodesAndVerifiesEveryToken() throws Exception {
        AuthSessionJwtTokenEncoder encoder = encoder();
        int concurrency = 32;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<Claims>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < concurrency; index++) {
                int tokenIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent token start timed out.");
                    }
                    String token = encoder.encode(claims(tokenIndex));
                    return encoder.decodeAndVerify(token);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (int index = 0; index < concurrency; index++) {
                Claims decoded = futures.get(index).get(10, TimeUnit.SECONDS);
                assertThat(decoded.getId()).isEqualTo("concurrent-token-" + index);
                assertThat(decoded.getSubject()).isEqualTo(String.valueOf(10_000 + index));
                assertThat(decoded.get("roles")).isEqualTo(List.of("EMPLOYEE"));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void startupProbeCompletesAnActualSignedRoundTrip() {
        assertThatCode(() -> encoder().verifyStartupReadiness()).doesNotThrowAnyException();
    }

    @Test
    void preservesTheExistingSessionAndAssuranceClaimContract() {
        AuthSessionJwtTokenEncoder encoder = encoder();
        AuthSessionJwtTokenEncoder.SessionTokenClaims expected = claims(7);

        Claims decoded = encoder.decodeAndVerify(encoder.encode(expected));

        assertThat(decoded.getId()).isEqualTo(expected.tokenId());
        assertThat(decoded.getSubject()).isEqualTo(String.valueOf(expected.userId()));
        assertThat(decoded.get("tenant_id")).isEqualTo(String.valueOf(expected.tenantId()));
        assertThat(decoded.get("roles")).isEqualTo(expected.roles());
        assertThat(decoded.get("sid")).isEqualTo(expected.familyId().toString());
        assertThat(decoded.get("auth_time", Long.class))
                .isEqualTo(expected.authenticatedAt().getEpochSecond());
        assertThat(decoded.get("acr")).isEqualTo(expected.acr());
        assertThat(decoded.get("amr")).isEqualTo(expected.amr());
    }

    private static AuthSessionJwtTokenEncoder encoder() {
        return new AuthSessionJwtTokenEncoder(
                SECRET, new ObjectMapper().findAndRegisterModules());
    }

    private static AuthSessionJwtTokenEncoder.SessionTokenClaims claims(int index) {
        Instant now = Instant.now();
        return new AuthSessionJwtTokenEncoder.SessionTokenClaims(
                10_000L + index,
                1L,
                List.of("EMPLOYEE"),
                "concurrent-token-" + index,
                UUID.randomUUID(),
                now,
                now.plusSeconds(60),
                now,
                "urn:dwp:acr:password",
                List.of("pwd"));
    }
}
