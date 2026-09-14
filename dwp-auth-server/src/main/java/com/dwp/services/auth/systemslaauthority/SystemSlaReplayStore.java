package com.dwp.services.auth.systemslaauthority;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class SystemSlaReplayStore {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            redis.call('SET', KEYS[1], 'used', 'PX', ARGV[1])
            redis.call('SET', KEYS[2], 'used', 'PX', ARGV[1])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;
    public SystemSlaReplayStore(StringRedisTemplate redis, Clock clock) { this.redis = redis; this.clock = clock; }
    public void ready() {
        if (redis == null) throw SystemSlaJson.unavailable();
        try { if (!"PONG".equals(redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping()))) throw SystemSlaJson.unavailable(); }
        catch (RuntimeException error) { throw SystemSlaJson.unavailable(); }
    }
    public void consume(SystemSlaProofVerifier.Verified proof, Instant expiry) {
        if (redis == null) throw SystemSlaJson.unavailable();
        if (expiry == null || expiry.isAfter(proof.expiresAt())) throw SystemSlaJson.denied();
        Instant now = clock.instant();
        if (!expiry.isAfter(now)) throw SystemSlaJson.denied();
        // Consumed proofs outlive a shorter source/transport window so the owner cannot be rewrapped.
        Instant retainedUntil = proof.ownerExpiresAt().isAfter(proof.expiresAt()) ? proof.ownerExpiresAt() : proof.expiresAt();
        long ttl = Duration.between(now, retainedUntil).toMillis();
        if (now.plusMillis(ttl).isBefore(retainedUntil)) ttl++;
        if (ttl < 1 || ttl > 30000) throw SystemSlaJson.denied();
        String prefix = "dwp:approval-system-sla:{" + proof.bindings().tenantId() + ":" + SystemSlaProtocol.OWNER_PURPOSE + "}:";
        final Long result;
        try { result = redis.execute(CONSUME, List.of(prefix + "owner:" + proof.ownerJti(), prefix + "transport:" + proof.transportJti()), Long.toString(ttl)); }
        catch (RuntimeException error) { throw SystemSlaJson.unavailable(); }
        if (result == null) throw SystemSlaJson.unavailable(); if (result != 1) throw SystemSlaJson.denied();
    }
}
