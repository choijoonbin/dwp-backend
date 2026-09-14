package com.dwp.services.approval.security;

import static com.dwp.services.approval.security.ApprovalWorkflowQuorumCommandProof.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

@Component
public class ApprovalWorkflowQuorumCommandMetadata {
    private static final String ATTRIBUTE = ApprovalWorkflowQuorumCommandMetadata.class.getName();
    private final ObjectProvider<HttpServletRequest> requests;
    private final ObjectProvider<ApprovalWorkflowQuorumCommandProof> proofs;
    public ApprovalWorkflowQuorumCommandMetadata(ObjectProvider<HttpServletRequest> requests,
            ObjectProvider<ApprovalWorkflowQuorumCommandProof> proofs) { this.requests = requests; this.proofs = proofs; }

    public void prepare(Purpose purpose, UUID target) {
        var request = request(); request.removeAttribute(ATTRIBUTE);
        var actor = ApprovalRequestContext.require(); var body = body(request);
        String key = key(request); String path = path(purpose, target);
        if (!"POST".equals(request.getMethod()) || !path.equals(request.getRequestURI()) || request.getQueryString() != null) throw forbidden();
        var source = proofs.getIfAvailable(); if (source == null) throw unavailable();
        var verified = source.verify(actor, purpose, target, request.getMethod(), path, key, body.clone());
        verify(verified, actor, purpose, target, request, body);
        request.setAttribute(ATTRIBUTE, verified);
    }

    public Verified current(ApprovalRequestContext.Actor actor, Purpose purpose, UUID target) {
        var request = request(); var value = request.getAttribute(ATTRIBUTE);
        if (!(value instanceof Verified verified)) throw unavailable();
        verify(verified, actor, purpose, target, request, body(request)); return verified;
    }
    public void clear() { var request = requests.getIfAvailable(); if (request != null) request.removeAttribute(ATTRIBUTE); }
    private void verify(Verified verified, ApprovalRequestContext.Actor actor, Purpose purpose, UUID target,
            HttpServletRequest request, byte[] body) {
        Instant now = Instant.now();
        var context = ApprovalDecisionRevisionContext.current().orElseThrow(this::unavailable);
        if (verified == null || verified.evaluatedAt() == null || verified.expiresAt() == null
                || verified.evaluatedAt().isAfter(now) || !verified.expiresAt().isAfter(now)
                || verified.expiresAt().isAfter(verified.evaluatedAt().plusSeconds(30)) || verified.revision() == null || verified.revision().isBlank()
                || verified.revision().length() > 256 || !context.validUntil().toInstant().isAfter(now)
                || !context.equals(verified.context()) || !java.util.Set.of("110", "111").contains(context.rolloutState()))
            throw unavailable();
        var modes = Collections.list(request.getHeaders("X-DWP-Active-Access-Mode"));
        if (actor.personPublicId() == null || actor.tenantId() != verified.tenantId() || actor.userId() != verified.actorUserId()
                || !actor.personPublicId().equals(verified.actorPersonId()) || purpose != verified.purpose() || !target.equals(verified.targetId())
                || !route(purpose).equals(context.routeContractKey())
                || !"POST".equals(request.getMethod()) || !"POST".equals(verified.method()) || request.getQueryString() != null
                || !path(purpose, target).equals(request.getRequestURI()) || !request.getRequestURI().equals(verified.path())
                || !key(request).equals(verified.idempotencyKey()) || !Objects.equals(hash(body), verified.rawBodySha256())
                || modes.size() != 1 || !java.util.Set.of("NORMAL", "ELEVATED").contains(modes.getFirst())
                || !modes.getFirst().equals(verified.accessMode()) || actor.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) throw forbidden();
    }
    private HttpServletRequest request() { var request = requests.getIfAvailable(); if (request == null) throw unavailable(); return request; }
    private byte[] body(HttpServletRequest request) {
        var cached = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        if (cached == null || Boolean.TRUE.equals(request.getAttribute(ApprovalWorkflowQuorumBodyFilter.OVERFLOW))) throw unavailable();
        byte[] body = cached.getContentAsByteArray();
        if (body.length == 0 || body.length > ApprovalWorkflowQuorumBodyFilter.MAX_BODY) throw unavailable(); return body;
    }
    private String key(HttpServletRequest request) {
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1 || !keys.getFirst().matches("[A-Za-z0-9._:-]{1,120}")) throw forbidden(); return keys.getFirst();
    }
    private String path(Purpose purpose, UUID target) {
        return purpose == Purpose.TASK_INFORMATION ? "/v1/tasks/" + target + "/decisions" : "/v1/requests/" + target + "/information-response";
    }
    private String route(Purpose purpose) {
        return purpose == Purpose.TASK_INFORMATION ? "route.approvals.work.task-decision.action"
                : "route.approvals.work.request-information-response.action";
    }
    private String hash(byte[] body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Dedicated signed workflow command admission is unavailable."); }
    private BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN, "The original signed command identity does not match."); }
}
