package com.dwp.services.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.commercial.ProviderCommercialRenewalRepository;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class ProviderCommercialControl {

    private final ProviderOperationsRepository operationsRepository;
    private final ProviderCommercialRenewalRepository commercialRenewalRepository;
    private final ProviderAuditService auditService;
    private final ProviderControlPlaneContext context;

    ProviderCommercialControl(
            ProviderOperationsRepository operationsRepository,
            ProviderCommercialRenewalRepository commercialRenewalRepository,
            ProviderAuditService auditService,
            ProviderControlPlaneContext context) {
        this.operationsRepository = operationsRepository;
        this.commercialRenewalRepository = commercialRenewalRepository;
        this.auditService = auditService;
        this.context = context;
    }

    ProviderDtos.CommercialOverview commercialOverview() {
        ProviderRequestContext.requirePermission("COMMERCIAL_READ");
        return operationsRepository.commercialOverview();
    }

    List<ProviderDtos.SubscriptionRenewalRevision> subscriptionRenewals() {
        ProviderRequestContext.requirePermission("COMMERCIAL_READ");
        return commercialRenewalRepository.list();
    }

    ProviderDtos.SubscriptionRenewalRevision createSubscriptionRenewal(
            String correlationId,
            ProviderDtos.CreateSubscriptionRenewalRequest request) {
        ProviderRequestContext.requirePermission("COMMERCIAL_WRITE");
        String requestKey = context.normalizeIdempotencyKey(request.requestKey());
        String targetPlanKey = request.targetPlanKey().trim().toLowerCase(Locale.ROOT);
        String contractReference = request.proposedContractReference().trim();
        String reason = request.reason().trim();
        LinkedHashMap<String, Object> requestInput = new LinkedHashMap<>();
        requestInput.put("subscriptionId", request.subscriptionId());
        requestInput.put("subscriptionVersion", request.subscriptionVersion());
        requestInput.put("targetPlanKey", targetPlanKey);
        requestInput.put("proposedEndsAt", request.proposedEndsAt());
        requestInput.put("proposedContractReference", contractReference);
        requestInput.put("reason", reason);
        String requestFingerprint = context.sha256(context.json(requestInput));
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        ProviderCommercialRenewalRepository.RenewalRecord existing =
                commercialRenewalRepository.byKey(actor.operatorId(), requestKey).orElse(null);
        if (existing != null) {
            if (!context.constantTimeEquals(requestFingerprint, existing.requestFingerprint())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The commercial request key was already used for a different proposal.");
            }
            return commercialRenewalRepository.summary(existing.revisionId());
        }
        ProviderCommercialRenewalRepository.SubscriptionRecord subscription =
                commercialRenewalRepository.subscription(request.subscriptionId())
                        .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        context.requireVersion(subscription.version(), request.subscriptionVersion());
        if (!request.proposedEndsAt().isAfter(subscription.startsAt())
                || !request.proposedEndsAt().isAfter(Instant.now())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The proposed subscription end must be in the future and after its start.");
        }
        ProviderCommercialRenewalRepository.PlanRecord targetPlan =
                commercialRenewalRepository.activePlan(targetPlanKey)
                        .orElseThrow(() -> new BaseException(
                                ErrorCode.INVALID_INPUT_VALUE, "Unknown or inactive service plan."));
        List<String> currentEntitlements =
                commercialRenewalRepository.entitlements(subscription.servicePlanId());
        List<String> targetEntitlements =
                commercialRenewalRepository.entitlements(targetPlan.servicePlanId());
        List<String> added = targetEntitlements.stream()
                .filter(item -> !currentEntitlements.contains(item)).sorted().toList();
        List<String> removed = currentEntitlements.stream()
                .filter(item -> !targetEntitlements.contains(item)).sorted().toList();
        if (subscription.servicePlanId().equals(targetPlan.servicePlanId())
                && Objects.equals(subscription.endsAt(), request.proposedEndsAt())
                && Objects.equals(subscription.contractReference(), contractReference)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The renewal proposal has no changes.");
        }
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("subscriptionId", subscription.subscriptionId());
        content.put("baselineVersion", subscription.version());
        content.put("targetPlanKey", targetPlan.planKey());
        content.put("proposedEndsAt", request.proposedEndsAt());
        content.put("proposedContractReference", contractReference);
        content.put("reason", reason);
        content.put("addedEntitlements", added);
        content.put("removedEntitlements", removed);
        String contentHash = context.sha256(context.json(content));
        UUID revisionId;
        try {
            revisionId = commercialRenewalRepository.create(
                    subscription, targetPlan, request.proposedEndsAt(), contractReference,
                    reason, added, removed, contentHash, requestFingerprint, requestKey,
                    actor.operatorId());
        } catch (DataIntegrityViolationException exception) {
            ProviderCommercialRenewalRepository.RenewalRecord concurrent =
                    commercialRenewalRepository.byKey(actor.operatorId(), requestKey)
                            .orElseThrow(() -> exception);
            if (!context.constantTimeEquals(requestFingerprint, concurrent.requestFingerprint())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The commercial request key was concurrently used for a different proposal.");
            }
            revisionId = concurrent.revisionId();
        }
        auditService.success(
                "provider.subscription-renewal.requested", "SUBSCRIPTION_RENEWAL",
                revisionId.toString(), null, subscription.organizationId(), correlationId,
                Map.of(
                        "subscriptionId", subscription.subscriptionId(),
                        "targetPlanKey", targetPlan.planKey(),
                        "addedEntitlements", added,
                        "removedEntitlements", removed,
                        "impactedTenants", subscription.tenantCount(),
                        "contentSha256", contentHash));
        return commercialRenewalRepository.summary(revisionId);
    }

    ProviderDtos.SubscriptionRenewalRevision decideSubscriptionRenewal(
            UUID revisionId,
            String correlationId,
            ProviderDtos.DecideSubscriptionRenewalRequest request) {
        ProviderRequestContext.requirePermission("COMMERCIAL_APPROVE");
        ProviderCommercialRenewalRepository.RenewalRecord record = requireSubscriptionRenewal(revisionId);
        context.requireVersion(record.version(), request.version());
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        if (Objects.equals(record.requestedBy(), actor.operatorId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN, "Commercial renewal proposals cannot be self-approved.");
        }
        if (!"PENDING_APPROVAL".equals(record.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The renewal is not awaiting approval.");
        }
        if (!commercialRenewalRepository.decide(
                revisionId, request.version(), actor.operatorId(),
                request.decision(), request.reason().trim())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The renewal changed or expired.");
        }
        auditService.success(
                "provider.subscription-renewal." + request.decision().toLowerCase(Locale.ROOT),
                "SUBSCRIPTION_RENEWAL", revisionId.toString(), null, record.organizationId(),
                correlationId, Map.of("decision", request.decision(), "reason", request.reason().trim()));
        return commercialRenewalRepository.summary(revisionId);
    }

    ProviderDtos.SubscriptionRenewalRevision publishSubscriptionRenewal(
            UUID revisionId,
            String correlationId,
            ProviderDtos.PublishSubscriptionRenewalRequest request) {
        ProviderRequestContext.requirePermission("COMMERCIAL_WRITE");
        ProviderCommercialRenewalRepository.RenewalRecord record = requireSubscriptionRenewal(revisionId);
        context.requireVersion(record.version(), request.version());
        if (!"APPROVED".equals(record.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only approved renewals can be published.");
        }
        try {
            if (!commercialRenewalRepository.publish(
                    revisionId, request.version(), ProviderRequestContext.require().operatorId())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The subscription or renewal changed. Refresh before publishing.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The proposed contract reference conflicts with another subscription.");
        }
        ProviderDtos.SubscriptionRenewalRevision summary = commercialRenewalRepository.summary(revisionId);
        auditService.success(
                "provider.subscription-renewal.published", "SUBSCRIPTION_RENEWAL",
                revisionId.toString(), null, record.organizationId(), correlationId,
                Map.of(
                        "contentSha256", record.contentSha256(),
                        "executionState", summary.executionState(),
                        "notificationState", summary.notificationState()));
        return summary;
    }

    private ProviderCommercialRenewalRepository.RenewalRecord requireSubscriptionRenewal(
            UUID revisionId) {
        return commercialRenewalRepository.byId(revisionId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }
}
