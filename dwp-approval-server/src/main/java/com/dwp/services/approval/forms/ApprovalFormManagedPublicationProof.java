package com.dwp.services.approval.forms;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.*;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reuses the established signed challenge/replay protocol, with workspace-owned maker checks. */
@Component
public class ApprovalFormManagedPublicationProof {
    private final ApprovalStepUpVerifier verifier;
    private final ApprovalStepUpReplayRepository replay;
    public ApprovalFormManagedPublicationProof(ApprovalStepUpVerifier verifier,ApprovalStepUpReplayRepository replay) {
        this.verifier=verifier;this.replay=replay;
    }
    public Permit begin(ApprovalFormLifecycleAuthority.Window window,UUID form,
            ApprovalFormLifecycleDtos.PublishReviewed body,ApprovalStepUpHeaders headers) {
        var authority=ApprovalPilotAuthorizationContext.highRisk().orElseThrow(ApprovalFormWorkspaceRepository::unavailable);
        var current=window.evidence();
        if(!authority.routeContractKey().equals(current.routeContractKey())||!"approvals.design.publish".equals(authority.capabilityContractKey())
                ||!"STEPUP-MGMT-HIGH-V1".equals(authority.activationPolicy())) throw new BaseException(ErrorCode.FORBIDDEN);
        if(headers==null||headers.challenge()==null||headers.challenge().isBlank()||headers.idempotencyKey()==null
                ||headers.idempotencyKey().isBlank()||headers.idempotencyKey().length()>200
                ||headers.expectedObjectVersion()==null||!headers.expectedObjectVersion().equals(body.expectedFormRevision())
                ||!current.revision().equals(headers.decisionRevision())||!current.validUntil().isAfter(OffsetDateTime.now()))
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED);
        String path="/api/approvals/v1/admin/forms/"+form+"/publish-reviewed";
        var binding=new ApprovalStepUpVerifier.CommandBinding(window.actor().userId(),window.actor().tenantId(),current.routeContractKey(),
                current.contextKey(),"STEPUP-MGMT-HIGH-V1","approvals.design.publish",current.contextScopeKey(),"FORM",form.toString(),
                body.expectedFormRevision(),"POST",path,headers.idempotencyKey(),verifier.payloadSha256(body),current.revision());
        var request=new LinkedHashMap<String,Object>();
        request.put("routeContractKey",binding.commandContractKey());request.put("actorUserId",binding.actorUserId());request.put("tenantId",binding.tenantId());
        request.put("contextKey",binding.contextKey());request.put("contextScopeKey",binding.scopeRef());request.put("targetType",binding.targetType());
        request.put("targetId",binding.targetId());request.put("targetVersion",binding.targetVersion());request.put("commandMethod","POST");
        request.put("commandPath",path);request.put("idempotencyKey",binding.idempotencyKey());request.put("payloadSha256",binding.payloadSha256());
        request.put("decisionRevision",binding.decisionRevision());
        var reservation=replay.reserve(binding,current.routeContractKey(),verifier.payloadSha256(request));
        if(reservation.committed()) return new Permit(null,reservation);
        var challenge=verifier.verify(headers.challenge(),binding);
        replay.assertNotConsumed(challenge.challengeId(),challenge.nonce());
        return new Permit(challenge,reservation);
    }
    public void complete(Permit permit) {
        if(permit.challenge()!=null) { replay.consume(permit.challenge());replay.commit(permit.reservation().id(),permit.challenge()); }
    }
    public record Permit(ApprovalStepUpVerifier.VerifiedChallenge challenge,ApprovalStepUpReplayRepository.Reservation reservation) { }
}
