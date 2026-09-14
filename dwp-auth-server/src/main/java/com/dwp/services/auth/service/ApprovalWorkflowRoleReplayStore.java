package com.dwp.services.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public final class ApprovalWorkflowRoleReplayStore {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('exists', KEYS[1]) ~= 0 or redis.call('exists', KEYS[2]) ~= 0 then return 0 end
            redis.call('psetex', KEYS[1], ARGV[1], ARGV[2])
            redis.call('psetex', KEYS[2], ARGV[1], ARGV[3])
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public ApprovalWorkflowRoleReplayStore(StringRedisTemplate redis) { this(redis, Clock.systemUTC()); }
    ApprovalWorkflowRoleReplayStore(StringRedisTemplate redis, Clock clock) { this.redis = redis; this.clock = clock; }

    public void consume(ApprovalWorkflowRoleProofVerifier.VerifiedProof proof) {
        if (proof == null || proof.proofId() == null || proof.transportId() == null || proof.binding() == null
                || proof.expiresAt() == null || proof.requestDigest() == null || !proof.requestDigest().matches("[a-f0-9]{64}")
                || proof.bodySha256() == null || !proof.bodySha256().matches("[a-f0-9]{64}")) throw ApprovalWorkflowRoleBinding.denied();
        Duration ttl = Duration.between(clock.instant(), proof.expiresAt());
        if (ttl.toMillis() < 1 || ttl.compareTo(Duration.ofSeconds(30)) > 0) throw ApprovalWorkflowRoleBinding.denied();
        String tenant = "{" + proof.binding().tenantId() + "}:";
        Long fresh;
        try {
            fresh = redis.execute(CONSUME, List.of("dwp:auth:approval-workflow-role-proof:v1:" + tenant + proof.proofId(),
                    "dwp:auth:approval-workflow-role-transport:v1:" + tenant + proof.transportId()),
                    Long.toString(ttl.toMillis()), proof.requestDigest(), proof.bodySha256());
        } catch (RuntimeException exception) { throw ApprovalWorkflowRoleBinding.unavailable(); }
        if (fresh == null) throw ApprovalWorkflowRoleBinding.unavailable();
        if (fresh != 1L) throw ApprovalWorkflowRoleBinding.denied();
    }
}
