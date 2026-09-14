package com.dwp.services.auth.retentionexecutionauthority;

import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import java.time.*;
import java.util.function.Supplier;

/** The producer accepts only a private current factory result, never a caller's Target. */
public final class RetentionExecutionAuthorityService {
    private final Supplier<RetentionExecutionProofVerifier> verifier;private final RetentionExecutionAuthorityPort authority;
    private final RetentionExecutionReplayStore replay;private final SigningPort producer;private final Clock clock;private final boolean enabled;
    public RetentionExecutionAuthorityService(Supplier<RetentionExecutionProofVerifier> verifier,RetentionExecutionAuthorityPort authority,
            RetentionExecutionReplayStore replay,SigningPort producer,Clock clock,boolean enabled) {
        this.verifier=verifier;this.authority=authority;this.replay=replay;this.producer=producer;this.clock=clock;this.enabled=enabled;
    }
    public RetentionExecutionProofVerifier.Verified preverify(byte[] body,String token) {
        if(!enabled) throw unavailable();
        try {return verifier.get().verify(body,token);}
        catch(com.dwp.core.exception.BaseException invalid) {throw invalid;}
        catch(RuntimeException invalid) {throw unavailable();}
    }
    public SignedAuthorization evaluate(RetentionExecutionProofVerifier.Verified proof) {
        if(!enabled || proof==null) throw unavailable();replay.requireReady();
        var before=authority.requireCurrent(proof);Instant expiry=minimum(before.expiresAt(),proof.expiresAt());
        if(!expiry.isAfter(clock.instant())) throw denied();replay.consume(proof,expiry);
        var after=authority.requireCurrent(proof);if(!before.same(after)) throw changed();
        expiry=minimum(expiry,after.expiresAt());expiry=Instant.ofEpochSecond(expiry.getEpochSecond());
        if(!expiry.isAfter(clock.instant())) throw changed();
        return producer.issue(new Current(proof,after,clock.instant(),expiry));
    }
    private Instant minimum(Instant a,Instant b) {return a.isBefore(b)?a:b;}
    public interface SigningPort {SignedAuthorization issue(Current current);}
    public static final class Current {
        private final RetentionExecutionProofVerifier.Verified proof;private final RetentionExecutionAuthorityPort.Observation authority;
        private final Instant issued,expires;
        private Current(RetentionExecutionProofVerifier.Verified proof,RetentionExecutionAuthorityPort.Observation authority,Instant issued,Instant expires) {
            this.proof=proof;this.authority=authority;this.issued=issued;this.expires=expires;
        }
        RetentionExecutionProofVerifier.Verified proof() {return proof;}
        RetentionExecutionAuthorityPort.Observation authority() {return authority;}
        Instant issued() {return issued;}
        Instant expires() {return expires;}
    }
}
