package com.dwp.services.provider.rollout;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class FeatureRolloutApplicationReceiptService {

    /**
     * Logical evidence scope for a request path that accepted an authoritative decision. The
     * Gateway cache is replica-local, so this target deliberately does not claim fleet-wide
     * replica convergence.
     */
    public static final String GATEWAY_TARGET = "gateway.sampled-authority-request-path";

    private final FeatureRolloutRepository rolloutRepository;
    private final FeatureRolloutDecisionOutboxRepository decisionRepository;
    private final FeatureRolloutApplicationReceiptRepository receiptRepository;
    private final Clock clock;

    @Autowired
    public FeatureRolloutApplicationReceiptService(
            FeatureRolloutRepository rolloutRepository,
            FeatureRolloutDecisionOutboxRepository decisionRepository,
            FeatureRolloutApplicationReceiptRepository receiptRepository) {
        this(rolloutRepository, decisionRepository, receiptRepository, Clock.systemUTC());
    }

    FeatureRolloutApplicationReceiptService(
            FeatureRolloutRepository rolloutRepository,
            FeatureRolloutDecisionOutboxRepository decisionRepository,
            FeatureRolloutApplicationReceiptRepository receiptRepository,
            Clock clock) {
        this.rolloutRepository = rolloutRepository;
        this.decisionRepository = decisionRepository;
        this.receiptRepository = receiptRepository;
        this.clock = clock;
    }

    @Transactional
    public FeatureRolloutDtos.ApplicationReceipt acknowledge(
            FeatureRolloutDtos.ApplicationReceiptRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.receiptId() == null
                || request.authTenantId() == null
                || request.authTenantId() <= 0) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A receipt ID and positive auth tenant ID are required.");
        }
        validateState(request.observationState(), request.errorCode());
        if (!ProductSurfaceRolloutFlagCatalog.contains(request.flagKey())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Only governed product-surface rollout flags accept application receipts.");
        }
        FeatureRolloutRepository.TenantRow tenant = rolloutRepository
                .tenantByAuthTenantId(request.authTenantId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        FeatureRolloutRepository.FlagRow flag = rolloutRepository.flag(request.flagKey())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        long observedRevision = revision(request.opaqueRevision());
        FeatureRolloutDecisionOutboxRepository.RevisionSnapshot published =
                decisionRepository.revisionSnapshot(request.flagKey());
        if (observedRevision > published.opaqueRevision()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "An application receipt cannot acknowledge a future rollout revision.");
        }

        Instant acceptedAt = clock.instant();
        String errorCode = normalize(request.errorCode());
        boolean inserted = receiptRepository.append(
                request.receiptId(), tenant.tenantId(), flag.flagId(), GATEWAY_TARGET,
                observedRevision, request.observationState(), errorCode, acceptedAt);
        UUID canonicalReceiptId = request.receiptId();
        if (!inserted) {
            FeatureRolloutApplicationReceiptRepository.ReceiptRow existing = receiptRepository
                    .receipt(request.receiptId())
                    .or(() -> receiptRepository.receipt(
                            tenant.tenantId(), flag.flagId(), GATEWAY_TARGET,
                            observedRevision, request.observationState(), errorCode))
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                            "The application receipt projection is temporarily inconsistent."));
            if (!sameReceipt(
                    existing, tenant.tenantId(), flag.flagId(), observedRevision,
                    request.observationState(), errorCode)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The receipt ID is already bound to different application evidence.");
            }
            canonicalReceiptId = existing.receiptId();
            receiptRepository.refreshProjection(existing, acceptedAt);
        }
        return new FeatureRolloutDtos.ApplicationReceipt(
                canonicalReceiptId, GATEWAY_TARGET, request.flagKey(),
                request.opaqueRevision(), request.observationState(), inserted, acceptedAt);
    }

    @Transactional(readOnly = true)
    public ApplicationSnapshot snapshot(String featureKey, UUID providerTenantId) {
        if (!ProductSurfaceRolloutFlagCatalog.contains(featureKey)) {
            return ApplicationSnapshot.unsupported();
        }
        FeatureRolloutRepository.FlagRow flag = rolloutRepository.flag(featureKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        FeatureRolloutDecisionOutboxRepository.RevisionSnapshot revision =
                decisionRepository.revisionSnapshot(featureKey);
        List<TargetReceipt> receipts = receiptRepository
                .current(providerTenantId, flag.flagId()).stream()
                .map(row -> new TargetReceipt(
                        row.targetId(), row.observationState(),
                        FeatureRolloutInternalEvaluationService.opaque(
                                row.observedRevision()),
                        row.observedAt(), row.lastSuccessAt(), row.errorCode()))
                .toList();
        return new ApplicationSnapshot(
                true,
                FeatureRolloutInternalEvaluationService.opaque(revision.opaqueRevision()),
                revision.publishedAt(),
                1,
                receipts);
    }

    private boolean sameReceipt(
            FeatureRolloutApplicationReceiptRepository.ReceiptRow existing,
            UUID tenantId,
            UUID flagId,
            long revision,
            String state,
            String errorCode) {
        return existing.providerTenantId().equals(tenantId)
                && existing.featureFlagId().equals(flagId)
                && existing.targetId().equals(GATEWAY_TARGET)
                && existing.observedRevision() == revision
                && existing.observationState().equals(state)
                && Objects.equals(existing.errorCode(), errorCode);
    }

    private void validateState(String state, String errorCode) {
        String normalizedError = normalize(errorCode);
        if (normalizedError != null
                && !normalizedError.matches("^[A-Z][A-Z0-9_]{2,79}$")) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Application receipt error codes must use the bounded canonical format.");
        }
        if ("APPLIED".equals(state) && normalizedError == null) {
            return;
        }
        if ("FAILED".equals(state) && normalizedError != null) {
            return;
        }
        throw new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "Applied receipts cannot include an error and failed receipts require one.");
    }

    private long revision(String opaqueRevision) {
        if (opaqueRevision == null || !opaqueRevision.matches("^rev-[0-9]{20}$")) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A canonical opaque rollout revision is required.");
        }
        try {
            return Long.parseLong(opaqueRevision.substring(4));
        } catch (NumberFormatException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The opaque rollout revision is outside the supported range.",
                    exception);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ApplicationSnapshot(
            boolean observationSupported,
            String publishedVersion,
            Instant publishAcceptedAt,
            int expectedTargetCount,
            List<TargetReceipt> receipts) {

        public ApplicationSnapshot {
            receipts = List.copyOf(receipts);
            if (observationSupported
                    && (publishedVersion == null || publishedVersion.isBlank()
                    || expectedTargetCount < 1)) {
                throw new IllegalArgumentException(
                        "Supported application observation requires a published version and target.");
            }
            if (!observationSupported
                    && (publishedVersion != null || publishAcceptedAt != null
                    || expectedTargetCount != 0 || !receipts.isEmpty())) {
                throw new IllegalArgumentException(
                        "Unsupported application observation cannot expose evidence.");
            }
            if (receipts.size() > expectedTargetCount
                    || receipts.stream().map(TargetReceipt::targetId).distinct().count()
                    != receipts.size()) {
                throw new IllegalArgumentException(
                        "Application receipt targets must be unique and expected.");
            }
        }

        static ApplicationSnapshot unsupported() {
            return new ApplicationSnapshot(false, null, null, 0, List.of());
        }
    }

    public record TargetReceipt(
            String targetId,
            String observationState,
            String observedVersion,
            Instant observedAt,
            Instant lastSuccessAt,
            String errorCode) {
    }
}
