package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OidcStateStore {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private final Map<String, StateContext> states = new ConcurrentHashMap<>();

    public String create(Long tenantId, String providerKey) {
        removeExpired();
        String state = UUID.randomUUID().toString();
        states.put(state, new StateContext(tenantId, providerKey, Instant.now().plus(STATE_TTL)));
        return state;
    }

    public StateContext consume(String state) {
        StateContext context = states.remove(state);
        if (context == null || context.expiresAt().isBefore(Instant.now())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC state가 유효하지 않습니다.");
        }
        return context;
    }

    private void removeExpired() {
        Instant now = Instant.now();
        states.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    public record StateContext(Long tenantId, String providerKey, Instant expiresAt) {
    }
}
