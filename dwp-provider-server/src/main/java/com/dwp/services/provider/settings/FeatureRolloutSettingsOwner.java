package com.dwp.services.provider.settings;

import com.dwp.services.provider.rollout.FeatureRolloutDtos;
import com.dwp.services.provider.rollout.FeatureRolloutService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class FeatureRolloutSettingsOwner implements SettingsOwnerAdapter {

    static final String AUTHORITY = "provider-feature-rollout";
    static final String READ_PERMISSION = "FEATURE_ROLLOUT_READ";

    private final FeatureRolloutService rolloutService;

    public FeatureRolloutSettingsOwner(FeatureRolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    @Override
    public String authority() {
        return AUTHORITY;
    }

    @Override
    public String readPermission() {
        return READ_PERMISSION;
    }

    @Override
    public List<SettingsContracts.Definition> definitions() {
        return rolloutService.flags().stream().map(this::definition).toList();
    }

    @Override
    public SettingsContracts.ScopeTarget canonicalTarget(
            SettingsContracts.ScopeTarget target) {
        // The current feature-rollout owner has one tenant-wide value and no
        // environment dimension. Never echo a caller-provided environment as
        // though a staging/production-specific value had been resolved.
        return new SettingsContracts.ScopeTarget(
                target.scopeType(), target.scopeId(), null);
    }

    @Override
    public SettingsContracts.OwnerSnapshot resolve(
            SettingsContracts.Definition definition,
            SettingsContracts.ScopeTarget target) {
        UUID tenantId;
        try {
            tenantId = UUID.fromString(target.scopeId());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Feature rollout tenant scope ID must be a UUID.", exception);
        }
        FeatureRolloutDtos.Evaluation evaluation = rolloutService.resolveEffectiveValue(
                definition.settingId(), tenantId);
        String version = evaluation.rolloutRevisionId() == null
                ? "definition:" + definition.definitionVersion()
                : "rollout:" + evaluation.rolloutRevisionId();
        SettingsContracts.SourceType sourceType = evaluation.rolloutRevisionId() == null
                ? SettingsContracts.SourceType.DEFAULT
                : SettingsContracts.SourceType.ACTIVE_ROLLOUT;
        SettingsContracts.ScopeType sourceScope = evaluation.rolloutRevisionId() == null
                ? SettingsContracts.ScopeType.PROVIDER
                : SettingsContracts.ScopeType.TENANT;
        String sourceId = evaluation.rolloutRevisionId() == null
                ? definition.settingId()
                : evaluation.rolloutRevisionId().toString();
        Instant resolvedAt = evaluation.evaluatedAt();
        return new SettingsContracts.OwnerSnapshot(
                evaluation.value(),
                version,
                List.of(new SettingsContracts.Provenance(
                        0, sourceType, sourceScope, sourceId, version,
                        false, evaluation.reasonCode(), resolvedAt)),
                new SettingsContracts.ApplicationEvidence(
                        version, SettingsContracts.DesiredState.PUBLISHED,
                        version, null, false, 0, List.of()),
                resolvedAt);
    }

    private SettingsContracts.Definition definition(FeatureRolloutDtos.FeatureFlag flag) {
        return new SettingsContracts.Definition(
                flag.featureKey(),
                flag.displayName(),
                flag.description(),
                new SettingsContracts.Owner(
                        flag.ownerService(),
                        "FEATURE_ROLLOUT",
                        READ_PERMISSION,
                        "/provider/feature-rollouts"),
                Set.of(SettingsContracts.ScopeType.TENANT),
                new SettingsContracts.ValidationContract(
                        valueType(flag.valueType()),
                        "feature-flag-schema-v1",
                        flag.configurationSchema(),
                        true),
                SettingsContracts.Sensitivity.INTERNAL,
                new SettingsContracts.ChangeContract(
                        flag.riskTier(),
                        SettingsContracts.ChangeWorkflow.APPROVE_AND_ACTIVATE,
                        true,
                        true),
                lifecycle(flag.lifecycleState()),
                flag.version());
    }

    private SettingsContracts.ValueType valueType(String valueType) {
        return switch (valueType) {
            case "BOOLEAN" -> SettingsContracts.ValueType.BOOLEAN;
            case "STRING" -> SettingsContracts.ValueType.STRING;
            case "NUMBER" -> SettingsContracts.ValueType.NUMBER;
            case "JSON" -> SettingsContracts.ValueType.JSON;
            default -> throw new IllegalStateException(
                    "Unsupported feature flag value type: " + valueType);
        };
    }

    private SettingsContracts.LifecycleState lifecycle(String state) {
        return switch (state) {
            case "ACTIVE" -> SettingsContracts.LifecycleState.ACTIVE;
            case "DEPRECATED", "RETIRED" -> SettingsContracts.LifecycleState.DEPRECATED;
            default -> SettingsContracts.LifecycleState.UNAVAILABLE;
        };
    }
}
