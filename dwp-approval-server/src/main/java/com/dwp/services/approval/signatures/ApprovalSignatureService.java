package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import static com.dwp.services.approval.signatures.ApprovalSignatureDtos.*;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.*;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public final class ApprovalSignatureService {
    private final boolean enabled;
    private final ApprovalSignatureAuthority authority;
    private final ApprovalSignatureSourceRepository sources;
    private final ApprovalSignatureRepository ledger;
    private final ApprovalSignatureTerms terms;
    private final ApprovalSignatureEvidenceSigner signer;
    private final ApprovalSignatureCanonical canonical;
    private final TransactionTemplate writes,reads;
    private final Clock clock;
    private final ApprovalSignatureHighRiskGuard highRisk;
    public ApprovalSignatureService(boolean enabled,ApprovalSignatureAuthority authority,ApprovalSignatureSourceRepository sources,
            ApprovalSignatureRepository ledger,ApprovalSignatureTerms terms,ApprovalSignatureEvidenceSigner signer,
            ApprovalSignatureCanonical canonical,TransactionTemplate writes,TransactionTemplate reads,Clock clock) {
        this(enabled,authority,sources,ledger,terms,signer,canonical,writes,reads,clock,null);
    }
    ApprovalSignatureService(boolean enabled,ApprovalSignatureAuthority authority,ApprovalSignatureSourceRepository sources,
            ApprovalSignatureRepository ledger,ApprovalSignatureTerms terms,ApprovalSignatureEvidenceSigner signer,
            ApprovalSignatureCanonical canonical,TransactionTemplate writes,TransactionTemplate reads,Clock clock,ApprovalSignatureHighRiskGuard highRisk) {
        this.enabled=enabled; this.authority=authority; this.sources=sources; this.ledger=ledger; this.terms=terms; this.signer=signer;
        this.canonical=canonical; this.writes=writes; this.reads=reads; this.clock=clock;
        this.highRisk=highRisk;
    }
    public Context context(UUID requestId,String locale) {
        Verified proof=begin(Operation.CONTEXT,requestId,null,Map.of("locale",locale),null);
        return reads.execute(status -> {
            var source=sources.read(proof,requestId,false); Terms current=terms.current(locale);
            authority.unchanged(proof);
            byte[] bytes=source.bytes();
            return new Context(SignerKind.SELF_ATTESTATION,source.pin(),digest(source.pin(),current),
                    new Artifact(source.pin().rendererVersion(),"application/json",source.pin().artifactSha256(),bytes.length,
                            new String(bytes,java.nio.charset.StandardCharsets.UTF_8)),current,signer.readiness(),true,clock.instant());
        });
    }
    public Receipt create(UUID requestId,Create input) {
        if (input.signerKind()!=SignerKind.SELF_ATTESTATION) throw denied();
        Verified proof=begin(Operation.CREATE,requestId,input.expectedVersion(),input,input.idempotencyKey());
        return writes.execute(status -> {
            sources.lockCurrent(proof,requestId,false);
            ledger.serializeCommand(proof,"CREATE",input.idempotencyKey());
            Receipt prior=ledger.prior(proof,"CREATE",input.idempotencyKey(),input);
            if (prior!=null) return recover(proof,prior);
            var source=sources.read(proof,requestId,true); Terms current=terms.current(input.locale());
            if (!input.sourceDigest().equals(digest(source.pin(),current)) || source.pin().requestVersion()!=input.expectedVersion()) throw conflict();
            Instant now=clock.instant(),expires=current.expiresAt().isBefore(now.plusSeconds(900))?current.expiresAt():now.plusSeconds(900);
            Ceremony result=ledger.create(proof,source,current,input.sourceDigest(),now,expires);
            unchanged(proof,result);
            Receipt receipt=ledger.complete(proof,"CREATE",input.idempotencyKey(),input,result,clock.instant());
            unchanged(proof,result); return receipt;
        });
    }
    public Ceremony get(UUID id) {
        Verified proof=begin(Operation.GET,id,null,Map.of(),null);
        return reads.execute(status -> {
            Ceremony result=ledger.read(proof,id,false); sourceUnchanged(proof,result,false); authority.unchanged(proof); return result;
        });
    }
    public Audit audit(UUID id) {
        Verified proof=begin(Operation.AUDIT,id,null,Map.of(),null);
        return reads.execute(status -> { ledger.read(proof,id,false); Audit result=ledger.audit(proof,id); authority.unchanged(proof); return result; });
    }
    public Receipt consent(UUID id,Consent input) {
        if (!input.accepted()) throw denied();
        return mutate(Operation.CONSENT,id,input.expectedVersion(),input.sourceDigest(),input.idempotencyKey(),input,(proof,original) -> {
            Terms t=original.terms();
            if (original.state()!=State.AWAITING_CONSENT || !t.termsId().equals(input.termsId()) || t.version()!=input.termsVersion()
                    || !t.sha256().equals(input.termsSha256()) || !t.locale().equals(input.locale())) throw conflict();
            ledger.consent(proof,original,clock.instant());
        });
    }
    public Receipt sign(UUID id,Sign input) {
        gate(); if (!"VERIFIED_INTERNAL_KEY".equals(signer.readiness())) throw unavailable();
        return mutate(Operation.SIGN,id,input.expectedVersion(),input.sourceDigest(),input.idempotencyKey(),input,(proof,original) -> {
            if (original.state()!=State.CONSENTED) throw conflict();
            ledger.validateConsent(proof,original,input.consentReceiptId(),clock.instant());
            Evidence evidence=signer.sign(id,proof.actorId(),original.sourceDigest(),original.source().artifactSha256(),
                    original.consentReceiptId(),proof.digest(),clock.instant());
            unchanged(proof,original); ledger.attest(proof,original,evidence);
        });
    }
    public Receipt cancel(UUID id,Cancel input) {
        return mutate(Operation.CANCEL,id,input.expectedVersion(),input.sourceDigest(),input.idempotencyKey(),input,(proof,original) -> {
            if (original.state()==State.ATTESTED || original.state()==State.CANCELLED) throw conflict();
            ledger.cancel(proof,original);
        });
    }
    private interface Mutation { void apply(Verified proof,Ceremony original); }
    private Receipt mutate(Operation operation,UUID id,Long version,String digest,String key,Object body,Mutation mutation) {
        Verified proof=begin(operation,id,version,body,key);
        return writes.execute(status -> {
            sources.lockCurrent(proof,id,true);
            ledger.serializeCommand(proof,operation.name(),key); Ceremony original=ledger.read(proof,id,true);
            if (!original.sourceDigest().equals(digest)) throw conflict();
            Receipt prior=ledger.prior(proof,operation.name(),key,body);
            if (prior!=null) return recover(proof,prior);
            if (operation!=Operation.CANCEL) unchanged(proof,original);
            if (version==null || original.version()!=version) throw conflict();
            ApprovalSignatureHighRiskGuard.Reservation high = null;
            if (operation==Operation.SIGN) {
                if (highRisk==null) throw unavailable();
                high=highRisk.verify(proof,proof.binding());
            }
            mutation.apply(proof,original); Ceremony result=ledger.read(proof,id,true);
            if (operation!=Operation.CANCEL) unchanged(proof,result); else authority.unchanged(proof);
            Receipt receipt=ledger.complete(proof,operation.name(),key,body,result,clock.instant());
            if (operation!=Operation.CANCEL) unchanged(proof,result); else authority.unchanged(proof);
            if (high!=null) highRisk.consume(proof,high);
            return receipt;
        });
    }
    private Verified begin(Operation operation,UUID id,Long version,Object body,String key) {
        gate(); return authority.require(new Binding(operation,id,version,canonical.digest(body),key,
                canonical.read(canonical.json(body), com.fasterxml.jackson.databind.JsonNode.class)));
    }
    private Receipt recover(Verified proof,Receipt committed) {
        // A committed receipt is historical, not a new consent/sign attempt. Artifact access remains current.
        Ceremony current=ledger.read(proof,committed.ceremony().signatureRequestId(),true);
        if (!current.requestId().equals(committed.ceremony().requestId()) || !current.sourceDigest().equals(committed.ceremony().sourceDigest())
                || !current.artifact().equals(committed.ceremony().artifact())) throw conflict();
        sourceUnchanged(proof,current,true); authority.unchanged(proof); return committed;
    }
    private void gate() { if (!enabled) throw unavailable(); }
    private String digest(SourcePin pin,Terms current) { return canonical.digest(Map.of("source",pin,"terms",current)); }
    private void sourceUnchanged(Verified proof,Ceremony original,boolean lock) {
        var current=sources.read(proof,original.requestId(),lock);
        if (!current.pin().equals(original.source())) throw conflict();
    }
    private void unchanged(Verified proof,Ceremony original) {
        if (!original.expiresAt().isAfter(clock.instant())) throw conflict();
        terms.unchanged(original.terms()); sourceUnchanged(proof,original,true); authority.unchanged(proof);
    }
}
