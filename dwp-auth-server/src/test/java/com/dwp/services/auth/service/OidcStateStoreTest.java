package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OidcStateStoreTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-24T05:00:00Z");

    private final Map<String, String> values = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> operations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));
        when(operations.getAndDelete(anyString()))
                .thenAnswer(invocation -> values.remove(invocation.getArgument(0)));
    }

    @Test
    void stateCanOnlyBeConsumedOnce() {
        OidcStateStore store = store(Clock.fixed(STARTED_AT, ZoneOffset.UTC));
        OidcStateStore.AuthorizationRequest authorization = store.create(1L, "provider");

        OidcStateStore.StateContext context = store.consume(authorization.state());

        assertThat(context.tenantId()).isEqualTo(1L);
        assertThat(context.providerKey()).isEqualTo("provider");
        assertThat(context.nonce()).isEqualTo(authorization.nonce());
        assertThat(context.codeVerifier()).isEqualTo(authorization.codeVerifier());
        assertThat(context.startedAt()).isEqualTo(STARTED_AT);
        assertThatThrownBy(() -> store.consume(authorization.state()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void stepUpStateKeepsOnlyOpaqueCommandAndAssuranceBindings() {
        OidcStateStore store = store(Clock.fixed(STARTED_AT, ZoneOffset.UTC));
        UUID familyId = UUID.randomUUID();

        OidcStateStore.AuthorizationRequest authorization = store.createStepUp(
                new OidcStateStore.StepUpBinding(
                        7L, "corp-idp", 19L, familyId, "session-token-id",
                        "browser-hash", "urn:dwp:acr:mfa", List.of("otp", "pwd"), 600,
                        "/approvals", "command-digest", "psr-source-revision"));
        String serialized = values.values().iterator().next();
        OidcStateStore.StateContext context = store.consume(authorization.state());

        assertThat(authorization.flowRef()).isNotBlank();
        assertThat(context.flowRef()).isEqualTo(authorization.flowRef());
        assertThat(context.sessionFamilyId()).isEqualTo(familyId);
        assertThat(context.startedAt()).isEqualTo(STARTED_AT);
        assertThat(context.commandDigest()).isEqualTo("command-digest");
        assertThat(context.sourceRevision()).isEqualTo("psr-source-revision");
        assertThat(context.acceptedAmrs()).containsExactly("otp", "pwd");
        assertThat(serialized).doesNotContain(
                "payload", "bearer", "accessToken", "authorizationUrl");
    }

    @Test
    void expiredOrSchemaDriftedStateIsConsumedAndRejected() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(STARTED_AT, STARTED_AT.plus(Duration.ofMinutes(11)));
        OidcStateStore store = store(clock);
        OidcStateStore.AuthorizationRequest expired = store.create(1L, "provider");

        assertThatThrownBy(() -> store.consume(expired.state()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> store.consume(expired.state()))
                .isInstanceOf(BaseException.class);

        OidcStateStore current = store(Clock.fixed(STARTED_AT, ZoneOffset.UTC));
        OidcStateStore.AuthorizationRequest drifted = current.create(1L, "provider");
        String key = "dwp:auth:oidc-state:" + drifted.state();
        String serialized = values.get(key);
        values.put(key, serialized.substring(0, serialized.length() - 1) + ",\"unknown\":true}");

        assertThatThrownBy(() -> current.consume(drifted.state()))
                .isInstanceOf(BaseException.class);
    }

    private OidcStateStore store(Clock clock) {
        return new OidcStateStore(redisTemplate, objectMapper, clock);
    }
}
