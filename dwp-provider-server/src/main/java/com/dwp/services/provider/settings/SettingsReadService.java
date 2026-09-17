package com.dwp.services.provider.settings;

import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class SettingsReadService {

    private final SettingsDefinitionRegistry registry;
    private final SettingsApplicationStatusEvaluator statusEvaluator;
    private final Clock clock;

    @Autowired
    public SettingsReadService(
            SettingsDefinitionRegistry registry,
            SettingsApplicationStatusEvaluator statusEvaluator) {
        this(registry, statusEvaluator, Clock.systemUTC());
    }

    SettingsReadService(
            SettingsDefinitionRegistry registry,
            SettingsApplicationStatusEvaluator statusEvaluator,
            Clock clock) {
        this.registry = registry;
        this.statusEvaluator = statusEvaluator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SettingsContracts.Definition> catalog(
            String query,
            String ownerService,
            SettingsContracts.ScopeType scopeType) {
        String normalizedQuery = SettingsContracts.optional(query);
        String normalizedOwner = SettingsContracts.optional(ownerService);
        return registry.authorizedDefinitions().stream()
                .filter(definition -> normalizedQuery == null
                        || containsIgnoreCase(definition.settingId(), normalizedQuery)
                        || containsIgnoreCase(definition.displayName(), normalizedQuery)
                        || containsIgnoreCase(definition.description(), normalizedQuery))
                .filter(definition -> normalizedOwner == null
                        || definition.owner().service().equals(normalizedOwner))
                .filter(definition -> scopeType == null
                        || definition.supportedScopes().contains(scopeType))
                .toList();
    }

    @Transactional(readOnly = true)
    public SettingsContracts.Resolution effective(
            String settingId,
            SettingsContracts.ScopeTarget target) {
        String normalizedId = SettingsContracts.required(settingId, "setting ID");
        SettingsDefinitionRegistry.Entry entry = registry.authorizedEntry(normalizedId)
                .orElse(null);
        if (entry == null) {
            return unavailable(normalizedId, target,
                    SettingsContracts.ResolutionState.UNSUPPORTED_SETTING,
                    "SETTING_NOT_REGISTERED");
        }
        ProviderRequestContext.requirePermission(entry.adapter().readPermission());
        SettingsContracts.Definition definition = entry.definition();
        if (!definition.supportedScopes().contains(target.scopeType())) {
            return new SettingsContracts.Resolution(
                    normalizedId, SettingsContracts.ResolutionState.UNSUPPORTED_SCOPE,
                    definition, target, null, null, List.of(), null,
                    "SCOPE_NOT_SUPPORTED_BY_OWNER", clock.instant());
        }

        SettingsContracts.ScopeTarget canonicalTarget =
                entry.adapter().canonicalTarget(target);

        SettingsContracts.OwnerSnapshot snapshot = entry.adapter().resolve(
                definition, canonicalTarget);
        SettingsContracts.ApplicationStatus applicationStatus = statusEvaluator.evaluate(
                snapshot.applicationEvidence(), clock.instant());
        boolean redact = definition.sensitivity() == SettingsContracts.Sensitivity.SECRET;
        return new SettingsContracts.Resolution(
                normalizedId,
                redact ? SettingsContracts.ResolutionState.REDACTED
                        : SettingsContracts.ResolutionState.RESOLVED,
                definition,
                canonicalTarget,
                redact ? null : snapshot.effectiveValue(),
                snapshot.effectiveVersion(),
                snapshot.provenance(),
                applicationStatus,
                redact ? "VALUE_REDACTED_BY_SENSITIVITY" : "OWNER_VALUE_RESOLVED",
                snapshot.resolvedAt());
    }

    private SettingsContracts.Resolution unavailable(
            String settingId,
            SettingsContracts.ScopeTarget target,
            SettingsContracts.ResolutionState state,
            String reason) {
        return new SettingsContracts.Resolution(
                settingId, state, null, target, null, null, List.of(), null,
                reason, clock.instant());
    }

    private boolean containsIgnoreCase(String value, String query) {
        return value.regionMatches(true, 0, query, 0, query.length())
                || value.toLowerCase(java.util.Locale.ROOT)
                .contains(query.toLowerCase(java.util.Locale.ROOT));
    }
}
