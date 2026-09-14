package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public final class WorkflowRuntimeReplayStore {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('exists',KEYS[1]) ~= 0 or redis.call('exists',KEYS[2]) ~= 0 then return 0 end
            redis.call('psetex',KEYS[1],ARGV[1],ARGV[2])
            redis.call('psetex',KEYS[2],ARGV[1],ARGV[3])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;
    @Autowired public WorkflowRuntimeReplayStore(StringRedisTemplate redis) { this(redis, Clock.systemUTC()); }
    public WorkflowRuntimeReplayStore(StringRedisTemplate redis, Clock clock) { this.redis = redis; this.clock = clock; }
    public void consume(WorkflowRuntimeProofVerifier.Verified proof, Instant authorityExpiry) {
        if (proof == null || authorityExpiry == null || authorityExpiry.isAfter(proof.expiresAt())) throw denied();
        long millis = Duration.between(clock.instant(), authorityExpiry).toMillis();
        if (millis < 1 || millis > 30000) throw denied();
        String prefix = "dwp:auth:approval-workflow-runtime:v1:" + proof.operation().name() + ":{" + proof.caller().tenantId() + "}:";
        Long result;
        try { result = redis.execute(CONSUME, List.of(prefix + "owner:" + proof.sourceProofJti(), prefix + "transport:" + proof.transportProofJti()),
                Long.toString(millis), proof.bindingsSha256(), proof.bodySha256()); }
        catch (RuntimeException exception) { throw unavailable(); }
        if (result == null) throw unavailable(); if (result != 1L) throw denied();
    }
}
