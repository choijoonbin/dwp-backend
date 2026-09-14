package com.dwp.services.auth.workflowplanning;

import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

public final class PlanningAuthorityService {
    private final Supplier<PlanningProofVerifier> verifier; private final PlanningAuthorityPort authority;
    private final PlanningRoleRepository roles; private final PlanningReplayStore replay; private final SigningPort issuer;
    private final PlanningJson json; private final Clock clock; private final boolean enabled;
    public PlanningAuthorityService(Supplier<PlanningProofVerifier> verifier,PlanningAuthorityPort authority,PlanningRoleRepository roles,
            PlanningReplayStore replay,SigningPort issuer,PlanningJson json,Clock clock,boolean enabled) {
        this.verifier=verifier;this.authority=authority;this.roles=roles;this.replay=replay;this.issuer=issuer;this.json=json;this.clock=clock;this.enabled=enabled;
    }
    public PlanningProofVerifier.Verified preverify(byte[] body,String transport) {
        if(!enabled) throw unavailable();
        try {return verifier.get().verify(body,transport);}
        catch(com.dwp.core.exception.BaseException invalid) {throw invalid;}
        catch(RuntimeException unavailable) {throw unavailable();}
    }
    public String evaluate(PlanningProofVerifier.Verified proof) {
        if(!enabled || proof==null) throw unavailable(); authority.requireRegistered(); replay.requireReady();
        var before=authority.requireCurrent(proof); var binding=proof.bindings();
        var first=roles.current(binding.tenantId(),binding.source().get("roleCodes"));
        var after=authority.requireCurrent(proof);
        var second=roles.current(binding.tenantId(),binding.source().get("roleCodes"));
        if(!before.same(after) || !first.equals(second)) throw changed();
        Instant expiry=after.expiresAt();
        if(second.deadline()!=null && second.deadline().isBefore(expiry)) expiry=second.deadline();
        var snapshot=binding.source().get("snapshot"); if("PUBLISHED".equals(snapshot.path("workflow_state").asText())) {
            var from=snapshot.get("effectiveFrom");
            if(!from.isNull() && Instant.parse(from.textValue()).isAfter(clock.instant())) throw changed();
            var until=snapshot.get("effectiveTo"); if(!until.isNull()) { var bound=Instant.parse(until.textValue()); if(bound.isBefore(expiry)) expiry=bound; }
        }
        if(!expiry.isAfter(clock.instant()) || expiry.isAfter(proof.expiresAt())) throw changed();
        replay.consume(proof,expiry);
        if(!expiry.isAfter(clock.instant())) throw changed();
        String vector=json.digest(Map.of("owner",after.vector(),"roles",second.vector(),"snapshotSha256",binding.source().get("snapshotSha256")));
        return issuer.issue(proof,new Current(after,second,vector,clock.instant(),expiry));
    }
    public interface SigningPort {
        String issue(PlanningProofVerifier.Verified proof,Current current);
    }
    public static final class Current {
        private final PlanningAuthorityPort.Owner owner; private final PlanningRoleRepository.Snapshot roles;
        private final String vector; private final Instant evaluated,expires;
        private Current(PlanningAuthorityPort.Owner owner,PlanningRoleRepository.Snapshot roles,String vector,Instant evaluated,Instant expires) {
            this.owner=owner; this.roles=roles; this.vector=vector; this.evaluated=evaluated; this.expires=expires;
        }
        PlanningAuthorityPort.Owner owner() { return owner; }
        PlanningRoleRepository.Snapshot roles() { return roles; }
        String vector() { return vector; }
        Instant evaluated() { return evaluated; }
        Instant expires() { return expires; }
    }
}
