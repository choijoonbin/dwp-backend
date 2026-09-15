package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.HomeExperienceService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private static final List<BaselineWidget> NATIVE_BASELINE = List.of(
            new BaselineWidget(
                    "core.workspace.command-rail",
                    "a3a1fd5ffff9d7f6014ec3007a16ebea10dbf8ce3ae19e02fd2bd001fee0eb97",
                    "home.command-rail",
                    "30000000-0000-0000-0000-000000000001",
                    "31000000-0000-0000-0000-000000000001"),
            new BaselineWidget(
                    "core.workspace.daily-brief",
                    "9b7f48b7ea4ef429120db330a4972c3315ad682759fa86e49c212c42bdd02406",
                    "home.daily-brief",
                    "30000000-0000-0000-0000-000000000002",
                    "31000000-0000-0000-0000-000000000002"),
            new BaselineWidget(
                    "core.work.focus",
                    "36d1b02326e4725a235749e173dfdf50a0423ef30f42d7ccab97946ba826d893",
                    "home.focus",
                    "30000000-0000-0000-0000-000000000003",
                    "31000000-0000-0000-0000-000000000003"),
            new BaselineWidget(
                    "core.calendar.schedule",
                    "7f3e090997a213e9d3e6f8184e1458e57382c5f31db79f00fbf678d36f884f5d",
                    "home.schedule",
                    "30000000-0000-0000-0000-000000000004",
                    "31000000-0000-0000-0000-000000000004"),
            new BaselineWidget(
                    "core.activity.activity",
                    "fbab61015ec3b20c2faf9810b1758aebbd7517029baa64cb6b99190815836ca1",
                    "home.activity",
                    "30000000-0000-0000-0000-000000000005",
                    "31000000-0000-0000-0000-000000000005"),
            new BaselineWidget(
                    "core.work.focus-balance",
                    "10388bcc9f1bf02f761790b157d7b1d10b574e362d3db81a198cb45ade05c899",
                    "home.focus-balance",
                    "30000000-0000-0000-0000-000000000006",
                    "31000000-0000-0000-0000-000000000006"),
            new BaselineWidget(
                    "core.calendar.meeting-load",
                    "17b5fee8b514793d8244ce6ac744979a5ad7a3805817612521fc2c77a7e6add2",
                    "home.meeting-load",
                    "30000000-0000-0000-0000-000000000007",
                    "31000000-0000-0000-0000-000000000007"));
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
        List<WidgetDefinitionVersion> baselineVersions = resolveBaseline();
        boolean controlPlaneReady = baselineVersions.size() == NATIVE_BASELINE.size();
        boolean runtimeActivationReady = controlPlaneReady
                && state.isRuntimeActivationReady()
                && "AUTHORITATIVE".equals(state.getMigrationMode())
                && baselineVersions.stream().allMatch(version ->
                        "PASS".equals(version.getCertificationStatus())
                                && definitionService.hasCurrentCertificationEvidence(version));
        return new WidgetRegistryDtos.ReadinessResponse(
                1,
                state.getMigrationMode(),
                controlPlaneReady,
                runtimeActivationReady,
                CAPABILITIES,
                state.getRegistryRevision(), state.getPolicyRevision(), state.getSafetyRevision());
    }

    private List<WidgetDefinitionVersion> resolveBaseline() {
        List<WidgetDefinitionVersion> resolved = new ArrayList<>();
        for (BaselineWidget baseline : NATIVE_BASELINE) {
            WidgetDefinition definition = definitions.findByDefinitionKey(baseline.definitionKey())
                    .filter(value -> "ACTIVE".equals(value.getDefinitionState()))
                    .orElse(null);
            if (definition == null) return List.of();
            WidgetDefinitionVersion version = versions.findByDefinitionIdAndSemanticVersion(
                            definition.getDefinitionId(), "1.0.0")
                    .filter(value -> baseline.manifestHash().equals(value.getManifestHash()))
                    .filter(value -> baseline.rendererKey().equals(value.getRendererKey()))
                    .filter(value -> "PUBLISHED".equals(value.getReleaseState()))
                    .filter(value -> "CLEAR".equals(value.getSafetyState()))
                    .orElse(null);
            if (version == null
                    || bindings.findByRendererKeyAndBindingState(
                                    baseline.rendererKey(), "ACTIVE")
                            .filter(binding -> baseline.manifestHash().equals(
                                    binding.getBindingRevision()))
                            .isEmpty()
                    || channels.findByDefinitionIdAndChannel(
                                    definition.getDefinitionId(), "STABLE")
                            .filter(channel -> version.getVersionId().equals(
                                    channel.getCurrentVersionId()))
                            .isEmpty()) {
                return List.of();
            }
            resolved.add(version);
        }
        return List.copyOf(resolved);
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
                case "DEPRECATED" -> {
                    if (version.getDeprecationEndsAt() == null
                            || !version.getDeprecationEndsAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
                        reasons.add(WidgetRegistryDtos.EffectiveCatalogReason.NOT_AVAILABLE);
                    } else {
                        deprecated = true;
                    }
                }
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
                    && "NOT_RUN".equals(version.getCertificationStatus())
                    && "LEGACY_UNVERIFIED".equals(version.getAttestation().path("source").asText())
                    && NATIVE_BASELINE.stream().anyMatch(baseline -> baseline.matches(definition, version))
                    && bindings.findByRendererKeyAndBindingState(version.getRendererKey(), "ACTIVE")
                            .filter(binding -> "NATIVE".equals(binding.getKind()))
                            .filter(binding -> version.getManifestHash().equals(binding.getBindingRevision()))
                            .isPresent();
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

    private record BaselineWidget(
            String definitionKey, String manifestHash, String rendererKey,
            String definitionId, String versionId) {
        boolean matches(WidgetDefinition definition, WidgetDefinitionVersion version) {
            return definitionId.equals(definition.getDefinitionId().toString())
                    && versionId.equals(version.getVersionId().toString())
                    && definitionKey.equals(definition.getDefinitionKey())
                    && definition.getDefinitionId().equals(version.getDefinitionId())
                    && "1.0.0".equals(version.getSemanticVersion())
                    && manifestHash.equals(version.getManifestHash())
                    && rendererKey.equals(version.getRendererKey());
        }
    }
}
