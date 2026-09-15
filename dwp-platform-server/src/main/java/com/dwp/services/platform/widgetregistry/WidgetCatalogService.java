package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.HomeExperienceService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WidgetCatalogService {
    private static final List<String> CAPABILITIES = List.of(
            "WIDGET_REGISTRY_CONTROL_PLANE",
            "WIDGET_REGISTRY_SHADOW_EVALUATION",
            "TENANT_WIDGET_POLICY");
    private final WidgetRegistryLedger ledger;
    private final WidgetDefinitionRepository definitions;
    private final WidgetDefinitionVersionRepository versions;
    private final WidgetRendererBindingRepository bindings;
    private final WidgetReleaseChannelRepository channels;
    private final TenantWidgetPolicyHeadRepository heads;
    private final TenantWidgetPolicyRevisionRepository policies;
    private final WidgetRegistryMutationGuard controls;
    private final HomeExperienceService homeExperience;
    private final WidgetRegistryDefinitionService definitionService;

    public WidgetCatalogService(
            WidgetRegistryLedger ledger,
            WidgetDefinitionRepository definitions,
            WidgetDefinitionVersionRepository versions,
            WidgetRendererBindingRepository bindings,
            WidgetReleaseChannelRepository channels,
            TenantWidgetPolicyHeadRepository heads,
            TenantWidgetPolicyRevisionRepository policies,
            WidgetRegistryMutationGuard controls,
            HomeExperienceService homeExperience,
            WidgetRegistryDefinitionService definitionService) {
        this.ledger = ledger;
        this.definitions = definitions;
        this.versions = versions;
        this.bindings = bindings;
        this.channels = channels;
        this.heads = heads;
        this.policies = policies;
        this.controls = controls;
        this.homeExperience = homeExperience;
        this.definitionService = definitionService;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ReadinessResponse readiness() {
        WidgetRegistryState state = ledger.state();
        return new WidgetRegistryDtos.ReadinessResponse(
                1, state.getMigrationMode(), true, false, CAPABILITIES,
                state.getRegistryRevision(), state.getPolicyRevision(), state.getSafetyRevision());
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.EffectiveCatalogResponse effective(
            Long tenantId,
            String surfaceKey,
            String permissionHeader,
            String roleHeader,
            String groupHeader) {
        if (!"workspace-home".equals(surfaceKey)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported Widget Catalog surface.");
        }
        AuthorityContext authority = AuthorityContext.of(permissionHeader, roleHeader, groupHeader);
        WidgetRegistryState state = ledger.state();
        HomeExperienceDtos.HomeExperienceResponse home = homeExperience.get(tenantId);
        String resolvedHostMode = "FLOW_V1".equals(home.effectiveExperienceVariant()) ? "FLOW" : "CLASSIC";
        List<String> contextKeys = "FLOW".equals(resolvedHostMode)
                ? List.of("FLOW_PERSONAL", "FLOW_GOVERNED") : List.of("CLASSIC_PERSONAL");
        Map<UUID, TenantWidgetPolicyRevision> tenantPolicies = heads.findByTenantId(tenantId).stream()
                .filter(head -> head.getCurrentRevisionId() != null)
                .map(head -> policies.findByPolicyRevisionIdAndTenantId(
                        head.getCurrentRevisionId(), tenantId).orElse(null))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(TenantWidgetPolicyRevision::getDefinitionId, Function.identity()));
        List<WidgetDefinition> ordered = definitions.findAll().stream()
                .sorted(Comparator.comparing(WidgetDefinition::getDefinitionKey)).toList();
        List<WidgetRegistryDtos.PlacementContext> contexts = new ArrayList<>();
        for (String contextKey : contextKeys) {
            List<WidgetRegistryDtos.EffectiveItem> items = ordered.stream()
                    .map(definition -> evaluate(tenantId, surfaceKey, contextKey, definition,
                            tenantPolicies.get(definition.getDefinitionId()), authority, state))
                    .filter(java.util.Objects::nonNull).toList();
            contexts.add(new WidgetRegistryDtos.PlacementContext(
                    contextKey,
                    new WidgetRegistryDtos.CatalogCapabilities(
                            true, false, false, false, false, false),
                    items));
        }
        String catalogRevision = Long.toString(state.getRegistryRevision());
        String bindingRevision = bindingRevision();
        String policyRevision = Long.toString(state.getPolicyRevision());
        String safetyRevision = Long.toString(state.getSafetyRevision());
        String decisionRevision = WidgetRegistryCommandReceiptService.fingerprintText(
                tenantId + "\n" + surfaceKey + "\n" + resolvedHostMode + "\n"
                        + catalogRevision + "\n" + bindingRevision + "\n"
                        + policyRevision + "\n" + safetyRevision + "\n" + authority.fingerprint());
        WidgetRegistryDtos.HostContext hostContext = new WidgetRegistryDtos.HostContext(
                surfaceKey, resolvedHostMode, home.version(),
                home.compositionPolicy() == null || home.compositionPolicy().schemaVersion() == null
                        ? 3 : home.compositionPolicy().schemaVersion(),
                "VIEWS".equals(home.homePreferenceStore()) ? "HOME_VIEW" : "LEGACY_PREFERENCE",
                null, 0, "home:" + home.version(), 1, decisionRevision);
        return new WidgetRegistryDtos.EffectiveCatalogResponse(
                WidgetRegistryDtos.SCHEMA_VERSION, state.getMigrationMode(), catalogRevision, bindingRevision,
                policyRevision, safetyRevision, hostContext, List.copyOf(contexts));
    }

    private WidgetRegistryDtos.EffectiveItem evaluate(
            Long tenantId,
            String surfaceKey,
            String context,
            WidgetDefinition definition,
            TenantWidgetPolicyRevision policy,
            AuthorityContext authority,
            WidgetRegistryState registryState) {
        UUID versionId = resolveVersion(policy);
        WidgetDefinitionVersion version = versionId == null ? null : versions.findById(versionId).orElse(null);
        if (version != null && !supportsContext(version.getManifest(), context)) return null;
        Set<WidgetRegistryDtos.EffectiveCatalogReason> reasons = new LinkedHashSet<>();
        if (!"ACTIVE".equals(definition.getDefinitionState())) {
            reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.NOT_AVAILABLE);
        }
        if (policy == null) {
            reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.DISABLED_BY_ORGANIZATION);
        }
        else {
            if (!"PUBLISHED".equals(policy.getPolicyState()) || !policy.isEnabled()) {
                reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.DISABLED_BY_ORGANIZATION);
            }
            if (!supportsSurface(policy.getSupportedSurfaceKeys(), surfaceKey)) {
                reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.INCOMPATIBLE);
            }
            if (!WidgetAudienceSelectorContract.matches(
                    policy.getAudienceSelector(), authority.roles(), authority.groups())) {
                reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.NOT_AVAILABLE);
            }
        }
        boolean deprecated = false;
        if (version == null) {
            reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.INCOMPATIBLE);
        }
        else {
            switch (version.getReleaseState()) {
                case "PUBLISHED" -> { }
                case "DEPRECATED" -> deprecated = true;
                case "BLOCKED" -> reasons.add(
                        WidgetRegistryDtos.EffectiveCatalogReason.TEMPORARILY_UNAVAILABLE);
                default -> reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.NOT_AVAILABLE);
            }
            switch (version.getSafetyState()) {
                case "CLEAR" -> { }
                case "QUARANTINED" -> reasons.add(
                        WidgetRegistryDtos.EffectiveCatalogReason.TEMPORARILY_UNAVAILABLE);
                default -> reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.NOT_AVAILABLE);
            }
            boolean legacyShadowDiscovery = "SHADOW".equals(registryState.getMigrationMode())
                    && !registryState.isRuntimeActivationReady()
                    && "LEGACY_UNVERIFIED".equals(version.getAttestation().path("source").asText());
            if (!legacyShadowDiscovery
                    && (!"PASS".equals(version.getCertificationStatus())
                    || !definitionService.hasCurrentCertificationEvidence(version))) {
                reasons.add("EXPIRED".equals(version.getCertificationStatus())
                        ? WidgetRegistryDtos.EffectiveCatalogReason.TEMPORARILY_UNAVAILABLE
                        : WidgetRegistryDtos.EffectiveCatalogReason.NOT_AVAILABLE);
            }
            if (bindings.findByRendererKeyAndBindingState(version.getRendererKey(), "ACTIVE").isEmpty()) {
                reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.NOT_AVAILABLE);
            }
            if (!hasRequiredAuthorities(version.getManifest(), authority.permissions())) {
                reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.APP_ACCESS_REQUIRED);
            }
            if (controls.runtimeDenied(
                    "CATALOG_DISCOVERY", tenantId, definition.getOwnerProductKey(),
                    definition.getDefinitionId(), version.getVersionId())) {
                reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.TEMPORARILY_UNAVAILABLE);
            }
        }
        WidgetRegistryDtos.EffectiveCatalogState effectiveState;
        List<WidgetRegistryDtos.EffectiveCatalogReason> reasonCodes;
        if (!reasons.isEmpty()) {
            effectiveState = WidgetRegistryDtos.EffectiveCatalogState.DENY;
            reasonCodes = List.copyOf(reasons);
        } else if (deprecated) {
            effectiveState = WidgetRegistryDtos.EffectiveCatalogState.DEPRECATED;
            reasonCodes = List.of(WidgetRegistryDtos.EffectiveCatalogReason.DEPRECATED);
        } else {
            effectiveState = WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE;
            reasonCodes = List.of(WidgetRegistryDtos.EffectiveCatalogReason.AVAILABLE);
        }
        return new WidgetRegistryDtos.EffectiveItem(
                definition.getDefinitionId(), definition.getDefinitionKey(), definition.getLegacyWidgetKey(),
                versionId, version == null ? null : version.getSemanticVersion(), effectiveState, reasonCodes,
                new WidgetRegistryDtos.PlacementCapabilities(
                        effectiveState == WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE
                                && registryState.isRuntimeActivationReady(),
                        false, false, false), 0);
    }

    private UUID resolveVersion(TenantWidgetPolicyRevision policy) {
        if (policy == null) return null;
        if ("PINNED".equals(policy.getSelectorType())) return policy.getVersionId();
        return channels.findByDefinitionIdAndChannel(policy.getDefinitionId(), policy.getChannel())
                .map(WidgetReleaseChannel::getCurrentVersionId).orElse(null);
    }

    private boolean supportsSurface(JsonNode surfaces, String surfaceKey) {
        if (surfaces == null || !surfaces.isArray()) return false;
        for (JsonNode surface : surfaces) if (surfaceKey.equals(surface.asText())) return true;
        return false;
    }

    private boolean supportsContext(JsonNode manifest, String context) {
        if (manifest == null) return false;
        JsonNode contexts = manifest.path("placement").path("supportedContexts");
        for (JsonNode value : contexts) if (context.equals(value.asText())) return true;
        return false;
    }

    static boolean hasRequiredAuthorities(JsonNode manifest, Set<String> permissions) {
        if (manifest == null) return false;
        JsonNode required = manifest.path("requiredAuthorities");
        if (!required.isArray() || required.isEmpty()) return false;
        for (JsonNode authority : required) {
            if (!authority.isTextual()
                    || !permissions.contains(authority.asText().trim().toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private String bindingRevision() {
        String material = bindings.findByBindingStateOrderByRendererKey("ACTIVE").stream()
                .map(binding -> binding.getRendererKey() + ":" + binding.getBindingRevision())
                .collect(Collectors.joining("\n"));
        return WidgetRegistryCommandReceiptService.fingerprintText(material);
    }

    record AuthorityContext(
            Set<String> permissions,
            Set<String> roles,
            Set<String> groups,
            String fingerprint) {
        static AuthorityContext of(String permissions, String roles, String groups) {
            Set<String> permissionSet = tokens(permissions, true);
            Set<String> roleSet = tokens(roles, true);
            Set<String> groupSet = tokens(groups, false);
            String material = String.join("\n",
                    permissionSet.stream().sorted().toList()) + "\n--\n"
                    + String.join("\n", roleSet.stream().sorted().toList()) + "\n--\n"
                    + String.join("\n", groupSet.stream().sorted().toList());
            return new AuthorityContext(permissionSet, roleSet, groupSet,
                    WidgetRegistryCommandReceiptService.fingerprintText(material));
        }

        private static Set<String> tokens(String header, boolean uppercase) {
            if (header == null || header.isBlank()) return Set.of();
            return Arrays.stream(header.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(value -> uppercase ? value.toUpperCase(Locale.ROOT) : value)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
