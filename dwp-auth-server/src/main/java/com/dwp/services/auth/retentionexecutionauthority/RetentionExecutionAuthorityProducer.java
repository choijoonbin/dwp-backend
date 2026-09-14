package com.dwp.services.auth.retentionexecutionauthority;

import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import java.security.Signature;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Existing Ed25519 nine-claim wire format; current raw Auth evidence stays private. */
public final class RetentionExecutionAuthorityProducer implements RetentionExecutionAuthorityService.SigningPort {
    private final RetentionExecutionJson json;private final Supplier<RetentionExecutionKeys> keys;private final Clock clock;
    public RetentionExecutionAuthorityProducer(RetentionExecutionJson json,Supplier<RetentionExecutionKeys> keys,Clock clock) {this.json=json;this.keys=keys;this.clock=clock;}
    @Override public SignedAuthorization issue(RetentionExecutionAuthorityService.Current current) {
        if(current==null || !current.expires().isAfter(clock.instant()) || current.expires().isAfter(current.proof().expiresAt())
                || current.issued().isAfter(clock.instant()) || Duration.between(current.issued(),current.expires()).toMillis()>30000) throw denied();
        try {
            var key=keys.get();byte[] payload=json.bytes(Map.of("target",current.proof().bindings().target(),"issuer",key.issuer(),"audience","dwp-approval-server",
                    "purpose",EXECUTION_PURPOSE,"keyId",key.keyId(),"nonce",current.proof().sourceJti(),"issuedAt",current.issued(),"expiresAt",current.expires(),"allowed",true));
            if(payload.length>8192) throw denied();var signer=Signature.getInstance("Ed25519");signer.initSign(key.execution());signer.update(payload);
            return new SignedAuthorization(Base64.getUrlEncoder().withoutPadding().encodeToString(payload),Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
        } catch(com.dwp.core.exception.BaseException denied) {throw denied;}
        catch(Exception unavailable) {throw unavailable();}
    }
}
