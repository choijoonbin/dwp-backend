package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.signatures.ApprovalSignatureCommandReceiptDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Supplier;

final class ApprovalSignatureCommandReceiptSource implements ApprovalSignatureCommandReceiptAuthority.Source {
    private static final String SESSION=ApprovalSignatureCommandReceiptSource.class.getName()+".session";
    private final boolean enabled;private final Supplier<HttpServletRequest> requests;private final ApprovalSignatureReceiptInstalledSource installed;
    private final Supplier<ApprovalSignatureSourceKeys> keys;private final Supplier<ApprovalSignatureAuthorityClient> transport;
    private final ApprovalSignatureCommandReceiptRepository repository;private final ApprovalSignatureCanonical json;private final Clock clock;
    ApprovalSignatureCommandReceiptSource(boolean enabled,Supplier<HttpServletRequest> requests,ApprovalSignatureReceiptInstalledSource installed,Supplier<ApprovalSignatureSourceKeys> keys,
            Supplier<ApprovalSignatureAuthorityClient> transport,ApprovalSignatureCommandReceiptRepository repository,ApprovalSignatureCanonical json,Clock clock){
        this.enabled=enabled;this.requests=requests;this.installed=installed;this.keys=keys;this.transport=transport;this.repository=repository;this.json=json;this.clock=clock;
    }
    @Override public String assertion(Query query,UUID nonce) {
        if(!enabled || nonce==null)throw unavailable();var request=requests.get();var seal=installed.capture(request,query);
        var signing=keys.get();var client=transport.get();
        Session session;
        if(request.getAttribute(SESSION) instanceof Session original){if(!original.query().equals(query) || !original.seal().equals(seal))throw conflict();session=original;}
        else{
            var snapshot=repository.capture(seal,query);Instant until=seal.evidence().validUntil().toInstant();Instant max=clock.instant().plusSeconds(30);if(max.isBefore(until))until=max;
            session=new Session(query,seal,snapshot,Instant.ofEpochSecond(until.getEpochSecond()));request.setAttribute(SESSION,session);
        }
        if(!session.until().isAfter(clock.instant()) || !session.snapshot().equals(repository.capture(seal,query)))throw conflict();
        var b=new LinkedHashMap<String,Object>();b.put("operation","COMMAND_RECEIPT");b.put("tenantId",seal.actor().tenantId());b.put("actorId",seal.actor().userId());
        b.put("personPublicId",seal.actor().personPublicId().toString());b.put("objectId",session.snapshot().receipt().requestId().toString());b.put("objectVersion",null);
        b.put("idempotencyKey",query.idempotencyKey());b.put("bodySha256",json.digest(query.body()));b.put("contextKey",seal.evidence().contextKey());b.put("contextScopeKey",seal.evidence().contextScopeKey());
        b.put("resourceSetKey",session.snapshot().resourceSetKey());b.put("decisionRevision",seal.evidence().revision());b.put("registrySha256",seal.registrySha256());
        b.put("rolloutState",seal.evidence().rolloutState());b.put("accessMode",seal.mode());b.put("authorityValidUntil",session.until().toString());b.put("nonce",nonce.toString());
        b.put("source",session.snapshot().source());b.put("commandBody",query.body());b.put("sourceSha256",json.digest(session.snapshot().source()));b.put("stepUpToken",null);
        String result=client.evaluate(new ApprovalSignatureSourceExchange(signing,json,clock).issue(b,session.until()));
        if(!seal.equals(installed.capture(request,query)) || !session.snapshot().equals(repository.capture(seal,query)))throw conflict();return result;
    }
    private record Session(Query query,ApprovalSignatureInstalledSource.Seal seal,ApprovalSignatureCommandReceiptRepository.Snapshot snapshot,Instant until){ }
}
