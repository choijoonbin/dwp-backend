package com.dwp.services.provider.rollout;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FeatureRolloutService {

    private static final String READ = "FEATURE_ROLLOUT_READ";
    private static final String WRITE = "FEATURE_ROLLOUT_WRITE";
    private static final String APPROVE = "FEATURE_ROLLOUT_APPROVE";
    private static final Set<String> SECRET_FIELD_MARKERS =
            Set.of("secret", "password", "token", "credential", "privatekey");

    private final FeatureRolloutRepository repository;
    private final ProviderAuditService audit;

    public FeatureRolloutService(
            FeatureRolloutRepository repository,
            ProviderAuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<FeatureRolloutDtos.FeatureFlag> flags() {
        ProviderRequestContext.requirePermission(READ);
        return repository.flags().stream().map(this::flag).toList();
    }

    @Transactional
    public FeatureRolloutDtos.FeatureFlag createFlag(
            FeatureRolloutDtos.CreateFeatureFlagRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        validateValue(request.valueType(), request.defaultValue());
        if (!request.configurationSchema().isObject()) {
            throw invalid("The typed configuration schema must be a JSON object.");
        }
        requireSecretReferences(request.defaultValue(), "defaultValue");
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        FeatureRolloutRepository.FlagRow created;
        try {
            created = repository.createFlag(UUID.randomUUID(), request, actor.operatorId());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The feature key already exists or violates the registry contract.",
                    exception);
        }
        audit.success(
                "provider.feature-flag.created",
                "FEATURE_FLAG",
                created.flagId().toString(),
                correlationId,
                Map.of(
                        "featureKey", created.featureKey(),
                        "ownerService", created.ownerService(),
                        "valueType", created.valueType(),
                        "riskTier", created.riskTier(),
                        "defaultValueHash", hash(created.defaultValue().toString())));
        return flag(created);
    }

    @Transactional(readOnly = true)
    public List<FeatureRolloutDtos.Rollout> rollouts(String featureKey) {
        ProviderRequestContext.requirePermission(READ);
        return repository.rollouts(featureKey).stream().map(this::rollout).toList();
    }

    @Transactional(readOnly = true)
    public FeatureRolloutDtos.Rollout rollout(UUID rolloutId) {
        ProviderRequestContext.requirePermission(READ);
        return rollout(requireRollout(rolloutId));
    }

    @Transactional
    public FeatureRolloutDtos.Rollout createRollout(
            String featureKey,
            FeatureRolloutDtos.CreateRolloutRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        FeatureRolloutRepository.FlagRow flag = repository.lockFlag(featureKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!"ACTIVE".equals(flag.lifecycleState())) {
            throw invalidState("Only active feature flags accept rollout revisions.");
        }
        validateValue(flag.valueType(), request.rolloutValue());
        requireSecretReferences(request.rolloutValue(), "rolloutValue");
        validateTargeting(request.targeting());
        validateStages(request.strategy(), request.stages());
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        FeatureRolloutRepository.RolloutRow created = repository.createRollout(
                flag, UUID.randomUUID(), request, actor.operatorId());
        audit.success(
                "provider.feature-rollout.drafted",
                "FEATURE_ROLLOUT",
                created.rolloutId().toString(),
                correlationId,
                snapshot(created));
        return rollout(created);
    }

    @Transactional
    public FeatureRolloutDtos.Rollout submit(
            UUID rolloutId,
            FeatureRolloutDtos.VersionedReasonRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        FeatureRolloutRepository.RolloutRow before = requireRollout(rolloutId);
        if (!repository.submit(
                rolloutId, request.version(), ProviderRequestContext.require().operatorId())) {
            throw conflict("The draft changed or is no longer submittable.");
        }
        FeatureRolloutRepository.RolloutRow result = requireRollout(rolloutId);
        audit.success(
                "provider.feature-rollout.submitted",
                "FEATURE_ROLLOUT",
                rolloutId.toString(),
                correlationId,
                Map.of("before", snapshot(before), "after", snapshot(result),
                        "reason", request.reason()));
        return rollout(result);
    }

    @Transactional
    public FeatureRolloutDtos.Rollout decide(
            UUID rolloutId,
            FeatureRolloutDtos.ApprovalDecisionRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(APPROVE);
        FeatureRolloutRepository.RolloutRow before = requireRollout(rolloutId);
        Long actorId = ProviderRequestContext.require().operatorId();
        if (actorId.equals(before.requestedBy())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "A rollout requester cannot approve or reject the same revision.");
        }
        if (!repository.decide(
                rolloutId, request.version(), request.decision(), request.reason(), actorId)) {
            throw conflict("The approval changed or cannot be decided by this operator.");
        }
        FeatureRolloutRepository.RolloutRow result = requireRollout(rolloutId);
        audit.success(
                "provider.feature-rollout." + request.decision().toLowerCase(Locale.ROOT),
                "FEATURE_ROLLOUT",
                rolloutId.toString(),
                correlationId,
                Map.of("before", snapshot(before), "after", snapshot(result),
                        "reason", request.reason()));
        return rollout(result);
    }

    @Transactional
    public FeatureRolloutDtos.Rollout activate(
            UUID rolloutId,
            FeatureRolloutDtos.VersionedReasonRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        FeatureRolloutRepository.RolloutRow before = requireRollout(rolloutId);
        if (!repository.activate(rolloutId, request.version())) {
            throw conflict("The approved rollout changed or cannot be activated.");
        }
        FeatureRolloutRepository.RolloutRow result = requireRollout(rolloutId);
        audit.success(
                "provider.feature-rollout.activated",
                "FEATURE_ROLLOUT",
                rolloutId.toString(),
                correlationId,
                Map.of("before", snapshot(before), "after", snapshot(result),
                        "reason", request.reason(), "externalExecutionEnabled", false));
        return rollout(result);
    }

    @Transactional
    public FeatureRolloutDtos.Rollout pause(
            UUID rolloutId,
            FeatureRolloutDtos.VersionedReasonRequest request,
            String correlationId) {
        return changeRunningState(
                rolloutId, request, correlationId, true);
    }

    @Transactional
    public FeatureRolloutDtos.Rollout resume(
            UUID rolloutId,
            FeatureRolloutDtos.VersionedReasonRequest request,
            String correlationId) {
        return changeRunningState(
                rolloutId, request, correlationId, false);
    }

    @Transactional
    public FeatureRolloutDtos.Rollout advance(
            UUID rolloutId,
            FeatureRolloutDtos.AdvanceRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        FeatureRolloutRepository.RolloutRow before = requireRollout(rolloutId);
        if (!"ACTIVE".equals(before.lifecycleState()) || before.currentStageOrder() == null) {
            throw invalidState("Only an active rollout stage can advance.");
        }
        List<FeatureRolloutRepository.StageRow> stages = repository.stages(rolloutId);
        FeatureRolloutRepository.StageRow current = stages.stream()
                .filter(stage -> stage.stageOrder() == before.currentStageOrder())
                .findFirst()
                .orElseThrow(() -> invalidState("The active rollout stage is missing."));
        if (current.startedAt() == null
                || Duration.between(current.startedAt(), Instant.now()).toMinutes()
                < current.minimumMinutes()) {
            throw invalidState("The minimum observation window has not elapsed.");
        }
        validateHealthGate(current.healthGate(), request.observedHealth());
        Integer nextOrder = stages.stream()
                .map(FeatureRolloutRepository.StageRow::stageOrder)
                .filter(order -> order > current.stageOrder())
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (!repository.advance(
                rolloutId, request.version(), current.stageOrder(), nextOrder)) {
            throw conflict("The active rollout stage changed before it could advance.");
        }
        FeatureRolloutRepository.RolloutRow result = requireRollout(rolloutId);
        audit.success(
                "provider.feature-rollout.advanced",
                "FEATURE_ROLLOUT",
                rolloutId.toString(),
                correlationId,
                Map.of(
                        "before", snapshot(before),
                        "after", snapshot(result),
                        "reason", request.reason(),
                        "observedHealthHash", hash(request.observedHealth().toString())));
        return rollout(result);
    }

    @Transactional
    public FeatureRolloutDtos.Rollout rollback(
            UUID rolloutId,
            FeatureRolloutDtos.VersionedReasonRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        ProviderRequestContext.requirePermission(APPROVE);
        FeatureRolloutRepository.RolloutRow before = requireRollout(rolloutId);
        FeatureRolloutRepository.FlagRow flag = repository.lockFlag(before.featureKey())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        JsonNode rollbackValue = before.previousRevisionId() == null
                ? flag.defaultValue()
                : repository.rollout(before.previousRevisionId())
                        .map(FeatureRolloutRepository.RolloutRow::value)
                        .orElse(flag.defaultValue());
        if (!repository.markRolledBack(rolloutId, request.version())) {
            throw conflict("The rollout changed or is no longer rollback eligible.");
        }
        FeatureRolloutRepository.RolloutRow rollback = repository.createRollback(
                flag,
                before,
                rollbackValue,
                request.reason().trim(),
                ProviderRequestContext.require().operatorId());
        audit.success(
                "provider.feature-rollout.rolled-back",
                "FEATURE_ROLLOUT",
                rolloutId.toString(),
                correlationId,
                Map.of(
                        "rolledBackRevision", snapshot(before),
                        "restoredRevision", snapshot(rollback),
                        "reason", request.reason()));
        return rollout(rollback);
    }

    @Transactional
    public FeatureRolloutDtos.Evaluation evaluate(String featureKey, UUID tenantId) {
        ProviderRequestContext.requirePermission(READ);
        FeatureRolloutRepository.FlagRow flag = repository.flag(featureKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        FeatureRolloutRepository.TenantRow tenant = repository.tenant(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        List<FeatureRolloutRepository.RolloutRow> candidates =
                repository.effectiveRollouts(flag.flagId());
        FeatureRolloutRepository.RolloutRow selected = null;
        BigDecimal exposure = BigDecimal.ZERO;
        int bucket = deterministicBucket(featureKey, tenantId);
        String reason = candidates.isEmpty() ? "DEFAULT" : "TARGET_MISS";
        JsonNode value = flag.defaultValue();
        for (FeatureRolloutRepository.RolloutRow candidate : candidates) {
            if (!matches(candidate.targeting(), tenant)) continue;
            selected = candidate;
            exposure = exposure(candidate);
            if (BigDecimal.valueOf(bucket)
                    .compareTo(exposure.multiply(BigDecimal.valueOf(100))) >= 0) {
                reason = "PERCENTAGE_EXCLUDED";
                selected = null;
            } else {
                reason = "ROLLOUT_MATCH";
                value = candidate.value();
            }
            break;
        }
        String variantHash = hash(value.toString());
        repository.recordEvaluation(
                flag.flagId(),
                selected == null ? null : selected.rolloutId(),
                tenantId,
                reason,
                exposure,
                variantHash);
        return new FeatureRolloutDtos.Evaluation(
                featureKey,
                tenantId,
                tenant.tenantKey(),
                value,
                reason,
                selected == null ? null : selected.rolloutId(),
                selected == null ? null : selected.revisionNumber(),
                exposure,
                bucket,
                false,
                Instant.now());
    }

    static int deterministicBucket(String featureKey, UUID tenantId) {
        byte[] digest = digest(featureKey + ":" + tenantId);
        long value = ((long) (digest[0] & 0xff) << 24)
                | ((long) (digest[1] & 0xff) << 16)
                | ((long) (digest[2] & 0xff) << 8)
                | (digest[3] & 0xffL);
        return (int) (value % 10_000L);
    }

    private FeatureRolloutDtos.Rollout changeRunningState(
            UUID rolloutId,
            FeatureRolloutDtos.VersionedReasonRequest request,
            String correlationId,
            boolean pause) {
        ProviderRequestContext.requirePermission(WRITE);
        FeatureRolloutRepository.RolloutRow before = requireRollout(rolloutId);
        boolean changed = pause
                ? repository.pause(rolloutId, request.version())
                : repository.resume(rolloutId, request.version());
        if (!changed) {
            throw conflict("The rollout changed or cannot transition to the requested state.");
        }
        FeatureRolloutRepository.RolloutRow result = requireRollout(rolloutId);
        String action = pause ? "paused" : "resumed";
        audit.success(
                "provider.feature-rollout." + action,
                "FEATURE_ROLLOUT",
                rolloutId.toString(),
                correlationId,
                Map.of("before", snapshot(before), "after", snapshot(result),
                        "reason", request.reason()));
        return rollout(result);
    }

    private void validateValue(String valueType, JsonNode value) {
        boolean valid = switch (valueType) {
            case "BOOLEAN" -> value.isBoolean();
            case "STRING" -> value.isTextual();
            case "NUMBER" -> value.isNumber();
            case "JSON" -> value.isObject() || value.isArray();
            default -> false;
        };
        if (!valid) throw invalid("The feature value does not match its registered type.");
    }

    private void validateTargeting(JsonNode targeting) {
        if (!targeting.isObject()) {
            throw invalid("Rollout targeting must be a JSON object.");
        }
        Set<String> allowed = Set.of(
                "tenantIds", "tenantKeys", "regions", "serviceTiers", "isolationModels");
        List<String> unsupported = new ArrayList<>();
        targeting.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) unsupported.add(name);
            JsonNode values = targeting.get(name);
            if (!values.isArray() || !values.elements().hasNext()) unsupported.add(name);
            if (values.isArray()) {
                values.forEach(value -> {
                    if (!value.isTextual() || value.textValue().isBlank()) unsupported.add(name);
                });
            }
        });
        if (!unsupported.isEmpty()) {
            throw invalid("Rollout targeting contains unsupported or empty criteria: "
                    + unsupported.stream().distinct().sorted().toList());
        }
    }

    private void validateStages(
            String strategy,
            List<FeatureRolloutDtos.StageRequest> stages) {
        BigDecimal previous = BigDecimal.ZERO;
        for (FeatureRolloutDtos.StageRequest stage : stages) {
            if (!stage.healthGate().isObject()) {
                throw invalid("Every rollout health gate must be a JSON object.");
            }
            if (stage.exposurePercentage().compareTo(previous) <= 0) {
                throw invalid("Rollout exposure percentages must increase by stage.");
            }
            previous = stage.exposurePercentage();
        }
        if (previous.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw invalid("The final rollout stage must reach 100 percent.");
        }
        if ("ALL_AT_ONCE".equals(strategy)
                && (stages.size() != 1
                || stages.get(0).exposurePercentage().compareTo(BigDecimal.valueOf(100)) != 0)) {
            throw invalid("All-at-once rollouts require one 100 percent stage.");
        }
    }

    private void validateHealthGate(JsonNode configured, JsonNode observed) {
        if (!configured.isObject() || configured.isEmpty()) return;
        if (!observed.isObject()) {
            throw invalidState("Observed health evidence is required for this stage.");
        }
        compareMaximum(configured, observed, "maxErrorRate");
        compareMaximum(configured, observed, "maxP95LatencyMs");
        compareMinimum(configured, observed, "minSuccessRate");
    }

    private void compareMaximum(JsonNode configured, JsonNode observed, String field) {
        if (!configured.has(field)) return;
        requireNumeric(configured, field);
        requireNumeric(observed, field);
        if (observed.get(field).decimalValue().compareTo(configured.get(field).decimalValue()) > 0) {
            throw invalidState("The rollout health gate failed: " + field + ".");
        }
    }

    private void compareMinimum(JsonNode configured, JsonNode observed, String field) {
        if (!configured.has(field)) return;
        requireNumeric(configured, field);
        requireNumeric(observed, field);
        if (observed.get(field).decimalValue().compareTo(configured.get(field).decimalValue()) < 0) {
            throw invalidState("The rollout health gate failed: " + field + ".");
        }
    }

    private void requireNumeric(JsonNode source, String field) {
        if (!source.has(field) || !source.get(field).isNumber()) {
            throw invalidState("Numeric rollout health evidence is required: " + field + ".");
        }
    }

    private void requireSecretReferences(JsonNode node, String path) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String normalized = entry.getKey().replace("_", "")
                        .replace("-", "").toLowerCase(Locale.ROOT);
                boolean secretField = SECRET_FIELD_MARKERS.stream().anyMatch(normalized::contains);
                JsonNode value = entry.getValue();
                if (secretField && (!value.isTextual()
                        || !value.textValue().startsWith("secret://"))) {
                    throw invalid("Secret-bearing feature values must use secret:// references at "
                            + path + "." + entry.getKey() + ".");
                }
                requireSecretReferences(value, path + "." + entry.getKey());
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                requireSecretReferences(node.get(index), path + "[" + index + "]");
            }
        } else if (node.isTextual() && node.textValue().contains("-----BEGIN")) {
            throw invalid("Inline private key material is not permitted in feature configuration.");
        }
    }

    private boolean matches(
            JsonNode targeting,
            FeatureRolloutRepository.TenantRow tenant) {
        return matches(targeting, "tenantIds", tenant.tenantId().toString())
                && matches(targeting, "tenantKeys", tenant.tenantKey())
                && matches(targeting, "regions", tenant.region())
                && matches(targeting, "serviceTiers", tenant.serviceTier())
                && matches(targeting, "isolationModels", tenant.isolationModel());
    }

    private boolean matches(JsonNode targeting, String field, String actual) {
        JsonNode values = targeting.get(field);
        if (values == null) return true;
        for (JsonNode value : values) {
            if (value.asText().equals(actual)) return true;
        }
        return false;
    }

    private BigDecimal exposure(FeatureRolloutRepository.RolloutRow rollout) {
        List<FeatureRolloutRepository.StageRow> stages = repository.stages(rollout.rolloutId());
        if (stages.isEmpty()) return BigDecimal.ZERO;
        if (rollout.currentStageOrder() == null) {
            return stages.get(stages.size() - 1).percentage();
        }
        return stages.stream()
                .filter(stage -> stage.stageOrder() == rollout.currentStageOrder())
                .map(FeatureRolloutRepository.StageRow::percentage)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private FeatureRolloutRepository.RolloutRow requireRollout(UUID rolloutId) {
        return repository.rollout(rolloutId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private FeatureRolloutDtos.FeatureFlag flag(FeatureRolloutRepository.FlagRow row) {
        return new FeatureRolloutDtos.FeatureFlag(
                row.flagId(), row.featureKey(), row.displayName(), row.description(),
                row.ownerService(), row.valueType(), row.defaultValue(), row.schema(),
                row.riskTier(), row.lifecycleState(), row.version());
    }

    private FeatureRolloutDtos.Rollout rollout(FeatureRolloutRepository.RolloutRow row) {
        List<FeatureRolloutDtos.Stage> stages = repository.stages(row.rolloutId()).stream()
                .map(stage -> new FeatureRolloutDtos.Stage(
                        stage.stageId(), stage.stageOrder(), stage.stageName(),
                        stage.percentage(), stage.minimumMinutes(), stage.healthGate(),
                        stage.lifecycleState(), stage.startedAt(), stage.completedAt()))
                .toList();
        FeatureRolloutDtos.Approval approval = repository.approval(row.rolloutId())
                .map(item -> new FeatureRolloutDtos.Approval(
                        item.approvalId(), item.lifecycleState(), item.requestedBy(),
                        item.requestedAt(), item.decidedBy(), item.decidedAt(), item.reason()))
                .orElse(null);
        return new FeatureRolloutDtos.Rollout(
                row.rolloutId(), row.flagId(), row.featureKey(), row.revisionNumber(),
                row.name(), row.lifecycleState(), row.value(), row.targeting(), row.strategy(),
                row.currentStageOrder(), row.previousRevisionId(), row.rollbackOfRevisionId(),
                row.justification(), row.requestedBy(), row.approvedBy(), row.submittedAt(),
                row.approvedAt(), row.activatedAt(), row.completedAt(), row.pausedAt(),
                row.version(), stages, approval, false);
    }

    private Map<String, Object> snapshot(FeatureRolloutRepository.RolloutRow row) {
        return Map.ofEntries(
                Map.entry("featureKey", row.featureKey()),
                Map.entry("revisionNumber", row.revisionNumber()),
                Map.entry("lifecycleState", row.lifecycleState()),
                Map.entry("strategy", row.strategy()),
                Map.entry("currentStageOrder", row.currentStageOrder() == null
                        ? "" : row.currentStageOrder()),
                Map.entry("targetingHash", hash(row.targeting().toString())),
                Map.entry("valueHash", hash(row.value().toString())),
                Map.entry("version", row.version()));
    }

    private String hash(String value) {
        return HexFormat.of().formatHex(digest(value));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
