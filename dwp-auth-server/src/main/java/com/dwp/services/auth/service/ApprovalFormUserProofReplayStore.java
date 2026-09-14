package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.ApprovalFormUserSourceProofVerifier.VerifiedSourceProof;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ApprovalFormUserProofReplayStore {
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public ApprovalFormUserProofReplayStore(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    ApprovalFormUserProofReplayStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public void consume(VerifiedSourceProof proof) {
        Duration ttl = Duration.between(clock.instant(), proof.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) throw new BaseException(ErrorCode.FORBIDDEN,
                "The approval person source proof expired.");
        Boolean fresh;
        try {
            String prefix = proof.mutation() == null ? "dwp:auth:approval-form-user-proof:v1:"
                    : "dwp:auth:approval-form-reference-proof:v1:";
            fresh = redis.opsForValue().setIfAbsent(prefix
                    + proof.proofId(), proof.requestDigest(), ttl);
        } catch (RuntimeException exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Approval person source replay protection is unavailable.");
        }
        if (fresh == null) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Approval person source replay protection is unavailable.");
        if (!fresh) throw new BaseException(ErrorCode.FORBIDDEN,
                "The approval person source proof was already consumed.");
    }
}
