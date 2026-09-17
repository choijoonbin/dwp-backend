package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentCanaryModels.*;
import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;

@Service
class ApprovalDeploymentCanaryService {
    private static final Duration HEALTHY_RESUME_WINDOW = Duration.ofMinutes(10);

    private final ApprovalDeploymentRepository deployments;
    private final ApprovalDeploymentCanaryRepository canaries;
    private final ApprovalDeploymentAttestationVerifier verifier;
    private final ApprovalDeploymentCanonical canonical;
    private final Clock clock;

    ApprovalDeploymentCanaryService(
            ApprovalDeploymentRepository deployments,
            ApprovalDeploymentCanaryRepository canaries,
            ApprovalDeploymentAttestationVerifier verifier,
            ObjectMapper mapper,
            Clock clock) {
        this.deployments = deployments;
        this.canaries = canaries;
        this.verifier = verifier;
        this.canonical = new ApprovalDeploymentCanonical(mapper);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    CanaryView canary(Scope scope, UUID promotionId) {
        require(scope, Capability.VIEW);
        Promotion promotion = requiredPromotion(scope, promotionId, false);
        List<TelemetryObservation> telemetry = canaries.telemetry(scope, promotionId, 50);
        return new CanaryView(promotion, canaries.control(scope, promotionId),
                telemetry.isEmpty() ? null : telemetry.getFirst(), List.copyOf(telemetry));
    }

    @Transactional
    CanaryView control(
            Scope scope,
            UUID promotionId,
            ControlCommand command,
            GovernedCommand governed) {
        require(scope, Capability.ACTIVATE);
        requireGoverned(scope, governed);
        validate(command);
        Promotion promotion = requiredPromotion(scope, promotionId, true);
        if (promotion.version() != command.expectedPromotionVersion()) {
            throw versionConflict("Promotion version changed before canary control.");
        }
        CanaryControl current = canaries.control(scope, promotionId);
        if (current.version() != command.expectedControlVersion()
                || current.state() == command.state()) {
            throw versionConflict("Canary control version or requested state is stale.");
        }
        if (command.state() == CanaryState.RUNNING) {
            List<TelemetryObservation> telemetry = canaries.telemetry(scope, promotionId, 1);
            if (telemetry.isEmpty()
                    || telemetry.getFirst().outcome() != HealthOutcome.HEALTHY
                    || telemetry.getFirst().sourceGeneratedAt()
                    .isBefore(clock.instant().minus(HEALTHY_RESUME_WINDOW))) {
                throw conflict(
                        "Canary resume requires fresh, externally verified healthy telemetry.");
            }
        }
        CanaryControl changed = canaries.setControl(scope, promotionId, command, clock.instant());
        deployments.journal(scope, promotionId,
                command.state() == CanaryState.PAUSED
                        ? "CANARY_PAUSED" : "CANARY_RESUMED",
                scope.actorUserId(), canonical.json(Map.of(
                        "reason", command.reason().strip(),
                        "controlVersion", changed.version(),
                        "promotionVersion", command.expectedPromotionVersion() + 1)),
                clock.instant());
        return canary(scope, promotionId);
    }

    @Transactional
    CanaryView recordTelemetry(
            Scope scope,
            UUID promotionId,
            TelemetryCommand command,
            GovernedCommand governed) {
        require(scope, Capability.RECORD_EXTERNAL_EVIDENCE);
        requireGoverned(scope, governed);
        validate(command);
        Promotion promotion = requiredPromotion(scope, promotionId, true);
        if (promotion.version() != command.expectedPromotionVersion()
                || promotion.activationStartedAt() == null) {
            throw versionConflict("Promotion is no longer at the exact canary version.");
        }
        ExternalHealthEvidence evidence = verifier.verify(
                scope, promotionId, command.expectedPromotionVersion(), command.evidence());
        if (!"CANARY_HEALTH".equals(evidence.evidenceType())
                || !command.telemetryId().equals(evidence.evidenceId())
                || evidence.sourceGeneratedAt().isBefore(promotion.activationStartedAt())) {
            throw conflict("Telemetry evidence is not bound to this canary attempt.");
        }
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("stableWeight", command.stableWeight());
        material.put("canaryWeight", command.canaryWeight());
        material.put("metrics", command.metrics());
        String payloadSha256 = canonical.sha256(material);
        if (!payloadSha256.equals(evidence.payloadSha256())) {
            throw forbidden("Signed telemetry digest does not match the submitted metrics.");
        }
        TelemetryObservation recorded = canaries.recordTelemetry(
                scope, promotionId, command, evidence, payloadSha256, clock.instant());
        CanaryControl control = canaries.control(scope, promotionId);
        deployments.journal(scope, promotionId, "CANARY_TELEMETRY_RECORDED",
                scope.actorUserId(), canonical.json(Map.of(
                        "telemetryId", recorded.telemetryId(),
                        "outcome", recorded.outcome(),
                        "stableWeight", recorded.stableWeight(),
                        "canaryWeight", recorded.canaryWeight(),
                        "payloadSha256", recorded.payloadSha256(),
                        "verificationReference", recorded.verificationReference(),
                        "controlState", control.state())), clock.instant());
        return canary(scope, promotionId);
    }

    @Transactional(readOnly = true)
    LedgerView ledger(Scope scope, UUID promotionId) {
        require(scope, Capability.VIEW);
        requiredPromotion(scope, promotionId, false);
        List<LedgerEntry> entries = new ArrayList<>();
        String previous = "0".repeat(64);
        long sequence = 0;
        for (JournalEvent event : canaries.journal(scope, promotionId)) {
            LinkedHashMap<String, Object> material = new LinkedHashMap<>();
            material.put("previousSha256", previous);
            material.put("journalId", event.journalId());
            material.put("eventType", event.eventType());
            material.put("actorUserId", event.actorUserId());
            material.put("payload", event.payload());
            material.put("occurredAt", event.occurredAt());
            String current = canonical.sha256(material);
            entries.add(new LedgerEntry(++sequence, event.journalId(), event.eventType(),
                    event.actorUserId(), event.payload(), event.occurredAt(), previous, current));
            previous = current;
        }
        return new LedgerView(promotionId, "INTERNAL_APPEND_ONLY_HASH_CHAIN",
                previous, List.copyOf(entries), clock.instant());
    }

    private Promotion requiredPromotion(Scope scope, UUID promotionId, boolean lock) {
        Promotion promotion = deployments.promotion(scope, promotionId, lock);
        if (promotion == null) throw new BaseException(ErrorCode.NOT_FOUND);
        return promotion;
    }

    private void validate(ControlCommand command) {
        if (command == null || command.state() == null
                || command.expectedPromotionVersion() < 0
                || command.expectedControlVersion() < 0
                || command.reason() == null || !command.reason().equals(command.reason().strip())
                || command.reason().length() < 10 || command.reason().length() > 1_000) {
            throw invalid("Canary control command is invalid.");
        }
    }

    private void validate(TelemetryCommand command) {
        if (command == null || command.telemetryId() == null
                || command.expectedPromotionVersion() < 0 || command.evidence() == null
                || command.stableWeight() < 0 || command.canaryWeight() < 0
                || command.stableWeight() + command.canaryWeight() != 100
                || command.metrics().isEmpty() || command.metrics().size() > 64) {
            throw invalid("Canary telemetry command is invalid.");
        }
        validateMetricValue(command.metrics(), 0);
    }

    private void validateMetricValue(Object value, int depth) {
        if (depth > 5) throw invalid("Canary telemetry is too deeply nested.");
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 64) throw invalid("Canary telemetry is too large.");
            map.forEach((key, nested) -> {
                String name = String.valueOf(key);
                String lower = name.toLowerCase(Locale.ROOT);
                if (!name.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")
                        || lower.contains("secret") || lower.contains("token")
                        || lower.contains("password") || lower.contains("credential")
                        || lower.contains("payload") || lower.contains("signature")) {
                    throw invalid("Canary telemetry contains a prohibited metric key.");
                }
                validateMetricValue(nested, depth + 1);
            });
            return;
        }
        if (value instanceof List<?> list) {
            if (list.size() > 128) throw invalid("Canary telemetry is too large.");
            list.forEach(item -> validateMetricValue(item, depth + 1));
            return;
        }
        if (value == null || value instanceof Boolean || value instanceof Number) return;
        if (value instanceof String text && text.length() <= 500) return;
        throw invalid("Canary telemetry contains an unsupported metric value.");
    }

    private void requireGoverned(Scope scope, GovernedCommand command) {
        Instant now = clock.instant();
        if (command == null || command.actorUserId() != scope.actorUserId()
                || command.stepUpEvidenceReference() == null
                || !command.stepUpEvidenceReference().startsWith("verified:")
                || command.stepUpVerifiedAt() == null || command.stepUpValidUntil() == null
                || command.stepUpVerifiedAt().isAfter(now.plusSeconds(30))
                || !command.stepUpValidUntil().isAfter(now)) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Fresh command-bound deployment step-up evidence is required.");
        }
    }

    private void require(Scope scope, Capability capability) {
        if (!scope.has(capability)) throw forbidden("Deployment capability is unavailable.");
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }
}
