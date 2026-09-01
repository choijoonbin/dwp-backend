package com.dwp.services.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.support.CustomerApprovalEvidencePolicy;
import com.dwp.services.provider.support.ProviderSupportAccessPolicy;
import com.dwp.services.provider.support.ProviderSupportActivationGate;
import com.dwp.services.provider.support.ProviderSupportDtos;
import com.dwp.services.provider.support.ProviderSupportRequestRepository;
import com.dwp.services.provider.support.ProviderSupportRequestSecurityPolicy;
import com.dwp.services.provider.support.ProviderSupportSessionLifecycleService;
import com.dwp.services.provider.support.ProviderSupportSessionRepository;
import com.dwp.services.provider.tenant.ProviderTenant;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class ProviderSupportControl {

    private final ProviderOperationsRepository operationsRepository;
    private final ProviderSupportRequestRepository supportRequestRepository;
    private final ProviderSupportRequestSecurityPolicy supportRequestSecurityPolicy;
    private final ProviderSupportSessionRepository supportSessionRepository;
    private final ProviderSupportSessionLifecycleService supportSessionLifecycleService;
    private final ProviderSupportActivationGate supportActivationGate;
    private final CustomerApprovalEvidencePolicy customerApprovalEvidencePolicy;
    private final ProviderAuditService auditService;
    private final ProviderControlPlaneContext context;

    ProviderSupportControl(
            ProviderOperationsRepository operationsRepository,
            ProviderSupportRequestRepository supportRequestRepository,
            ProviderSupportRequestSecurityPolicy supportRequestSecurityPolicy,
            ProviderSupportSessionRepository supportSessionRepository,
            ProviderSupportSessionLifecycleService supportSessionLifecycleService,
            ProviderSupportActivationGate supportActivationGate,
            CustomerApprovalEvidencePolicy customerApprovalEvidencePolicy,
            ProviderAuditService auditService,
            ProviderControlPlaneContext context) {
        this.operationsRepository = operationsRepository;
        this.supportRequestRepository = supportRequestRepository;
        this.supportRequestSecurityPolicy = supportRequestSecurityPolicy;
        this.supportSessionRepository = supportSessionRepository;
        this.supportSessionLifecycleService = supportSessionLifecycleService;
        this.supportActivationGate = supportActivationGate;
        this.customerApprovalEvidencePolicy = customerApprovalEvidencePolicy;
        this.auditService = auditService;
        this.context = context;
    }

    ProviderDtos.SupportSessionGrant createSupportSession(
            String correlationId,
            ProviderDtos.CreateSupportSessionRequest request) {
        ProviderRequestContext.requirePermission("SUPPORT_SESSION_WRITE");
        ProviderTenant tenant = context.requireTenant(request.tenantId());
        SupportRequestPolicy policy = supportRequestPolicy(request.scopes());
        String approvalReference = context.normalized(request.approvalReference());
        if (!request.emergencyAccess()) {
            if (policy.customerApprovalRequired() && approvalReference == null) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "A customer approval reference is required for this support scope.");
            }
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Standard support access must be requested and independently approved before activation.");
        }
        ProviderRequestContext.requirePermission("BREAK_GLASS_SUPPORT");
        throw new BaseException(
                ErrorCode.INVALID_STATE,
                "Break-glass support is disabled until incident binding, fresh MFA, alerting, "
                        + "and customer notification controls are available.");
    }

    ProviderDtos.SupportAccessRequestSummary createSupportAccessRequest(
            String correlationId,
            ProviderDtos.CreateSupportAccessRequest request) {
        ProviderRequestContext.requirePermission("SUPPORT_SESSION_WRITE");
        ProviderTenant tenant = context.requireTenant(request.tenantId());
        SupportRequestPolicy policy = supportRequestPolicy(request.scopes());
        String approvalReference = context.normalized(request.approvalReference());
        if (policy.customerApprovalRequired() && approvalReference == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A customer approval reference is required for this support scope.");
        }
        String requestKey = context.normalizeIdempotencyKey(request.requestKey());
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        UUID requesterAuthSessionId = supportRequestSecurityPolicy.requireCreationAuthSession(actor);
        LinkedHashMap<String, Object> fingerprintFields = new LinkedHashMap<>();
        fingerprintFields.put("tenantId", tenant.getProviderTenantId());
        fingerprintFields.put("scopes", policy.scopes());
        fingerprintFields.put("durationMinutes", request.durationMinutes());
        fingerprintFields.put("justification", request.justification().trim());
        fingerprintFields.put("approvalReference", approvalReference);
        fingerprintFields.put("requesterAuthSessionId", requesterAuthSessionId);
        String fingerprint = context.sha256(context.json(fingerprintFields));
        ProviderSupportRequestRepository.SupportAccessRequestRecord existing =
                supportRequestRepository.byKey(actor.operatorId(), requestKey).orElse(null);
        if (existing != null) {
            if (!context.constantTimeEquals(fingerprint, existing.requestFingerprint())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The support request key was already used for different access details.");
            }
            return supportRequestRepository.summary(existing.requestId());
        }
        ProviderSupportRequestRepository.CreateResult creation = supportRequestRepository.create(
                tenant.getProviderTenantId(), actor.operatorId(), requesterAuthSessionId,
                request.justification().trim(),
                request.durationMinutes(), approvalReference, policy.customerApprovalRequired(),
                policy.riskTier(), requestKey, fingerprint);
        UUID requestId = creation.requestId();
        ProviderSupportRequestRepository.SupportAccessRequestRecord stored =
                supportRequestRepository.byId(requestId)
                        .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!context.constantTimeEquals(fingerprint, stored.requestFingerprint())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The support request key was concurrently used for different access details.");
        }
        if (!creation.created()) return supportRequestRepository.summary(requestId);
        supportRequestRepository.addScopes(requestId, policy.scopes());
        auditService.success(
                "provider.support-access.requested", "SUPPORT_ACCESS_REQUEST", requestId.toString(),
                tenant.getProviderTenantId(), tenant.getOrganizationId(), correlationId,
                Map.of(
                        "scopes", policy.scopes(),
                        "durationMinutes", request.durationMinutes(),
                        "riskTier", policy.riskTier(),
                        "customerApprovalRequired", policy.customerApprovalRequired(),
                        "requestKey", requestKey));
        return supportRequestRepository.summary(requestId);
    }

    ProviderDtos.SupportAccessRequestSummary decideSupportAccessRequest(
            UUID requestId,
            String correlationId,
            ProviderDtos.DecideSupportAccessRequest request) {
        ProviderRequestContext.requirePermission("SUPPORT_ACCESS_REVIEW");
        ProviderSupportRequestRepository.SupportAccessRequestRecord record =
                requireSupportAccessRequest(requestId);
        context.requireVersion(record.version(), request.version());
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        supportRequestSecurityPolicy.requireIndependentApproval(record, actor, correlationId);
        if (!"PENDING_APPROVAL".equals(record.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The support access request is not awaiting approval.");
        }
        if (!supportRequestRepository.decide(
                requestId, request.version(), actor.operatorId(), record.requesterOperatorId(),
                record.requesterAuthSessionId(), request.decision(), request.reason().trim())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The support access request changed or expired.");
        }
        ProviderTenant tenant = context.requireTenant(record.tenantId());
        auditService.success(
                "provider.support-access." + request.decision().toLowerCase(Locale.ROOT),
                "SUPPORT_ACCESS_REQUEST", requestId.toString(), tenant.getProviderTenantId(),
                tenant.getOrganizationId(), correlationId,
                Map.of("decision", request.decision(), "reason", request.reason().trim()));
        return supportRequestRepository.summary(requestId);
    }

    ProviderDtos.SupportSessionGrant activateSupportAccessRequest(
            UUID requestId,
            String correlationId,
            ProviderDtos.ActivateSupportAccessRequest request) {
        ProviderRequestContext.requirePermission("SUPPORT_SESSION_WRITE");
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        supportSessionLifecycleService.expireElapsedSessions();
        ProviderSupportRequestRepository.SupportAccessRequestRecord record =
                supportRequestSecurityPolicy.requireActivationTarget(
                        supportRequestRepository.byId(requestId), requestId, actor, correlationId);
        context.requireVersion(record.version(), request.version());
        supportRequestSecurityPolicy.requireActivationPrincipal(record, actor, correlationId);
        if (!"APPROVED".equals(record.lifecycleState())
                || !record.decisionDueAt().isAfter(Instant.now())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The support access approval is not active.");
        }
        // Approval does not freeze a scope forever. Re-evaluate the active
        // catalog at activation so a retired scope cannot create a zombie JIT
        // session from an older approved request.
        supportRequestPolicy(record.scopes());
        if (!"STANDARD".equals(record.accessMode())
                || !"L1".equals(record.riskTier())
                || !record.customerApprovalRequired()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The approved support request does not match the executable JIT policy.");
        }
        String customerApprovalEvidenceMode = record.customerApprovalRequired()
                ? customerApprovalEvidencePolicy.requireVerified(record.approvalReference())
                : "NOT_REQUIRED";
        ProviderTenant tenant = context.requireTenant(record.tenantId());
        context.requireSupportReadyTenant(tenant);
        supportActivationGate.requireEnabled();
        String token = context.randomToken();
        ProviderSupportSessionRepository.ActivatedSupportSession activation =
                supportSessionRepository.activateApprovedRequest(
                        requestId, request.version(), actor.operatorId(),
                        actor.authSessionId(), context.sha256(token))
                        .orElseThrow(() -> new BaseException(
                                ErrorCode.RESOURCE_CONFLICT,
                                "The support access approval changed or expired."));
        UUID sessionId = activation.supportSessionId();
        Instant expiresAt = activation.expiresAt();
        ProviderDtos.SupportSessionSummary session = supportSessionRepository.summary(sessionId);
        auditService.success(
                "provider.support-access.activated", "SUPPORT_SESSION", sessionId.toString(),
                tenant.getProviderTenantId(), tenant.getOrganizationId(), correlationId,
                Map.of(
                        "requestId", requestId,
                        "scopes", record.scopes(),
                        "expiresAt", expiresAt,
                        "customerApprovalEvidenceMode", customerApprovalEvidenceMode,
                        "approvalReferenceHash", context.sha256(record.approvalReference() == null
                                ? "NOT_REQUIRED"
                                : record.approvalReference())));
        ProviderDtos.SupportAccessRequestSummary activatedRequest =
                supportRequestRepository.summary(requestId);
        return new ProviderDtos.SupportSessionGrant(
                session,
                token,
                ProviderSupportDtos.accessRequestLedgerItem(activatedRequest, true));
    }

    ProviderDtos.SupportAccessRequestSummary cancelSupportAccessRequest(
            UUID requestId,
            String correlationId,
            ProviderDtos.CancelSupportAccessRequest request) {
        ProviderRequestContext.requirePermission("SUPPORT_SESSION_WRITE");
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        supportSessionLifecycleService.expireElapsedSessions();
        ProviderSupportRequestRepository.SupportAccessRequestRecord record =
                supportRequestSecurityPolicy.requireCancellationTarget(
                        supportRequestRepository.byId(requestId), requestId, actor, correlationId);
        context.requireVersion(record.version(), request.version());
        if (!supportRequestRepository.cancel(
                requestId, request.version(), actor.operatorId(), request.reason().trim())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The support access request changed.");
        }
        ProviderTenant tenant = context.requireTenant(record.tenantId());
        auditService.success(
                "provider.support-access.cancelled", "SUPPORT_ACCESS_REQUEST", requestId.toString(),
                tenant.getProviderTenantId(), tenant.getOrganizationId(), correlationId,
                Map.of("reason", request.reason().trim()));
        return supportRequestRepository.summary(requestId);
    }

    ProviderDtos.SupportAccessRequestSummary reviewSupportAccessRequest(
            UUID requestId,
            String correlationId,
            ProviderDtos.ReviewSupportAccessRequest request) {
        ProviderRequestContext.requirePermission("SUPPORT_POST_REVIEW");
        ProviderSupportRequestRepository.SupportAccessRequestRecord record =
                requireSupportAccessRequest(requestId);
        context.requireVersion(record.version(), request.version());
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        supportRequestSecurityPolicy.requireIndependentPostReview(record, actor, correlationId);
        if (!supportRequestRepository.review(
                requestId, request.version(), actor.operatorId(), request.summary().trim())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The support access review state changed.");
        }
        ProviderTenant tenant = context.requireTenant(record.tenantId());
        auditService.success(
                "provider.support-access.reviewed", "SUPPORT_ACCESS_REQUEST", requestId.toString(),
                tenant.getProviderTenantId(), tenant.getOrganizationId(), correlationId,
                Map.of("summary", request.summary().trim(), "sessionId",
                        record.supportSessionId() == null ? "" : record.supportSessionId().toString()));
        return supportRequestRepository.summary(requestId);
    }

    ProviderDtos.SupportSessionSummary revokeSupportSession(
            UUID sessionId,
            String correlationId,
            ProviderDtos.RevokeSupportSessionRequest request) {
        ProviderRequestContext.requirePermission("SUPPORT_SESSION_WRITE");
        supportSessionLifecycleService.expireElapsedSessions();
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        ProviderSupportSessionRepository.SupportSessionRecord record =
                supportRequestSecurityPolicy.requireRevocationTarget(
                        supportSessionRepository.session(sessionId), sessionId, actor, correlationId);
        context.requireVersion(record.version(), request.version());
        if (!"ACTIVE".equals(record.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The support session is not active.");
        }
        ProviderTenant tenant = context.requireTenant(record.tenantId());
        if (!supportSessionRepository.revoke(
                sessionId, actor.operatorId(), record.version())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The support session changed before it could be revoked.");
        }
        auditService.success(
                "provider.support-session.revoked", "SUPPORT_SESSION", sessionId.toString(),
                tenant.getProviderTenantId(), tenant.getOrganizationId(), correlationId,
                Map.of("justification", request.justification()));
        return supportSessionRepository.summary(sessionId);
    }

    private ProviderSupportRequestRepository.SupportAccessRequestRecord requireSupportAccessRequest(
            UUID requestId) {
        supportSessionLifecycleService.expireElapsedSessions();
        return supportRequestRepository.byId(requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private SupportRequestPolicy supportRequestPolicy(List<String> requestedScopes) {
        LinkedHashSet<String> scopeSet = requestedScopes.stream()
                .map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new));
        ProviderOperationsRepository.SupportPolicy policy = operationsRepository.supportPolicy(scopeSet);
        if (!scopeSet.equals(Set.of(ProviderSupportAccessPolicy.EXECUTABLE_SCOPE))
                || policy.matchedScopes() != 1
                || !"L1".equals(policy.riskTier())
                || !policy.requiresCustomerApproval()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Only the active L1 customer-approved tenant experience preview scope is executable.");
        }
        return new SupportRequestPolicy(
                scopeSet.stream().sorted().toList(),
                policy.riskTier(),
                policy.requiresCustomerApproval());
    }

    private record SupportRequestPolicy(
            List<String> scopes,
            String riskTier,
            boolean customerApprovalRequired) {
    }
}
