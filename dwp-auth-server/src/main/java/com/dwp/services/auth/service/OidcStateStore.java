package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Component
public class OidcStateStore {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String KEY_PREFIX = "dwp:auth:oidc-state:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public OidcStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, Clock.systemUTC());
    }

    OidcStateStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            Clock clock) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AuthorizationRequest create(Long tenantId, String providerKey) {
        return create(new StateBinding(
                Purpose.LOGIN, null, tenantId, providerKey, null, null, null,
                null, null, List.of(), null, null, null, null));
    }

    public AuthorizationRequest createStepUp(StepUpBinding binding) {
        return create(new StateBinding(
                Purpose.STEP_UP, UUID.randomUUID().toString(), binding.tenantId(),
                binding.providerKey(), binding.actorId(), binding.sessionFamilyId(),
                binding.tokenId(), binding.browserBinding(), binding.requiredAcr(),
                binding.acceptedAmrs(), binding.maximumAgeSeconds(), binding.returnPath(),
                binding.commandDigest(), binding.sourceRevision()));
    }

    private AuthorizationRequest create(StateBinding binding) {
        String state = randomValue(32);
        String nonce = randomValue(24);
        String codeVerifier = randomValue(48);
        Instant startedAt = clock.instant();
        Instant expiresAt = startedAt.plus(STATE_TTL);
        StateContext context = new StateContext(
                binding.purpose(), binding.flowRef(), binding.tenantId(), binding.providerKey(),
                nonce, codeVerifier, binding.actorId(), binding.sessionFamilyId(),
                binding.tokenId(), binding.browserBinding(), binding.requiredAcr(),
                binding.acceptedAmrs(), binding.maximumAgeSeconds(), binding.returnPath(),
                binding.commandDigest(), binding.sourceRevision(), startedAt, expiresAt);
        try {
            redisTemplate.opsForValue().set(
                    key(state), objectMapper.writeValueAsString(context), STATE_TTL);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("OIDC state cannot be serialized.", exception);
        }
        return new AuthorizationRequest(
                state, nonce, codeVerifier, startedAt, expiresAt, binding.flowRef());
    }

    public StateContext consume(String state) {
        if (state == null || state.isBlank()) throw invalidState();
        String serialized = redisTemplate.opsForValue().getAndDelete(key(state));
        if (serialized == null) throw invalidState();
        try {
            StateContext context = objectMapper.readValue(serialized, StateContext.class);
            validate(context);
            return context;
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC state is invalid.", exception);
        }
    }

    private void validate(StateContext context) {
        Instant now = clock.instant();
        if (context == null || context.purpose() == null
                || context.startedAt() == null || context.expiresAt() == null
                || context.startedAt().isAfter(now.plusSeconds(30))
                || !context.expiresAt().isAfter(now)) {
            throw invalidState();
        }
        if (context.purpose() == Purpose.STEP_UP
                && (blank(context.flowRef()) || context.actorId() == null
                || context.sessionFamilyId() == null || blank(context.tokenId())
                || blank(context.browserBinding()) || blank(context.requiredAcr())
                || context.acceptedAmrs().isEmpty() || context.maximumAgeSeconds() == null
                || context.maximumAgeSeconds() <= 0 || blank(context.commandDigest())
                || blank(context.sourceRevision()))) {
            throw invalidState();
        }
    }

    private String key(String state) {
        return KEY_PREFIX + state;
    }

    private static String randomValue(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BaseException invalidState() {
        return new BaseException(ErrorCode.INVALID_STATE, "OIDC state is invalid.");
    }

    public record AuthorizationRequest(
            String state,
            String nonce,
            String codeVerifier,
            Instant startedAt,
            Instant expiresAt,
            String flowRef) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record StateContext(
            Purpose purpose,
            String flowRef,
            Long tenantId,
            String providerKey,
            String nonce,
            String codeVerifier,
            Long actorId,
            UUID sessionFamilyId,
            String tokenId,
            String browserBinding,
            String requiredAcr,
            List<String> acceptedAmrs,
            Integer maximumAgeSeconds,
            String returnPath,
            String commandDigest,
            String sourceRevision,
            Instant startedAt,
            Instant expiresAt) {

        public StateContext {
            acceptedAmrs = acceptedAmrs == null ? List.of() : List.copyOf(acceptedAmrs);
            if (purpose == Purpose.STEP_UP) {
                acceptedAmrs = OidcStepUpAmrPolicy.normalizeProviderPolicy(acceptedAmrs);
            }
            if ((purpose == Purpose.STEP_UP && acceptedAmrs.isEmpty())
                    || (purpose == Purpose.LOGIN && !acceptedAmrs.isEmpty())) {
                throw new IllegalArgumentException("OIDC state AMR allowlist is invalid.");
            }
        }
    }

    public enum Purpose {
        LOGIN,
        STEP_UP
    }

    private record StateBinding(
            Purpose purpose,
            String flowRef,
            Long tenantId,
            String providerKey,
            Long actorId,
            UUID sessionFamilyId,
            String tokenId,
            String browserBinding,
            String requiredAcr,
            List<String> acceptedAmrs,
            Integer maximumAgeSeconds,
            String returnPath,
            String commandDigest,
            String sourceRevision) {
    }

    public record StepUpBinding(
            Long tenantId,
            String providerKey,
            Long actorId,
            UUID sessionFamilyId,
            String tokenId,
            String browserBinding,
            String requiredAcr,
            List<String> acceptedAmrs,
            int maximumAgeSeconds,
            String returnPath,
            String commandDigest,
            String sourceRevision) {

        public StepUpBinding {
            acceptedAmrs = acceptedAmrs == null ? List.of() : List.copyOf(acceptedAmrs);
        }
    }
}
