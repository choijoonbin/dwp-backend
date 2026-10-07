package com.dwp.services.provider.resourcegovernance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateResourceCommitmentChangeRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CommitmentDefinition;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.LedgerTotals;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChange;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChangePage;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ResourceCommitmentChangeDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.UpsertCommitmentRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.CommitmentRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.LedgerTotalsRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ResourceCommitmentChangeRow;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class ResourceCommitmentChangeGovernance {

    private static final int LIST_LIMIT = 100;

    private final ResourceGovernanceRepository repository;
    private final ProviderAuditService audit;

    ResourceCommitmentChangeGovernance(
            ResourceGovernanceRepository repository,
            ProviderAuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public ResourceCommitmentChangePage resourceChanges(UUID tenantId) {
        ProviderRequestContext.requirePermission(ResourceGovernancePermissions.RESOURCE_READ);
        ProviderRequestContext.requirePermission("ESTATE_READ");
        List<ResourceCommitmentChangeRow> rows = repository.resourceChanges(
                tenantId, LIST_LIMIT + 1);
        return new ResourceCommitmentChangePage(
                rows.stream().limit(LIST_LIMIT).map(this::resourceChange).toList(),
                LIST_LIMIT,
                rows.size() > LIST_LIMIT);
    }

    public ResourceCommitmentChange createResourceChange(
            UUID tenantId,
            String resourceKey,
            CreateResourceCommitmentChangeRequest request,
            String correlationId) {
        requireTenantSelectionWrite();
        String canonicalKey = resourceKey.trim();
        UpsertCommitmentRequest proposed = normalize(request.proposed());
        validateCommitment(proposed);
        if (!repository.tenantExists(tenantId)) throw new BaseException(ErrorCode.NOT_FOUND);
        if (repository.hasActiveTemporaryOverride(tenantId, canonicalKey)) {
            throw conflict("An active temporary override must expire before another change is requested.");
        }
        CommitmentRow baseline = repository.lockCommitment(tenantId, canonicalKey).orElse(null);
        validateResourceChangeBoundary(tenantId, request, proposed, baseline);
        if (baseline != null) {
            if (repository.hasLedgerEntries(tenantId, canonicalKey)
                    && (!baseline.unit().equals(proposed.unit())
                    || !same(baseline.currencyCode(), proposed.currencyCode()))) {
                throw invalid("A commitment unit or currency cannot change after immutable ledger evidence exists.");
            }
            LedgerTotals totals = totals(baseline, repository.ledgerTotals(
                    tenantId, canonicalKey,
                    proposed.controlPeriodStartsAt(), proposed.controlPeriodEndsAt()));
            ensureLimitsCoverTotals(proposed, totals);
        }
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        String requestKey = request.requestKey().trim();
        JsonNode baselineDefinition = baseline == null ? null : definitionNode(definition(baseline));
        JsonNode proposedDefinition = definitionNode(definition(proposed));
        ResourceCommitmentChangeRow duplicate = repository.resourceChangeByRequestKey(
                actor.operatorId(), requestKey).orElse(null);
        if (duplicate != null) {
            if (!sameResourceChange(
                    duplicate, tenantId, canonicalKey, request, baselineDefinition, proposedDefinition)) {
                throw conflict("The resource change request key is already bound to different content.");
            }
            return resourceChange(duplicate);
        }
        CreateResourceCommitmentChangeRequest normalized = new CreateResourceCommitmentChangeRequest(
                request.changeKind().trim(), request.baselineCommitmentVersion(), proposed,
                request.commercialRenewalRevisionId(), request.overrideExpiresAt(), requestKey,
                request.justification().trim());
        ResourceCommitmentChangeRow saved;
        try {
            saved = repository.createResourceChange(
                    UUID.randomUUID(), tenantId, canonicalKey, normalized, baselineDefinition,
                    proposedDefinition, Instant.now().plus(Duration.ofHours(48)), actor.operatorId());
        } catch (DataIntegrityViolationException exception) {
            throw conflict("An open resource change already exists or the request was created concurrently.");
        }
        audit.success(
                "provider.resource-governance.change-requested",
                "RESOURCE_COMMITMENT_CHANGE",
                saved.changeRequestId().toString(),
                tenantId,
                null,
                correlationId,
                Map.of("resourceKey", canonicalKey, "changeKind", saved.changeKind(),
                        "baselineVersion", saved.baselineCommitmentVersion() == null
                                ? "NEW" : saved.baselineCommitmentVersion(),
                        "reservationState", saved.reservationState()));
        return resourceChange(saved);
    }

    public ResourceCommitmentChange decideResourceChange(
            UUID changeRequestId,
            ResourceCommitmentChangeDecisionRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(ResourceGovernancePermissions.RESOURCE_APPROVE);
        ProviderRequestContext.requirePermission("ESTATE_READ");
        ResourceCommitmentChangeRow before = repository.lockResourceChange(changeRequestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Long actorId = ProviderRequestContext.require().operatorId();
        if (Objects.equals(actorId, before.requestedBy())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "A resource-change requester cannot independently decide the same request.");
        }
        if ("APPROVED".equals(request.decision())) validateResourceChangeEvidence(before);
        ResourceCommitmentChangeDecisionRequest normalized =
                new ResourceCommitmentChangeDecisionRequest(
                        request.version(), request.decision().trim(), request.reason().trim());
        if (!repository.decideResourceChange(changeRequestId, normalized, actorId)) {
            throw conflict("The resource change changed, expired, or is no longer awaiting approval.");
        }
        ResourceCommitmentChangeRow saved = requireResourceChange(changeRequestId);
        audit.success(
                "provider.resource-governance.change-" + request.decision().toLowerCase(Locale.ROOT),
                "RESOURCE_COMMITMENT_CHANGE",
                changeRequestId.toString(),
                saved.tenantId(),
                null,
                correlationId,
                Map.of("resourceKey", saved.resourceKey(), "decision", request.decision(),
                        "reason", request.reason(), "reservationState", saved.reservationState()));
        return resourceChange(saved);
    }

    public ResourceCommitmentChange publishResourceChange(
            UUID changeRequestId,
            ResourceGovernanceDtos.VersionedReasonRequest request,
            String correlationId) {
        requireTenantSelectionWrite();
        ResourceCommitmentChangeRow change = repository.lockResourceChange(changeRequestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!"APPROVED".equals(change.lifecycleState())) {
            throw conflict("Only a current independently approved resource change can be published.");
        }
        validateResourceChangeEvidence(change);
        CommitmentRow baseline = repository.lockCommitment(change.tenantId(), change.resourceKey())
                .orElse(null);
        requireCurrentResourceBaseline(change, baseline);
        UpsertCommitmentRequest proposed = definitionRequest(
                definition(change.proposedDefinition()), change.baselineCommitmentVersion());
        validateCommitment(proposed);
        if (baseline != null) {
            LedgerTotals totals = totals(baseline, repository.ledgerTotals(
                    change.tenantId(), change.resourceKey(),
                    proposed.controlPeriodStartsAt(), proposed.controlPeriodEndsAt()));
            ensureLimitsCoverTotals(proposed, totals);
        }
        long actorId = ProviderRequestContext.require().operatorId();
        if ("CONTRACT_CHANGE".equals(change.changeKind())) {
            if (baseline == null) {
                repository.createCommitment(
                        change.tenantId(), change.resourceKey(), proposed, actorId);
            } else if (!repository.updateCommitment(
                    change.tenantId(), change.resourceKey(), baseline.version(), proposed, actorId)) {
                throw conflict("The commitment changed while this approved proposal was being published.");
            }
        } else if (repository.hasActiveTemporaryOverride(change.tenantId(), change.resourceKey())) {
            throw conflict("Another temporary override is already active.");
        }
        if (!repository.markResourceChangePublished(changeRequestId, request.version(), actorId)) {
            throw conflict("The approved resource change changed or expired before publication.");
        }
        ResourceCommitmentChangeRow saved = requireResourceChange(changeRequestId);
        audit.success(
                "provider.resource-governance.change-published",
                "RESOURCE_COMMITMENT_CHANGE",
                changeRequestId.toString(),
                saved.tenantId(),
                null,
                correlationId,
                Map.of("resourceKey", saved.resourceKey(), "changeKind", saved.changeKind(),
                        "reason", request.reason(), "reservationState", saved.reservationState()));
        return resourceChange(saved);
    }

    private LedgerTotals totals(CommitmentRow commitment, LedgerTotalsRow row) {
        BigDecimal allocationBalance = row.allocated().add(row.adjusted()).subtract(row.released());
        BigDecimal budgetReservation = row.budgetReserved().subtract(row.budgetReleased());
        BigDecimal internalBudgetCommitted = budgetReservation.add(row.budgetSpent());
        BigDecimal remainingQuota = remaining(commitment.quotaLimit(), allocationBalance);
        BigDecimal remainingBudget = remaining(commitment.budgetLimit(), internalBudgetCommitted);
        return new LedgerTotals(
                row.allocated(), row.released(), row.adjusted(), row.metered(),
                row.budgetReserved(), row.budgetReleased(), row.budgetSpent(), allocationBalance,
                remainingQuota, budgetReservation, remainingBudget,
                controlState(commitment.quotaLimit(), allocationBalance, commitment.controlMode()),
                controlState(commitment.budgetLimit(), internalBudgetCommitted, commitment.controlMode()));
    }
    private ResourceCommitmentChange resourceChange(ResourceCommitmentChangeRow row) {
        return new ResourceCommitmentChange(
                row.changeRequestId(), row.tenantId(), row.tenantKey(), row.tenantDisplayName(),
                row.resourceKey(), row.changeKind(), row.baselineCommitmentVersion(),
                definition(row.baselineDefinition()), definition(row.proposedDefinition()),
                row.commercialRenewalRevisionId(), row.overrideExpiresAt(), row.lifecycleState(),
                row.reservationState(), row.justification(), row.decisionDueAt(), row.requestedBy(),
                row.requestedAt(), row.decidedBy(), row.decidedAt(), row.decisionReason(),
                row.publishedBy(), row.publishedAt(), row.version(), row.updatedAt());
    }
    private ResourceCommitmentChangeRow requireResourceChange(UUID changeRequestId) {
        return repository.resourceChange(changeRequestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }
    private void validateCommitment(UpsertCommitmentRequest request) {
        if (request.quotaLimit() == null && request.budgetLimit() == null) {
            throw invalid("A resource commitment needs a quota limit, a budget limit, or both.");
        }
        if ((request.budgetLimit() == null) != (request.currencyCode() == null)) {
            throw invalid("A budget limit and ISO currency must be supplied together.");
        }
        if (!request.controlPeriodEndsAt().isAfter(request.controlPeriodStartsAt())) {
            throw invalid("The control-period end must be after its start.");
        }
    }

    private void validateResourceChangeBoundary(
            UUID tenantId,
            CreateResourceCommitmentChangeRequest request,
            UpsertCommitmentRequest proposed,
            CommitmentRow baseline) {
        Long actualVersion = baseline == null ? null : baseline.version();
        if (!Objects.equals(request.baselineCommitmentVersion(), actualVersion)
                || !Objects.equals(proposed.version(), actualVersion)) {
            throw conflict("The resource commitment baseline changed. Refresh before requesting review.");
        }
        Instant now = Instant.now();
        if ("CONTRACT_CHANGE".equals(request.changeKind())) {
            if (request.commercialRenewalRevisionId() == null || request.overrideExpiresAt() != null) {
                throw invalid("A contract change needs published commercial evidence and cannot have an override expiry.");
            }
            if (!repository.publishedCommercialEvidenceMatchesTenant(
                    tenantId, request.commercialRenewalRevisionId())) {
                throw invalid("The commercial renewal must be published for the selected tenant organization.");
            }
            return;
        }
        if (baseline == null) {
            throw invalid("A temporary override requires an existing contract-backed commitment.");
        }
        if (request.commercialRenewalRevisionId() != null || request.overrideExpiresAt() == null) {
            throw invalid("A temporary override needs an expiry and cannot claim commercial approval.");
        }
        if (request.overrideExpiresAt().isBefore(now.plus(Duration.ofMinutes(15)))
                || request.overrideExpiresAt().isAfter(now.plus(Duration.ofDays(30)))) {
            throw invalid("A temporary override must expire between 15 minutes and 30 days from now.");
        }
        if (request.overrideExpiresAt().isAfter(proposed.controlPeriodEndsAt())) {
            throw invalid("A temporary override cannot outlive its proposed control period.");
        }
        if (!baseline.unit().equals(proposed.unit())
                || !same(baseline.currencyCode(), proposed.currencyCode())
                || !baseline.controlPeriodStart().equals(proposed.controlPeriodStartsAt())
                || !baseline.controlPeriodEnd().equals(proposed.controlPeriodEndsAt())
                || !baseline.lifecycleState().equals(proposed.lifecycleState())) {
            throw invalid("A temporary override can change only quota, budget, and soft/hard control mode.");
        }
    }

    private void validateResourceChangeEvidence(ResourceCommitmentChangeRow change) {
        Instant now = Instant.now();
        if (!change.decisionDueAt().isAfter(now)) {
            throw conflict("The resource change review window has expired.");
        }
        if ("CONTRACT_CHANGE".equals(change.changeKind())) {
            if (change.commercialRenewalRevisionId() == null
                    || !repository.publishedCommercialEvidenceMatchesTenant(
                    change.tenantId(), change.commercialRenewalRevisionId())) {
                throw conflict("Published commercial evidence is no longer valid for this tenant.");
            }
        } else if (change.overrideExpiresAt() == null || !change.overrideExpiresAt().isAfter(now)) {
            throw conflict("The temporary override expiry is no longer current.");
        }
    }

    private void requireCurrentResourceBaseline(
            ResourceCommitmentChangeRow change,
            CommitmentRow baseline) {
        if (change.baselineCommitmentVersion() == null) {
            if (baseline != null) {
                throw conflict("A commitment was created after this proposal. Create a new reviewed change.");
            }
            return;
        }
        if (baseline == null || baseline.version() != change.baselineCommitmentVersion()) {
            throw conflict("The commitment changed after review. Create a new change request.");
        }
        if (repository.hasActiveTemporaryOverride(change.tenantId(), change.resourceKey())) {
            throw conflict("An active temporary override must expire before this change can publish.");
        }
    }

    private boolean sameResourceChange(
            ResourceCommitmentChangeRow existing,
            UUID tenantId,
            String resourceKey,
            CreateResourceCommitmentChangeRequest request,
            JsonNode baselineDefinition,
            JsonNode proposedDefinition) {
        return existing.tenantId().equals(tenantId)
                && existing.resourceKey().equals(resourceKey)
                && existing.changeKind().equals(request.changeKind())
                && Objects.equals(existing.baselineCommitmentVersion(),
                request.baselineCommitmentVersion())
                && Objects.equals(existing.baselineDefinition(), baselineDefinition)
                && existing.proposedDefinition().equals(proposedDefinition)
                && Objects.equals(existing.commercialRenewalRevisionId(),
                request.commercialRenewalRevisionId())
                && Objects.equals(existing.overrideExpiresAt(), request.overrideExpiresAt())
                && existing.justification().equals(request.justification().trim());
    }

    private CommitmentDefinition definition(CommitmentRow row) {
        return new CommitmentDefinition(
                row.unit(), row.quotaLimit(), row.budgetLimit(), row.currencyCode(),
                row.controlPeriodStart(), row.controlPeriodEnd(), row.controlMode(),
                row.lifecycleState());
    }

    private CommitmentDefinition definition(UpsertCommitmentRequest request) {
        return new CommitmentDefinition(
                request.unit(), request.quotaLimit(), request.budgetLimit(), request.currencyCode(),
                request.controlPeriodStartsAt(), request.controlPeriodEndsAt(), request.controlMode(),
                request.lifecycleState());
    }

    private CommitmentDefinition definition(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return new CommitmentDefinition(
                node.path("unit").asText(), nullableDecimal(node.get("quotaLimit")),
                nullableDecimal(node.get("budgetLimit")), nullableText(node.get("currencyCode")),
                Instant.parse(node.path("controlPeriodStartsAt").asText()),
                Instant.parse(node.path("controlPeriodEndsAt").asText()),
                node.path("controlMode").asText(), node.path("lifecycleState").asText());
    }

    private ObjectNode definitionNode(CommitmentDefinition definition) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("unit", definition.unit());
        if (definition.quotaLimit() == null) node.putNull("quotaLimit");
        else node.put("quotaLimit", definition.quotaLimit());
        if (definition.budgetLimit() == null) node.putNull("budgetLimit");
        else node.put("budgetLimit", definition.budgetLimit());
        if (definition.currencyCode() == null) node.putNull("currencyCode");
        else node.put("currencyCode", definition.currencyCode());
        node.put("controlPeriodStartsAt", definition.controlPeriodStartsAt().toString());
        node.put("controlPeriodEndsAt", definition.controlPeriodEndsAt().toString());
        node.put("controlMode", definition.controlMode());
        node.put("lifecycleState", definition.lifecycleState());
        return node;
    }

    private UpsertCommitmentRequest definitionRequest(
            CommitmentDefinition definition,
            Long version) {
        return new UpsertCommitmentRequest(
                definition.unit(), definition.quotaLimit(), definition.budgetLimit(),
                definition.currencyCode(), definition.controlPeriodStartsAt(),
                definition.controlPeriodEndsAt(), definition.controlMode(),
                definition.lifecycleState(), version);
    }

    private BigDecimal nullableDecimal(JsonNode node) {
        return node == null || node.isNull() ? null : node.decimalValue();
    }

    private String nullableText(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private void ensureLimitsCoverTotals(UpsertCommitmentRequest request, LedgerTotals totals) {
        if ("HARD_BLOCK".equals(request.controlMode())
                && request.quotaLimit() != null
                && request.quotaLimit().compareTo(totals.allocationBalance()) < 0) {
            throw invalid("The quota limit cannot be lower than existing internal allocation evidence.");
        }
        BigDecimal internalBudgetCommitted = totals.budgetReservationBalance()
                .add(totals.budgetSpentInternalEvidence());
        if ("HARD_BLOCK".equals(request.controlMode())
                && request.budgetLimit() != null
                && request.budgetLimit().compareTo(internalBudgetCommitted) < 0) {
            throw invalid("The budget limit cannot be lower than existing internal budget evidence.");
        }
    }
    private String controlState(BigDecimal limit, BigDecimal committed, String mode) {
        if (limit == null) return "NOT_CONFIGURED";
        if (committed.compareTo(limit) <= 0) return "WITHIN_LIMIT";
        return "HARD_BLOCK".equals(mode) ? "HARD_BLOCKED" : "SOFT_ALERT";
    }
    private UpsertCommitmentRequest normalize(UpsertCommitmentRequest request) {
        return new UpsertCommitmentRequest(
                request.unit().trim(), request.quotaLimit(), request.budgetLimit(),
                trimToNull(request.currencyCode()), request.controlPeriodStartsAt(),
                request.controlPeriodEndsAt(), request.controlMode().trim(),
                request.lifecycleState().trim(), request.version());
    }
    /** A write grant alone must not become a way to select an otherwise undisclosed tenant. */
    private void requireTenantSelectionWrite() {
        ProviderRequestContext.requirePermission(ResourceGovernancePermissions.RESOURCE_WRITE);
        ProviderRequestContext.requirePermission("ESTATE_READ");
    }
    private BigDecimal remaining(BigDecimal limit, BigDecimal committed) {
        return limit == null ? null : limit.subtract(committed);
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
