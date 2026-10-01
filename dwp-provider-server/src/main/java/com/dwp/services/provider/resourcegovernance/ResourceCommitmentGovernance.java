package com.dwp.services.provider.resourcegovernance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ActiveOverride;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendLedgerEntryRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.Commitment;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ControlPeriod;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.InternalEvidenceFreshness;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.LedgerEntry;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.LedgerPage;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.LedgerTotals;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.UpsertCommitmentRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.CommitmentRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.InternalEvidenceFreshnessRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.LedgerRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.LedgerTotalsRow;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ResourceCommitmentGovernance {

    private static final int MAX_LEDGER_ENTRIES = 250;
    private final ResourceGovernanceRepository repository;
    private final ProviderAuditService audit;

    ResourceCommitmentGovernance(
            ResourceGovernanceRepository repository,
            ProviderAuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public List<Commitment> commitments(UUID tenantId) {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.RESOURCE_READ);
        return repository.commitments(tenantId).stream().map(this::commitment).toList();
    }

    public LedgerPage ledger(UUID tenantId, String resourceKey, int requestedLimit) {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.RESOURCE_READ);
        requireCommitment(tenantId, resourceKey);
        int limit = boundedLimit(requestedLimit);
        List<LedgerRow> rows = repository.ledger(tenantId, resourceKey, limit + 1);
        return new LedgerPage(
                rows.stream().limit(limit).map(this::ledger).toList(),
                limit,
                rows.size() > limit);
    }

    public Commitment upsertCommitment(
            UUID tenantId,
            String resourceKey,
            UpsertCommitmentRequest request,
            String correlationId) {
        requireTenantSelectionWrite();
        String canonicalKey = resourceKey.trim();
        UpsertCommitmentRequest normalized = normalize(request);
        validateCommitment(normalized);
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        CommitmentRow existing = repository.lockCommitment(tenantId, canonicalKey).orElse(null);
        if (existing == null) {
            if (normalized.version() != null) {
                throw conflict("A version is only valid when replacing an existing commitment.");
            }
            if (!repository.tenantExists(tenantId)) throw new BaseException(ErrorCode.NOT_FOUND);
            CommitmentRow created;
            try {
                created = repository.createCommitment(tenantId, canonicalKey, normalized, actor.operatorId());
            } catch (DataIntegrityViolationException exception) {
                throw conflict("The resource commitment was created concurrently. Refresh and retry.");
            }
            audit.success(
                    "provider.resource-governance.commitment-created",
                    "RESOURCE_COMMITMENT",
                    tenantId + ":" + canonicalKey,
                    tenantId,
                    null,
                    correlationId,
                    Map.of("resourceKey", canonicalKey, "unit", created.unit(),
                            "lifecycleState", created.lifecycleState(), "version", created.version(),
                            "externalFeedState", created.externalFeedState()));
            return commitment(created);
        }
        if (normalized.version() == null || normalized.version() != existing.version()) {
            throw conflict("The resource commitment changed. Refresh before saving it.");
        }
        if (repository.hasLedgerEntries(tenantId, canonicalKey)
                && (!existing.unit().equals(normalized.unit())
                || !same(existing.currencyCode(), normalized.currencyCode()))) {
            throw invalid("A commitment unit or currency cannot change after immutable ledger evidence exists.");
        }
        LedgerTotals totals = totals(existing, repository.ledgerTotals(
                tenantId,
                canonicalKey,
                normalized.controlPeriodStartsAt(),
                normalized.controlPeriodEndsAt()));
        ensureLimitsCoverTotals(normalized, totals);
        if (!repository.updateCommitment(
                tenantId, canonicalKey, normalized.version(), normalized, actor.operatorId())) {
            throw conflict("The resource commitment changed. Refresh before saving it.");
        }
        CommitmentRow updated = requireCommitment(tenantId, canonicalKey);
        audit.success(
                "provider.resource-governance.commitment-updated",
                "RESOURCE_COMMITMENT",
                tenantId + ":" + canonicalKey,
                tenantId,
                null,
                correlationId,
                Map.of("resourceKey", canonicalKey, "beforeVersion", existing.version(),
                        "afterVersion", updated.version(), "lifecycleState", updated.lifecycleState(),
                        "externalFeedState", updated.externalFeedState()));
        return commitment(updated);
    }

    public LedgerEntry appendLedger(
            UUID tenantId,
            String resourceKey,
            AppendLedgerEntryRequest request,
            String correlationId) {
        requireTenantSelectionWrite();
        String canonicalKey = resourceKey.trim();
        AppendLedgerEntryRequest normalized = normalize(request);
        CommitmentRow commitment = repository.lockCommitment(tenantId, canonicalKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        LedgerRow existing = repository.ledgerByIdempotency(tenantId, normalized.idempotencyKey())
                .orElse(null);
        if (existing != null) {
            if (!sameLedgerCommand(existing, canonicalKey, normalized)) {
                throw conflict("The idempotency key is already bound to a different ledger command.");
            }
            return ledger(existing);
        }
        if (!"ACTIVE".equals(commitment.lifecycleState())) {
            throw invalid("Only active resource commitments can receive new ledger evidence.");
        }
        validateLedgerCommand(commitment, normalized);
        requireActiveControlPeriod(commitment, normalized.occurredAt());
        LedgerTotals totals = totals(commitment, repository.ledgerTotals(
                tenantId,
                canonicalKey,
                commitment.controlPeriodStart(),
                commitment.controlPeriodEnd()));
        ensureLedgerFitsCommitment(commitment, totals, normalized);
        UUID ledgerId = UUID.randomUUID();
        boolean inserted;
        try {
            inserted = repository.appendLedger(
                    ledgerId, tenantId, canonicalKey, commitment, normalized,
                    ProviderRequestContext.require().operatorId());
        } catch (DataIntegrityViolationException exception) {
            inserted = false;
        }
        if (!inserted) {
            LedgerRow raced = repository.ledgerByIdempotency(tenantId, normalized.idempotencyKey())
                    .orElseThrow(() -> conflict("The ledger command could not be recorded."));
            if (!sameLedgerCommand(raced, canonicalKey, normalized)) {
                throw conflict("The idempotency key is already bound to a different ledger command.");
            }
            return ledger(raced);
        }
        LedgerRow saved = repository.ledgerByIdempotency(tenantId, normalized.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("New resource ledger entry is missing."));
        audit.success(
                "provider.resource-governance.ledger-appended",
                "RESOURCE_LEDGER_ENTRY",
                saved.ledgerEntryId().toString(),
                tenantId,
                null,
                correlationId,
                Map.of("resourceKey", canonicalKey, "entryType", saved.entryType(),
                        "amount", saved.amount(), "unit", saved.unit(),
                        "evidenceRef", saved.evidenceRef(),
                        "externalFeedState", commitment.externalFeedState()));
        return ledger(saved);
    }

    private Commitment commitment(CommitmentRow row) {
        LedgerTotalsRow totalRow = repository.ledgerTotals(
                row.tenantId(), row.resourceKey(), row.controlPeriodStart(), row.controlPeriodEnd());
        InternalEvidenceFreshnessRow freshness = repository.internalEvidenceFreshness(
                row.tenantId(), row.resourceKey(), row.controlPeriodStart(), row.controlPeriodEnd());
        return new Commitment(
                row.tenantId(), row.tenantKey(), row.tenantDisplayName(), row.resourceKey(), row.unit(),
                row.quotaLimit(), row.budgetLimit(), row.currencyCode(), row.lifecycleState(),
                row.sourceSystem(), row.externalFeedState(), row.controlMode(), row.controlScope(),
                row.activeOverrideId() == null ? null
                        : new ActiveOverride(row.activeOverrideId(), row.activeOverrideExpiresAt()),
                controlPeriod(row), internalEvidenceFreshness(row, freshness), row.version(), row.updatedAt(),
                totals(row, totalRow));
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

    private LedgerEntry ledger(LedgerRow row) {
        return new LedgerEntry(
                row.ledgerEntryId(), row.tenantId(), row.resourceKey(), row.entryType(), row.amount(),
                row.unit(), row.currencyCode(), row.evidenceRef(), row.idempotencyKey(), row.reason(),
                row.occurredAt(), row.controlPeriodStart(), row.controlPeriodEnd(),
                row.recordedBy(), row.recordedAt());
    }

    private CommitmentRow requireCommitment(UUID tenantId, String resourceKey) {
        return repository.commitment(tenantId, resourceKey)
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

    private void validateLedgerCommand(CommitmentRow commitment, AppendLedgerEntryRequest request) {
        boolean budgetEntry = request.entryType().startsWith("BUDGET_");
        if (budgetEntry) {
            if (!"CURRENCY_MINOR".equals(request.unit())) {
                throw invalid("Budget ledger evidence must use the CURRENCY_MINOR unit.");
            }
            if (commitment.budgetLimit() == null || !same(commitment.currencyCode(), request.currencyCode())) {
                throw invalid("A budget ledger entry needs the commitment's configured budget currency.");
            }
        } else {
            if (!commitment.unit().equals(request.unit())) {
                throw invalid("A resource ledger entry unit must match its resource commitment.");
            }
            if (request.currencyCode() != null) {
                throw invalid("Only budget ledger entries can carry a currency code.");
            }
        }
    }

    private void ensureLedgerFitsCommitment(
            CommitmentRow commitment,
            LedgerTotals totals,
            AppendLedgerEntryRequest request) {
        switch (request.entryType()) {
            case "ALLOCATE", "ADJUST" -> {
                if ("HARD_BLOCK".equals(commitment.controlMode())
                        && commitment.quotaLimit() != null
                        && totals.allocationBalance().add(request.amount())
                        .compareTo(commitment.quotaLimit()) > 0) {
                    throw invalid("The internal allocation would exceed the committed quota.");
                }
            }
            case "RELEASE" -> {
                if (request.amount().compareTo(totals.allocationBalance()) > 0) {
                    throw invalid("A release cannot exceed recorded internal allocation.");
                }
            }
            case "BUDGET_RESERVE", "BUDGET_SPEND" -> {
                BigDecimal committed = totals.budgetReservationBalance()
                        .add(totals.budgetSpentInternalEvidence());
                if ("HARD_BLOCK".equals(commitment.controlMode())
                        && commitment.budgetLimit() != null
                        && committed.add(request.amount()).compareTo(commitment.budgetLimit()) > 0) {
                    throw invalid("The internal budget evidence would exceed the committed budget.");
                }
            }
            case "BUDGET_RELEASE" -> {
                if (request.amount().compareTo(totals.budgetReservationBalance()) > 0) {
                    throw invalid("A budget release cannot exceed recorded budget reservations.");
                }
            }
            case "METER" -> {
                // Meter rows remain manual internal evidence. They do not assert an external usage feed.
            }
            default -> throw invalid("Unsupported resource-ledger entry type.");
        }
    }
    private ControlPeriod controlPeriod(CommitmentRow row) {
        return new ControlPeriod(
                row.controlPeriodStart(), row.controlPeriodEnd(),
                controlPeriodState(row.controlPeriodStart(), row.controlPeriodEnd(), Instant.now()));
    }

    private InternalEvidenceFreshness internalEvidenceFreshness(
            CommitmentRow row,
            InternalEvidenceFreshnessRow freshness) {
        String periodState = controlPeriodState(
                row.controlPeriodStart(), row.controlPeriodEnd(), Instant.now());
        String state;
        if ("LEGACY_UNCONFIGURED".equals(periodState)) {
            state = "LEGACY_UNCONFIGURED";
        } else if (!"ACTIVE".equals(periodState)) {
            state = "PERIOD_NOT_ACTIVE";
        } else if (freshness.entryCount() == 0) {
            state = "NO_CURRENT_PERIOD_EVIDENCE";
        } else {
            state = "CURRENT_PERIOD_EVIDENCE";
        }
        return new InternalEvidenceFreshness(
                state, freshness.entryCount(), freshness.latestOccurredAt(), freshness.latestRecordedAt());
    }

    private String controlPeriodState(Instant start, Instant end, Instant now) {
        if (start == null || end == null) return "LEGACY_UNCONFIGURED";
        if (now.isBefore(start)) return "UPCOMING";
        if (!now.isBefore(end)) return "ENDED";
        return "ACTIVE";
    }

    private String controlState(BigDecimal limit, BigDecimal committed, String mode) {
        if (limit == null) return "NOT_CONFIGURED";
        if (committed.compareTo(limit) <= 0) return "WITHIN_LIMIT";
        return "HARD_BLOCK".equals(mode) ? "HARD_BLOCKED" : "SOFT_ALERT";
    }

    private void requireActiveControlPeriod(CommitmentRow commitment, Instant occurredAt) {
        String state = controlPeriodState(
                commitment.controlPeriodStart(), commitment.controlPeriodEnd(), Instant.now());
        if (!"ACTIVE".equals(state)) {
            throw invalid("Internal evidence can only be recorded during an active control period.");
        }
        if (occurredAt.isBefore(commitment.controlPeriodStart())
                || !occurredAt.isBefore(commitment.controlPeriodEnd())) {
            throw invalid("The evidence occurrence time must belong to the active control period.");
        }
    }
    private UpsertCommitmentRequest normalize(UpsertCommitmentRequest request) {
        return new UpsertCommitmentRequest(
                request.unit().trim(), request.quotaLimit(), request.budgetLimit(),
                trimToNull(request.currencyCode()), request.controlPeriodStartsAt(),
                request.controlPeriodEndsAt(), request.controlMode().trim(),
                request.lifecycleState().trim(), request.version());
    }

    private AppendLedgerEntryRequest normalize(AppendLedgerEntryRequest request) {
        return new AppendLedgerEntryRequest(
                request.entryType().trim(), request.amount(), request.unit().trim(),
                trimToNull(request.currencyCode()), request.evidenceRef().trim(),
                request.idempotencyKey().trim(), request.reason().trim(), request.occurredAt());
    }
    private boolean sameLedgerCommand(
            LedgerRow existing,
            String resourceKey,
            AppendLedgerEntryRequest request) {
        return existing.resourceKey().equals(resourceKey)
                && existing.entryType().equals(request.entryType())
                && existing.amount().compareTo(request.amount()) == 0
                && existing.unit().equals(request.unit())
                && same(existing.currencyCode(), request.currencyCode())
                && existing.evidenceRef().equals(request.evidenceRef())
                && existing.reason().equals(request.reason())
                && existing.occurredAt().equals(request.occurredAt());
    }

    /** A write grant alone must not become a way to select an otherwise undisclosed tenant. */
    private void requireTenantSelectionWrite() {
        ProviderRequestContext.requirePermission(ResourceGovernanceService.RESOURCE_WRITE);
        ProviderRequestContext.requirePermission("ESTATE_READ");
    }

    private int boundedLimit(int requested) {
        return Math.min(Math.max(requested, 1), MAX_LEDGER_ENTRIES);
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
