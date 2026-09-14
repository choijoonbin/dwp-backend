package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import static com.dwp.services.approval.signatures.ApprovalSignatureCommandReceiptDtos.*;
import com.dwp.services.approval.security.*;
import com.dwp.services.approval.signatures.ApprovalSignatureCommandReceiptDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Set;

final class ApprovalSignatureReceiptInstalledSource {
    private final ApprovalSignatureCanonical canonical;private final Clock clock;
    ApprovalSignatureReceiptInstalledSource(ApprovalSignatureCanonical canonical,Clock clock){this.canonical=canonical;this.clock=clock;}
    ApprovalSignatureInstalledSource.Seal capture(HttpServletRequest request,Query query) {
        String registry;
        try(var stream=new org.springframework.core.io.ClassPathResource("product-authorization/approval-pilot-pep-v10.generated.json").getInputStream()) {
            byte[] bytes=stream.readNBytes(1048577);if(bytes.length>1048576)throw unavailable();
            var ref=canonical.read(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),JsonNode.class).path("registryRef");
            if(!ref.path("version").isIntegralNumber() || ref.path("version").intValue()!=10 || !"product-surfaces".equals(ref.path("bundleKey").asText())
                    || !ref.path("sha256").isTextual() || !ref.path("sha256").textValue().matches("[a-f0-9]{64}"))throw unavailable();registry=ref.path("sha256").textValue();
        }catch(Exception missing){throw unavailable();}
        if(request==null || !"GET".equals(request.getMethod()) || !query.path().equals(request.getRequestURI()))throw denied();
        for(String header:Set.of("Idempotency-Key","X-DWP-Step-Up-Challenge","X-DWP-Expected-Object-Version"))if(request.getHeader(header)!=null)throw denied();
        var authorities=ApprovalPilotAuthorizationContext.current().orElseThrow(ApprovalSignatureCanonical::unavailable);
        var evidence=ApprovalDecisionRevisionContext.current().orElseThrow(ApprovalSignatureCanonical::unavailable);
        if(authorities.size()!=1 || authorities.stream().anyMatch(p->!ROUTE.equals(p.routeContractKey()) || !PROFILE.equals(p.profileKey()) || !p.readOnly() || p.highRisk()
                || !"approvals.work.signature.read".equals(p.capabilityContractKey()) || !"ACTION.APPROVAL_REQUEST:VIEW".equals(p.resolvedCapabilityCode()) || p.activationPolicy()!=null
                || !Integer.valueOf(1).equals(p.projectionSchemaVersion()) || !Boolean.FALSE.equals(p.projectionAdditionalProperties())
                || p.openApiSchemaSha256()==null || !p.openApiSchemaSha256().matches("[a-f0-9]{64}")))throw unavailable();
        if(!ROUTE.equals(evidence.routeContractKey()) || evidence.revision()==null || !evidence.revision().matches("psr-[a-f0-9]{64}")
                || evidence.validUntil()==null || !evidence.validUntil().toInstant().isAfter(clock.instant()) || !Set.of("110","111").contains(evidence.rolloutState())
                || evidence.contextKey()==null || evidence.contextKey().isBlank() || evidence.contextScopeKey()==null || evidence.contextScopeKey().isBlank())throw unavailable();
        final ApprovalRequestContext.Actor actor;try{actor=ApprovalRequestContext.require();}catch(IllegalStateException missing){throw unavailable();}
        if(actor.tenantId()==null || actor.tenantId()<=0 || actor.userId()==null || actor.userId()<=0 || actor.personPublicId()==null
                || actor.roles().stream().anyMatch(r->r.startsWith("PROVIDER_")) || !actor.permissions().contains("APP.APPROVALS:VIEW") || !actor.permissions().contains("ACTION.APPROVAL_REQUEST:VIEW"))throw denied();
        String mode=ApprovalSignatureInstalledSource.single(request,"X-DWP-Active-Access-Mode");
        if(!Set.of("NORMAL","ELEVATED").contains(mode) || request.getHeader("X-DWP-Support-Session-ID")!=null)throw denied();
        var expected=new java.util.HashMap<String,String>();expected.put("originalOperation",query.originalOperation().name());expected.put("targetId",query.targetId().toString());expected.put("bodySha256",query.bodySha256());
        if(request.getParameterMap().containsKey("contextScopeKey"))expected.put("contextScopeKey",evidence.contextScopeKey());
        if(!request.getParameterMap().keySet().equals(expected.keySet()))throw denied();
        for(var e:expected.entrySet())if(request.getParameterValues(e.getKey()).length!=1 || !e.getValue().equals(request.getParameter(e.getKey())))throw denied();
        String raw=request.getQueryString();if(raw==null)throw denied();var seen=new java.util.HashSet<String>();
        for(String part:raw.split("&",-1)){int split=part.indexOf('=');if(split<1)throw denied();String key=part.substring(0,split);String value=expected.get(key);
            if(value==null || !seen.add(key) || !java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8).equals(part.substring(split+1)))throw denied();}
        if(!seen.equals(expected.keySet()))throw denied();
        return new ApprovalSignatureInstalledSource.Seal(actor,evidence,mode,registry);
    }
}
