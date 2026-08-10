package com.dwp.services.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class OidcStateStore {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final String KEY_PREFIX = "dwp:auth:oidc-state:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public OidcStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public AuthorizationRequest create(Long tenantId, String providerKey) {
        String state = randomValue(32);
        String nonce = randomValue(24);
        String codeVerifier = randomValue(48);
        StateContext context = new StateContext(
                tenantId, providerKey, nonce, codeVerifier, Instant.now().plus(STATE_TTL));
        try {
            redisTemplate.opsForValue().set(key(state), objectMapper.writeValueAsString(context), STATE_TTL);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("OIDC state를 직렬화할 수 없습니다.", exception);
        }
        return new AuthorizationRequest(state, nonce, codeVerifier);
    }

    public StateContext consume(String state) {
        if (state == null || state.isBlank()) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC state가 유효하지 않습니다.");
        }
        String serialized = redisTemplate.opsForValue().getAndDelete(key(state));
        if (serialized == null) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC state가 유효하지 않습니다.");
        }
        try {
            StateContext context = objectMapper.readValue(serialized, StateContext.class);
            if (context.expiresAt().isBefore(Instant.now())) {
                throw new BaseException(ErrorCode.INVALID_STATE, "OIDC state가 유효하지 않습니다.");
            }
            return context;
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC state가 유효하지 않습니다.", exception);
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

    public record AuthorizationRequest(String state, String nonce, String codeVerifier) {
    }

    public record StateContext(
            Long tenantId,
            String providerKey,
            String nonce,
            String codeVerifier,
            Instant expiresAt) {
    }
}
