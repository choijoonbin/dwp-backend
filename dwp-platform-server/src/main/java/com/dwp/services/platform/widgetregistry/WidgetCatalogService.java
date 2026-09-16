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
import java.util.LinkedHashMap;
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
                    "1.0.1",
                    "36de53926e21ef11e61c78f6325fdf35b37998fe42403e0df0e70d71e3f4df13",
                    "home.command-rail",
                    "core.workspace",
                    "APP.WORK",
                    "30000000-0000-0000-0000-000000000001",
                    "31000000-0000-0000-0000-000000000101"),
            new BaselineWidget(
                    "core.workspace.daily-brief",
                    "1.0.0",
                    "9b7f48b7ea4ef429120db330a4972c3315ad682759fa86e49c212c42bdd02406",
                    "home.daily-brief",
                    "core.workspace",
                    "APP.WORK",
                    "30000000-0000-0000-0000-000000000002",
                    "31000000-0000-0000-0000-000000000002"),
            new BaselineWidget(
                    "core.work.focus",
                    "1.0.0",
                    "36d1b02326e4725a235749e173dfdf50a0423ef30f42d7ccab97946ba826d893",
                    "home.focus",
                    "core.work",
                    "APP.WORK",
                    "30000000-0000-0000-0000-000000000003",
                    "31000000-0000-0000-0000-000000000003"),
            new BaselineWidget(
                    "core.calendar.schedule",
                    "1.0.0",
                    "7f3e090997a213e9d3e6f8184e1458e57382c5f31db79f00fbf678d36f884f5d",
                    "home.schedule",
                    "core.calendar",
                    "APP.CALENDAR",
                    "30000000-0000-0000-0000-000000000004",
                    "31000000-0000-0000-0000-000000000004"),
            new BaselineWidget(
                    "core.activity.activity",
                    "1.0.0",
                    "fbab61015ec3b20c2faf9810b1758aebbd7517029baa64cb6b99190815836ca1",
                    "home.activity",
                    "core.activity",
                    "APP.ACTIVITY",
                    "30000000-0000-0000-0000-000000000005",
                    "31000000-0000-0000-0000-000000000005"),
            new BaselineWidget(
                    "core.work.focus-balance",
                    "1.0.1",
                    "5f4c5990a0b1b417832c93074f92a00cbb8c9e4f4e49240073120c485a8c9436",
                    "home.focus-balance",
                    "core.calendar",
                    "APP.CALENDAR",
                    "30000000-0000-0000-0000-000000000006",
                    "31000000-0000-0000-0000-000000000106"),
            new BaselineWidget(
                    "core.calendar.meeting-load",
                    "1.0.1",
                    "90d31f29e1dbc8e26a49475174aca5ecd76d557f3b7d39975f58ff1f047bb6c3",
                    "home.meeting-load",
                    "core.calendar",
                    "APP.CALENDAR",
                    "30000000-0000-0000-0000-000000000007",
                    "31000000-0000-0000-0000-000000000107"));
    private static final Set<String> OWNER_PROVIDER_EVIDENCE = Set.of(
            "MANIFEST", "SECURITY", "PRIVACY");
    private static final List<BaselineWidget> OWNER_PROVIDER_SHADOW_BASELINE = List.of(
            new BaselineWidget(
                    "approval.focus-queue", "1.0.0",
                    "d203595d9b713745a5e46e3545f29bd47cf403d016b6773aabc13b24d29f1600",
                    "home.approval.focus-queue", "core.approvals", "APP.APPROVALS",
                    "36000000-0000-0000-0000-000000000001",
                    "36100000-0000-0000-0000-000000000001"),
            new BaselineWidget(
                    "approval.my-requests", "1.0.0",
                    "a80bb5bc85786cc1b045eae227dd4b083de6089e41f037ff735e79fdd8ef8d12",
                    "home.approval.my-requests", "core.approvals", "APP.APPROVALS",
                    "36000000-0000-0000-0000-000000000002",
                    "36100000-0000-0000-0000-000000000002"),
            new BaselineWidget(
                    "meetings.next-prep", "1.0.0",
                    "12b4b13ad8838b821931247cbaae7bb694d80f0301138c75d5364759410aeb71",
                    "home.meetings.next-prep", "core.meetings", "APP.MEETINGS",
                    "36000000-0000-0000-0000-000000000003",
                    "36100000-0000-0000-0000-000000000003"),
            new BaselineWidget(
                    "meetings.followup-candidates", "1.0.0",
                    "9a18a83a2b704e4cad302365305ff112ad444629ff49ccca310a98d7c94b5e3b",
                    "home.meetings.followup-candidates", "core.meetings", "APP.MEETINGS",
                    "36000000-0000-0000-0000-000000000004",
                    "36100000-0000-0000-0000-000000000004"),
            new BaselineWidget(
                    "notification.app-badges", "1.0.0",
                    "99f8e3d68f8c7b7d6fb175b6bedb24653bf838a47d87eaa11861078ea789d930",
                    "home.notification.app-badges", "core.notifications", "APP.NOTIFICATIONS",
                    "36000000-0000-0000-0000-000000000005",
                    "36100000-0000-0000-0000-000000000005"),
            new BaselineWidget(
                    "notification.response-queue", "1.0.0",
                    "1095138bdafd1c04e638e65f458f207f7d4616cc395e3da82969c34a0b5ce14b",
                    "home.notification.response-queue", "core.notifications", "APP.NOTIFICATIONS",
                    "36000000-0000-0000-0000-000000000006",
                    "36100000-0000-0000-0000-000000000006"),
            new BaselineWidget(
                    "space.change-feed", "1.0.0",
                    "679133ea378aebfe3259d41615e084a74b3f02136ca273edd023df3896e45ce1",
                    "home.space.change-feed", "core.spaces", "APP.SPACES",
                    "36000000-0000-0000-0000-000000000007",
                    "36100000-0000-0000-0000-000000000007"),
            new BaselineWidget(
                    "space.response-queue", "1.0.0",
                    "cf00ea0674e3dcaec95b5dac3c74305e156815911d74570056e13721e4873457",
                    "home.space.response-queue", "core.spaces", "APP.SPACES",
                    "36000000-0000-0000-0000-000000000008",
                    "36100000-0000-0000-0000-000000000008"),
            new BaselineWidget(
                    "messaging.response-queue", "1.0.0",
                    "3ade20ba736ad8436a43b7877006b0393be15fd42ca711aaf1631990cabc65a1",
                    "home.messaging.response-queue", "core.messaging", "APP.MESSAGING",
                    "36000000-0000-0000-0000-000000000009",
                    "36100000-0000-0000-0000-000000000009"),
            new BaselineWidget(
                    "messaging.change-feed", "1.0.0",
                    "c25cb2b6e21b33e7fa1712956cae92f56fafd5cdb6b426c55db9a64924bcf0bc",
                    "home.messaging.change-feed", "core.messaging", "APP.MESSAGING",
                    "36000000-0000-0000-0000-000000000010",
                    "36100000-0000-0000-0000-000000000010"),
            new BaselineWidget(
                    "hr.edu", "1.0.0",
                    "05806990658c73ffaf4e5f5656721778641dfef19b4734c96d1f4fa9f3463eb8",
                    "home.hr.edu", "core.people", "APP.HCM",
                    "36000000-0000-0000-0000-000000000011",
                    "36100000-0000-0000-0000-000000000011"),
            new BaselineWidget(
                    "hr.team-pulse", "1.0.0",
                    "9cf2e1770e377a3a7a7721ee795beaf9ad1649bac8a8977046e9990f9c6604df",
                    "home.hr.team-pulse", "core.people", "APP.HCM",
                    "36000000-0000-0000-0000-000000000012",
                    "36100000-0000-0000-0000-000000000012"));
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
                    .filter(value -> baseline.ownerProductKey().equals(value.getOwnerProductKey()))
                    .orElse(null);
            if (definition == null) return List.of();
            WidgetDefinitionVersion version = versions.findByDefinitionIdAndSemanticVersion(
                            definition.getDefinitionId(), baseline.semanticVersion())
                    .filter(value -> baseline.manifestHash().equals(value.getManifestHash()))
                    .filter(value -> baseline.rendererKey().equals(value.getRendererKey()))
                    .filter(value -> "PUBLISHED".equals(value.getReleaseState()))
                    .filter(value -> "CLEAR".equals(value.getSafetyState()))
                    .orElse(null);
            if (version == null
                    || bindings.findByRendererKeyAndBindingState(
                                    baseline.rendererKey(), "ACTIVE")
                            .filter(baseline::matchesBinding)
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
        return effectiveForMode(
                tenantId, surfaceKey, permissionHeader, roleHeader, groupHeader, null);
    }

    /**
     * Returns the bounded manifest contracts that the legacy Home View store may persist.
     * Registry rollout stays in SHADOW: this does not enable broker rendering or v6 instances.
     */
    @Transactional(readOnly = true)
    public Map<String, PlacementWriteContract> availablePlacementContracts(
            Long tenantId,
            String surfaceKey,
            String permissionHeader,
            String roleHeader,
            String groupHeader) {
        WidgetRegistryDtos.EffectiveCatalogResponse evaluated = effective(
                tenantId, surfaceKey, permissionHeader, roleHeader, groupHeader);
        return WidgetPlacementWriteContractResolver.resolve(evaluated, versions);
    }

    private WidgetRegistryDtos.EffectiveCatalogResponse effectiveForMode(
            Long tenantId,
            String surfaceKey,
            String permissionHeader,
            String roleHeader,
            String groupHeader,
            String requestedMode) {
        if (!"workspace-home".equals(surfaceKey)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported Widget Catalog surface.");
        }
        AuthorityContext authority = AuthorityContext.of(permissionHeader, roleHeader, groupHeader);
        WidgetRegistryState state = ledger.state();
        HomeExperienceDtos.HomeExperienceResponse home = homeExperience.get(tenantId);
        String resolvedHostMode = requestedMode == null
                ? ("FLOW_V1".equals(home.effectiveExperienceVariant()) ? "FLOW" : "CLASSIC")
                : requestedMode;
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

    /**
     * Supplies the Runtime Broker with the same evaluated catalog decision and immutable manifest
     * metadata used by catalog discovery. The broker must still enforce the returned state and
     * never treats SHADOW catalog evaluation as permission to activate Registry authority.
     */
    @Transactional(readOnly = true)
    public RuntimeCatalog runtimeCatalog(
            Long tenantId,
            String surfaceKey,
            String permissionHeader,
            String roleHeader,
            String groupHeader,
            String mode) {
        String hostMode = "FLOW_V1".equals(mode) ? "FLOW" : "CLASSIC";
        WidgetRegistryDtos.EffectiveCatalogResponse evaluated = effectiveForMode(
                tenantId, surfaceKey, permissionHeader, roleHeader, groupHeader, hostMode);
        Map<String, RuntimeDefinition> definitionsByKey = new LinkedHashMap<>();
        evaluated.contexts().stream()
                .flatMap(context -> context.items().stream())
                .forEach(item -> definitionsByKey.computeIfAbsent(
                        item.definitionKey(), ignored -> runtimeDefinition(
                                item, evaluated.bindingCatalogRevision())));
        return new RuntimeCatalog(
                evaluated.mode(),
                evaluated.catalogRevision(),
                evaluated.bindingCatalogRevision(),
                evaluated.policyRevision(),
                evaluated.safetyRevision(),
                evaluated.hostContext().decisionRevision(),
                List.copyOf(definitionsByKey.values()));
    }

    private RuntimeDefinition runtimeDefinition(
            WidgetRegistryDtos.EffectiveItem item,
            String rendererBindingRevision) {
        WidgetDefinition definition = definitions.findById(item.definitionId()).orElse(null);
        WidgetDefinitionVersion version = item.resolvedVersionId() == null
                ? null : versions.findById(item.resolvedVersionId()).orElse(null);
        JsonNode manifest = version == null ? null : version.getManifest();
        return new RuntimeDefinition(
                item.definitionId(),
                item.definitionKey(),
                item.legacyWidgetKey(),
                item.resolvedVersionId(),
                item.semanticVersion(),
                version == null ? null : version.getManifestHash(),
                rendererBindingRevision,
                version == null ? null : version.getRendererKey(),
                definition == null ? null : definition.getOwnerProductKey(),
                text(manifest, "/owner/sourceAppResourceKey"),
                strings(manifest, "/requiredAuthorities"),
                text(manifest, "/privacy/classification"),
                text(manifest, "/privacy/retention"),
                integer(manifest, "/operations/freshnessSeconds", 30),
                item.effectiveState(),
                item.reasonCodes().stream().map(Enum::name).toList());
    }

    private String text(JsonNode node, String pointer) {
        if (node == null) return null;
        JsonNode value = node.at(pointer);
        return value.isTextual() ? value.asText() : null;
    }

    private List<String> strings(JsonNode node, String pointer) {
        if (node == null || !node.at(pointer).isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.at(pointer).forEach(value -> {
            if (value.isTextual()) result.add(value.asText());
        });
        return List.copyOf(result);
    }

    private int integer(JsonNode node, String pointer, int fallback) {
        if (node == null || !node.at(pointer).canConvertToInt()) return fallback;
        return node.at(pointer).asInt(fallback);
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
                            .filter(binding -> NATIVE_BASELINE.stream().anyMatch(baseline ->
                                    baseline.matches(definition, version)
                                            && baseline.matchesBinding(binding)))
                            .isPresent();
            boolean ownerProviderShadowDiscovery = "SHADOW".equals(
                    registryState.getMigrationMode())
                    && !registryState.isRuntimeActivationReady()
                    && "NOT_RUN".equals(version.getCertificationStatus())
                    && "WAVE4_OWNER_PROVIDER_SHADOW".equals(
                            version.getAttestation().path("source").asText())
                    && OWNER_PROVIDER_SHADOW_BASELINE.stream().anyMatch(
                            baseline -> baseline.matches(definition, version))
                    && definitionService.hasCurrentEvidence(
                            version, OWNER_PROVIDER_EVIDENCE)
                    && bindings.findByRendererKeyAndBindingState(
                                    version.getRendererKey(), "ACTIVE")
                            .filter(binding -> OWNER_PROVIDER_SHADOW_BASELINE.stream().anyMatch(
                                    baseline -> baseline.matches(definition, version)
                                            && baseline.matchesBinding(binding)))
                            .isPresent();
            if (!legacyShadowDiscovery && !ownerProviderShadowDiscovery
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

    public record RuntimeCatalog(
            String registryMode,
            String catalogRevision,
            String bindingRevision,
            String policyRevision,
            String safetyRevision,
            String decisionRevision,
            List<RuntimeDefinition> definitions) {
    }

    public record RuntimeDefinition(
            UUID definitionId,
            String definitionKey,
            String legacyWidgetKey,
            UUID versionId,
            String semanticVersion,
            String manifestHash,
            String rendererBindingRevision,
            String rendererKey,
            String ownerProductKey,
            String sourceAppResourceKey,
            List<String> requiredAuthorities,
            String classification,
            String retention,
            int freshnessSeconds,
            WidgetRegistryDtos.EffectiveCatalogState effectiveState,
            List<String> reasonCodes) {
    }

    public record PlacementWriteContract(
            boolean canHide,
            String defaultSize,
            Set<String> allowedSizes,
            String defaultHeight,
            Set<String> allowedHeights) {
    }

    private record BaselineWidget(
            String definitionKey, String semanticVersion, String manifestHash, String rendererKey,
            String ownerProductKey, String sourceAppResourceKey,
            String definitionId, String versionId) {
        boolean matches(WidgetDefinition definition, WidgetDefinitionVersion version) {
            return definitionId.equals(definition.getDefinitionId().toString())
                    && versionId.equals(version.getVersionId().toString())
                    && definitionKey.equals(definition.getDefinitionKey())
                    && ownerProductKey.equals(definition.getOwnerProductKey())
                    && definition.getDefinitionId().equals(version.getDefinitionId())
                    && semanticVersion.equals(version.getSemanticVersion())
                    && manifestHash.equals(version.getManifestHash())
                    && rendererKey.equals(version.getRendererKey());
        }

        boolean matchesBinding(WidgetRendererBinding binding) {
            return "NATIVE".equals(binding.getKind())
                    && rendererKey.equals(binding.getRendererKey())
                    && ownerProductKey.equals(binding.getOwnerProductKey())
                    && sourceAppResourceKey.equals(binding.getSourceAppResourceKey())
                    && manifestHash.equals(binding.getBindingRevision());
        }
    }
}
