package com.dwp.services.auth.approvalpolicyimpact;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class PolicyImpactReplayStore {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            redis.call('SET', KEYS[1], 'used', 'PX', ARGV[1])
            redis.call('SET', KEYS[2], 'used', 'PX', ARGV[1])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;
    public PolicyImpactReplayStore(StringRedisTemplate redis, Clock clock) { this.redis = redis; this.clock = clock; }
    public void requireReady() {
        if (redis == null) throw PolicyImpactJson.unavailable();
        try {
            String result = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
            if (!"PONG".equals(result)) throw PolicyImpactJson.unavailable();
        } catch (RuntimeException error) { throw PolicyImpactJson.unavailable(); }
    }
    public void consume(PolicyImpactProofVerifier.Verified proof, java.time.Instant authorityDeadline) {
        if (redis == null) throw PolicyImpactJson.unavailable();
        if (authorityDeadline == null || authorityDeadline.isAfter(proof.expiresAt())) throw PolicyImpactJson.denied();
        long ttl = Duration.between(clock.instant(), authorityDeadline).toMillis();
        if (ttl < 1 || ttl > 30000) throw PolicyImpactJson.denied();
        String prefix = "dwp:approval-policy-impact:{" + proof.bindings().tenantId() + ":" + PolicyImpactProtocol.OWNER_PURPOSE + "}:";
        final Long result;
        try { result = redis.execute(CONSUME, List.of(prefix + "owner:" + proof.sourceJti(), prefix + "transport:" + proof.transportJti()), Long.toString(ttl)); }
        catch (RuntimeException error) { throw PolicyImpactJson.unavailable(); }
        if (result == null) throw PolicyImpactJson.unavailable();
        if (result != 1) throw PolicyImpactJson.denied();
    }
}
