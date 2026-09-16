package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.ApprovedHomeApplicationCatalog;
import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.HomeExperienceService;
import com.dwp.services.platform.home.personalization.EffectiveHomeViewQuery;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.home.personalization.HomeViewDtos;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class HomeReadModelService {

    private static final String APP_BADGE_DEFINITION = "notification.app-badges";
    private static final String APP_BADGE_INSTANCE_KEY = "@composition.app-dock-badges";
    private static final Set<String> MODES = Set.of("CLASSIC", "FLOW_V1");
    private static final Set<String> CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> RETENTIONS = Set.of("NONE", "SESSION", "SHORT_LIVED");

    private final HomeExperienceService experiences;
    private final EffectiveHomeViewQuery views;
    private final WidgetCatalogService catalog;
    private final WidgetRuntimeBroker broker;
    private final ObjectMapper objectMapper;
    private final HomeCanonicalJson canonicalJson;
    private final HomeRuntimeRolloutDecisionResolver rolloutDecisions;

    @Autowired
    public HomeReadModelService(
            HomeExperienceService experiences,
            EffectiveHomeViewQuery views,
            WidgetCatalogService catalog,
            WidgetRuntimeBroker broker,
            ObjectMapper objectMapper,
            HomeCanonicalJson canonicalJson,
            HomeRuntimeRolloutDecisionResolver rolloutDecisions) {
        this.experiences = experiences;
        this.views = views;
        this.catalog = catalog;
        this.broker = broker;
        this.objectMapper = objectMapper;
        this.canonicalJson = canonicalJson;
        this.rolloutDecisions = rolloutDecisions;
    }

    /** Source-compatible test constructor. Production injection always supplies the resolver. */
    HomeReadModelService(
            HomeExperienceService experiences,
            EffectiveHomeViewQuery views,
            WidgetCatalogService catalog,
            WidgetRuntimeBroker broker,
            ObjectMapper objectMapper,
            HomeCanonicalJson canonicalJson) {
        this(experiences, views, catalog, broker, objectMapper, canonicalJson, null);
    }

    public HomeReadModelDtos.ReadResult read(
            HomeRuntimeContext context,
            String requestedMode,
            String deviceClass) {
        return read(
                context,
                new HomeRuntimeRolloutDecision.TrustedInput(
                        HomeRuntimeRolloutDecision.State.SHADOW_COMPARE,
                        HomeRuntimeRolloutDecision.Ring.CONTROL,
                        context.authorityDecisionRevision()),
                requestedMode,
                deviceClass);
    }

    public HomeReadModelDtos.ReadResult read(
            HomeRuntimeContext context,
            HomeRuntimeRolloutDecision.TrustedInput trustedRollout,
            String requestedMode,
            String deviceClass) {
        if (trustedRollout == null
                || trustedRollout.state() == HomeRuntimeRolloutDecision.State.DISABLED) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "Home Runtime v2 is disabled by the trusted rollout decision.");
        }
        HomeExperienceDtos.HomeExperienceResponse experience = experiences.get(context.tenantId());
        String mode = effectiveMode(experience, requestedMode);
        String device = canonicalDevice(deviceClass);
        EffectiveHomeViewQuery.EffectiveView view = views.resolve(
                context.tenantId(), context.userId(), mode, device);
        WidgetCatalogService.RuntimeCatalog runtimeCatalog = catalog.runtimeCatalog(
                context.tenantId(),
                "workspace-home",
                context.permissionsHeader(),
                context.rolesHeader(),
                context.groupsHeader(),
                mode);
        HomeRuntimeRolloutDecision decision = decision(
                context, trustedRollout, mode, runtimeCatalog);
        if (decision.state() == HomeRuntimeRolloutDecision.State.DISABLED) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "Home Runtime v2 is disabled by the current rollout decision.");
        }
        Map<String, WidgetCatalogService.RuntimeDefinition> definitions =
                definitionAliases(runtimeCatalog.definitions());
        WidgetCatalogService.RuntimeDefinition badgeDefinition = runtimeCatalog.definitions().stream()
                .filter(definition -> APP_BADGE_DEFINITION.equals(definition.definitionKey()))
                .findFirst().orElse(null);
        AppDockBadgeProjection.Projection badgeProjection = initialBadgeProjection(badgeDefinition);
        UUID badgeInstanceId = null;
        List<WidgetProviderPort.Request> providerRequests = new ArrayList<>();
        List<HomeReadModelDtos.Widget> fixed = new ArrayList<>();
        List<HomePreferenceDtos.WidgetPreference> visible = view.layout().widgets().stream()
                .filter(widget -> Boolean.TRUE.equals(widget.visible())).toList();
        for (HomePreferenceDtos.WidgetPreference preference : visible) {
            WidgetCatalogService.RuntimeDefinition definition =
                    definitions.get(preference.widgetKey());
            UUID instanceId = stableInstanceId(context, view, preference.widgetKey());
            if (definition == null) {
                fixed.add(unsupported(instanceId, preference.widgetKey()));
                continue;
            }
            if (!decision.allowedDefinitions().contains(definition.definitionKey())
                    || !decision.allowedProviders().contains(definition.ownerProductKey())) {
                fixed.add(controlled(instanceId, definition));
                continue;
            }
            if (APP_BADGE_DEFINITION.equals(definition.definitionKey())) {
                continue;
            }
            HomeReadModelDtos.Governance governance = governance(definition);
            if (definition.effectiveState() == WidgetRegistryDtos.EffectiveCatalogState.DENY) {
                fixed.add(denied(instanceId, definition, governance));
                continue;
            }
            HomeViewDtos.WidgetConfigurationPayload configuration =
                    view.widgetConfigurations().get(preference.widgetKey());
            Map<String, Object> configurationMap = configuration == null
                    ? Map.of() : objectMapper.convertValue(
                            configuration, new TypeReference<Map<String, Object>>() { });
            int configuredItemLimit = configuration == null || configuration.itemLimit() == null
                    ? 10 : configuration.itemLimit();
            int itemLimit = Math.max(1, Math.min(
                    configuredItemLimit, HomeWidgetProviderContract.MAX_ITEM_LIMIT));
            providerRequests.add(new WidgetProviderPort.Request(
                    instanceId, definition, configurationMap, itemLimit));
        }
        if (badgeDefinition != null
                && decision.allowedDefinitions().contains(badgeDefinition.definitionKey())
                && decision.allowedProviders().contains(badgeDefinition.ownerProductKey())
                && badgeDefinition.effectiveState()
                == WidgetRegistryDtos.EffectiveCatalogState.AVAILABLE) {
            governance(badgeDefinition);
            badgeInstanceId = stableInstanceId(context, view, APP_BADGE_INSTANCE_KEY);
            providerRequests.add(new WidgetProviderPort.Request(
                    badgeInstanceId,
                    badgeDefinition,
                    Map.of("projection", "APP_DOCK_BADGES_V1"),
                    HomeWidgetProviderContract.MAX_ITEM_LIMIT));
        }
        WidgetRuntimeBroker.Revisions revisions = new WidgetRuntimeBroker.Revisions(
                mode,
                device,
                Long.toString(view.revision()),
                runtimeCatalog.catalogRevision(),
                runtimeCatalog.policyRevision(),
                runtimeCatalog.safetyRevision(),
                decision.revision());
        Map<UUID, HomeWidgetProviderContract.WidgetResult> providerResults = broker.read(
                        context, revisions, providerRequests).stream()
                .collect(Collectors.toMap(
                        HomeWidgetProviderContract.WidgetResult::instanceId,
                        Function.identity()));
        HomeWidgetProviderContract.WidgetResult badgeResult = badgeInstanceId == null
                ? null : providerResults.get(badgeInstanceId);
        if (badgeInstanceId != null) {
            badgeProjection = AppDockBadgeProjection.from(badgeResult);
        }
        List<HomeReadModelDtos.Widget> widgets = new ArrayList<>(fixed);
        for (WidgetProviderPort.Request request : providerRequests) {
            if (request.instanceId().equals(badgeInstanceId)) continue;
            HomeWidgetProviderContract.WidgetResult result = providerResults.get(request.instanceId());
            if (result == null) continue;
            widgets.add(widget(request.definition(), result, decision));
        }
        Map<UUID, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < visible.size(); index++) {
            order.put(stableInstanceId(context, view, visible.get(index).widgetKey()), index);
        }
        widgets.sort(Comparator.comparingInt(widget -> order.getOrDefault(
                widget.instanceId(), Integer.MAX_VALUE)));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = widgets.stream()
                .map(widget -> widget.source().expiresAt())
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(now.plusSeconds(30));
        if (badgeResult != null && badgeResult.source() != null
                && badgeResult.source().expiresAt() != null
                && badgeResult.source().expiresAt().isBefore(expiresAt)) {
            expiresAt = badgeResult.source().expiresAt();
        }
        if (context.authorityRevalidateAt().isBefore(expiresAt)) {
            expiresAt = context.authorityRevalidateAt();
        }
        List<String> unavailableSources = new ArrayList<>(widgets.stream()
                .filter(this::technicalDegradation)
                .map(widget -> widget.source().sourceKey())
                .distinct().toList());
        if (technicalDegradation(badgeResult)) {
            unavailableSources.add(badgeResult.source().sourceKey());
        }
        unavailableSources = unavailableSources.stream().distinct().sorted().toList();
        boolean partial = !unavailableSources.isEmpty();
        HomeReadModelDtos.HomeShell shell = HomeReadModelDtos.shell(experience);
        List<HomeReadModelDtos.AppGroup> appDock = appDock(
                experience, context, badgeProjection);
        String changeVersion = changeVersion(
                context, experience, view, runtimeCatalog, widgets, shell, appDock,
                mode, device, decision);
        HomeReadModelDtos.HomeReadModel model = new HomeReadModelDtos.HomeReadModel(
                HomeReadModelDtos.SCHEMA_VERSION,
                mode,
                new HomeReadModelDtos.EffectiveView(
                        view.viewId(), view.revision(), view.source(), mode, device,
                        view.layout(), view.deviceOverlay()),
                shell,
                appDock,
                List.copyOf(widgets),
                new HomeReadModelDtos.RuntimeDecision(
                        decision.state().name(),
                        decision.mode(),
                        decision.ring().name(),
                        decision.revision(),
                        decision.commandsEnabled(),
                        decision.registryAuthoritative(),
                        decision.expiresAt()),
                now,
                expiresAt,
                partial,
                unavailableSources,
                changeVersion,
                decision.registryAuthoritative() ? "AUTHORITATIVE" : "SHADOW");
        return new HomeReadModelDtos.ReadResult(
                model, "\"" + changeVersion + "\"", decision);
    }

    private HomeRuntimeRolloutDecision decision(
            HomeRuntimeContext context,
            HomeRuntimeRolloutDecision.TrustedInput trusted,
            String mode,
            WidgetCatalogService.RuntimeCatalog runtimeCatalog) {
        if (rolloutDecisions != null) {
            return rolloutDecisions.resolve(context, trusted, mode, runtimeCatalog);
        }
        Set<String> providers = runtimeCatalog.definitions().stream()
                .filter(definition -> definition.effectiveState()
                        != WidgetRegistryDtos.EffectiveCatalogState.DENY)
                .map(WidgetCatalogService.RuntimeDefinition::ownerProductKey)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> definitions = runtimeCatalog.definitions().stream()
                .filter(definition -> definition.effectiveState()
                        != WidgetRegistryDtos.EffectiveCatalogState.DENY)
                .map(WidgetCatalogService.RuntimeDefinition::definitionKey)
                .collect(Collectors.toUnmodifiableSet());
        return new HomeRuntimeRolloutDecision(
                trusted.state(), mode, trusted.ring(), trusted.revision(), false,
                providers, definitions, Set.of(), context.authorityRevalidateAt());
    }

    private String effectiveMode(
            HomeExperienceDtos.HomeExperienceResponse experience,
            String requested) {
        String effective = MODES.contains(experience.effectiveExperienceVariant())
                ? experience.effectiveExperienceVariant() : "CLASSIC";
        if (requested == null || requested.isBlank()) return effective;
        String candidate = requested.trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(candidate)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported Home mode.");
        }
        HomeExperienceDtos.HomeCompositionPolicy policy = experience.compositionPolicy();
        boolean allowed = candidate.equals(effective)
                || policy != null
                && policy.schemaVersion() != null
                && policy.schemaVersion() >= 4
                && policy.modeLayouts() != null
                && policy.modeLayouts().containsKey(candidate);
        if (!allowed) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The requested Home mode is not enabled.");
        }
        return candidate;
    }

    private String canonicalDevice(String value) {
        if (value == null) throw new BaseException(
                ErrorCode.INVALID_INPUT_VALUE, "deviceClass is required.");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("DESKTOP_WIDE", "DESKTOP_STANDARD", "MOBILE_STANDARD", "MOBILE_COMPACT")
                .contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported Home device class.");
        }
        return normalized;
    }

    private HomeReadModelDtos.Governance governance(
            WidgetCatalogService.RuntimeDefinition definition) {
        ApprovedHomeApplicationCatalog.Application app = ApprovedHomeApplicationCatalog
                .findByResourceKey(definition.sourceAppResourceKey()).orElseThrow(() ->
                        new BaseException(ErrorCode.INVALID_STATE,
                                "Widget source application is not registered."));
        if (definition.ownerProductKey() == null || definition.ownerProductKey().isBlank()
                || definition.manifestHash() == null
                || !definition.manifestHash().matches("[0-9a-f]{64}")
                || definition.rendererBindingRevision() == null
                || definition.rendererBindingRevision().isBlank()
                || definition.rendererBindingRevision().length() > 160
                || definition.rendererKey() == null
                || definition.rendererKey().isBlank()
                || definition.requiredAuthorities() == null
                || definition.requiredAuthorities().isEmpty()
                || !CLASSIFICATIONS.contains(definition.classification())
                || !RETENTIONS.contains(definition.retention())) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "Widget governance metadata is incomplete.");
        }
        return new HomeReadModelDtos.Governance(
                definition.ownerProductKey(),
                definition.sourceAppResourceKey(),
                definition.requiredAuthorities(),
                definition.classification(),
                definition.retention(),
                ProviderResultValidator.internalRoute(app.launchTarget()));
    }

    private HomeReadModelDtos.Widget denied(
            UUID instanceId,
            WidgetCatalogService.RuntimeDefinition definition,
            HomeReadModelDtos.Governance governance) {
        boolean forbidden = definition.reasonCodes().contains("APP_ACCESS_REQUIRED");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeReadModelDtos.Widget(
                instanceId, definition.definitionKey(), definition.semanticVersion(),
                definition.manifestHash(), definition.rendererBindingRevision(),
                definition.rendererKey(), forbidden
                        ? HomeWidgetProviderContract.State.FORBIDDEN
                        : HomeWidgetProviderContract.State.UNAVAILABLE,
                new HomeReadModelDtos.SourceState(
                        WidgetRuntimeBroker.providerKey(definition.sourceAppResourceKey())
                                .toUpperCase(Locale.ROOT) + "_HOME",
                        now, now.plusSeconds(1), null, forbidden
                                ? "AUTHORIZATION_WIDGET_FORBIDDEN" : "WIDGET_POLICY_UNAVAILABLE",
                        false, null),
                Map.of(), List.of(), List.of(), governance);
    }

    private HomeReadModelDtos.Widget unsupported(UUID instanceId, String widgetKey) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeReadModelDtos.Widget(
                instanceId,
                "unsupported." + widgetKey,
                "0",
                "unsupported",
                "unsupported",
                "home.unsupported",
                HomeWidgetProviderContract.State.UNAVAILABLE,
                new HomeReadModelDtos.SourceState(
                        "WIDGET_REGISTRY", now, now.plusSeconds(1), null,
                        "DEFINITION_UNSUPPORTED", false, null),
                Map.of(), List.of(), List.of(),
                new HomeReadModelDtos.Governance(
                        "dwp-platform-home", "APP.WORK", List.of("APP.WORK:VIEW"),
                        "INTERNAL", "NONE", "/home"));
    }

    private HomeReadModelDtos.Widget controlled(
            UUID instanceId,
            WidgetCatalogService.RuntimeDefinition definition) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return new HomeReadModelDtos.Widget(
                instanceId,
                definition.definitionKey(),
                definition.semanticVersion(),
                definition.manifestHash(),
                definition.rendererBindingRevision(),
                definition.rendererKey(),
                HomeWidgetProviderContract.State.UNAVAILABLE,
                new HomeReadModelDtos.SourceState(
                        WidgetRuntimeBroker.providerKey(definition.sourceAppResourceKey())
                                .toUpperCase(Locale.ROOT) + "_HOME",
                        now,
                        now.plusSeconds(1),
                        null,
                        "RUNTIME_RENDER_DISABLED",
                        false,
                        null),
                Map.of(),
                List.of(),
                List.of(),
                governance(definition));
    }

    private HomeReadModelDtos.Widget widget(
            WidgetCatalogService.RuntimeDefinition definition,
            HomeWidgetProviderContract.WidgetResult result,
            HomeRuntimeRolloutDecision decision) {
        HomeWidgetProviderContract.SourceState source = result.source();
        List<HomeWidgetProviderContract.Action> actions = new ArrayList<>();
        for (HomeWidgetProviderContract.Action action : result.actions()) {
            if (action.kind() == HomeWidgetProviderContract.ActionKind.SOURCE_ROUTE) {
                actions.add(action);
                continue;
            }
            HomeOwnerActionContracts.find(
                            definition.definitionKey(), definition.semanticVersion(), action.actionId())
                    .filter(contract -> contract.commandKey().equals(action.commandKey()))
                    .filter(contract -> decision.actionAllowed(contract.contractId()))
                    .ifPresent(ignored -> actions.add(action));
        }
        HomeOwnerActionContracts.find(
                        definition.definitionKey(), definition.semanticVersion(),
                        HomeOwnerActionContracts.DISMISS_RECOMMENDATION.actionId())
                .filter(contract -> contract.definitionManifestHash().equals(definition.manifestHash()))
                .filter(contract -> decision.actionAllowed(contract.contractId()))
                .filter(contract -> result.state() == HomeWidgetProviderContract.State.AVAILABLE
                        || result.state() == HomeWidgetProviderContract.State.PARTIAL)
                .filter(contract -> actions.stream().noneMatch(action ->
                        contract.actionId().equals(action.actionId())))
                .ifPresent(contract -> actions.add(contract.action(source.resultVersion())));
        return new HomeReadModelDtos.Widget(
                result.instanceId(), definition.definitionKey(), definition.semanticVersion(),
                definition.manifestHash(), definition.rendererBindingRevision(),
                definition.rendererKey(), result.state(),
                new HomeReadModelDtos.SourceState(
                        source.sourceKey(), source.generatedAt(), source.expiresAt(),
                        source.lastSuccessAt(), source.reasonCode(), source.retryable(),
                        source.resultVersion()),
                result.payload(), List.copyOf(actions), result.redactions(), governance(definition));
    }

    private List<HomeReadModelDtos.AppGroup> appDock(
            HomeExperienceDtos.HomeExperienceResponse experience,
            HomeRuntimeContext context,
            AppDockBadgeProjection.Projection badges) {
        HomeExperienceDtos.HomeLaunchpadConfiguration launchpad =
                experience.launchpadConfiguration();
        Map<String, ApprovedHomeApplicationCatalog.Application> apps =
                ApprovedHomeApplicationCatalog.applications().stream().collect(Collectors.toMap(
                        ApprovedHomeApplicationCatalog.Application::resourceKey,
                        Function.identity()));
        Map<String, List<HomeExperienceDtos.HomeAppPlacement>> placements = launchpad.placements()
                .stream().collect(Collectors.groupingBy(
                        HomeExperienceDtos.HomeAppPlacement::groupKey,
                        LinkedHashMap::new,
                        Collectors.toList()));
        boolean korean = context.locale().toLowerCase(Locale.ROOT).startsWith("ko");
        return launchpad.groups().stream()
                .filter(group -> Boolean.TRUE.equals(group.enabled()))
                .sorted(Comparator.comparing(HomeExperienceDtos.HomeLaunchpadGroup::sortOrder))
                .map(group -> new HomeReadModelDtos.AppGroup(
                        groupKey(group.groupKey()),
                        localized(group.labels(), context.locale()),
                        placements.getOrDefault(group.groupKey(), List.of()).stream()
                                .sorted(Comparator.comparing(
                                        HomeExperienceDtos.HomeAppPlacement::sortOrder))
                                .map(placement -> apps.get(ApprovedHomeApplicationCatalog
                                        .canonicalResourceKey(placement.resourceKey())))
                                .filter(java.util.Objects::nonNull)
                                .filter(app -> context.has(app.resourceKey() + ":"
                                        + app.requiredPermissionCode()))
                                .map(app -> new HomeReadModelDtos.AppEntry(
                                        app.appKey(), korean ? app.nameKo() : app.nameEn(),
                                        app.iconKey(),
                                        ProviderResultValidator.internalRoute(app.launchTarget()),
                                        badges.state(app.appKey()),
                                        badges.badge(app.appKey())))
                                .toList()))
                .toList();
    }

    private AppDockBadgeProjection.Projection initialBadgeProjection(
            WidgetCatalogService.RuntimeDefinition definition) {
        if (definition == null) return AppDockBadgeProjection.notRequested();
        if (definition.effectiveState() == WidgetRegistryDtos.EffectiveCatalogState.DENY
                && definition.reasonCodes().contains("APP_ACCESS_REQUIRED")) {
            return AppDockBadgeProjection.forbidden();
        }
        return AppDockBadgeProjection.notRequested();
    }

    private Map<String, WidgetCatalogService.RuntimeDefinition> definitionAliases(
            List<WidgetCatalogService.RuntimeDefinition> definitions) {
        Map<String, WidgetCatalogService.RuntimeDefinition> aliases = new LinkedHashMap<>();
        for (WidgetCatalogService.RuntimeDefinition definition : definitions) {
            putDefinitionAlias(aliases, definition.definitionKey(), definition);
            if (definition.legacyWidgetKey() != null
                    && !definition.legacyWidgetKey().isBlank()) {
                putDefinitionAlias(aliases, definition.legacyWidgetKey(), definition);
            }
        }
        return Map.copyOf(aliases);
    }

    private void putDefinitionAlias(
            Map<String, WidgetCatalogService.RuntimeDefinition> aliases,
            String key,
            WidgetCatalogService.RuntimeDefinition definition) {
        if (key == null || key.isBlank()) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "Widget Registry definition keys are required.");
        }
        WidgetCatalogService.RuntimeDefinition previous = aliases.putIfAbsent(key, definition);
        if (previous != null && !previous.definitionId().equals(definition.definitionId())) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "Widget Registry definition aliases must be unique.");
        }
    }

    private String localized(Map<String, String> values, String locale) {
        if (values == null || values.isEmpty()) return "";
        String normalized = locale.toLowerCase(Locale.ROOT);
        String language = normalized.contains("-")
                ? normalized.substring(0, normalized.indexOf('-')) : normalized;
        return values.getOrDefault(normalized,
                values.getOrDefault(language, values.getOrDefault("en", values.values().iterator().next())));
    }

    private String groupKey(String value) {
        return switch (value) {
            case "work" -> "WORK_START";
            case "connect" -> "COLLABORATION";
            case "services" -> "PEOPLE_SERVICES";
            case "systems" -> "SYSTEM_CONTROL";
            default -> value.toUpperCase(Locale.ROOT).replace('-', '_');
        };
    }

    private UUID stableInstanceId(
            HomeRuntimeContext context,
            EffectiveHomeViewQuery.EffectiveView view,
            String widgetKey) {
        String material = context.tenantId() + ":" + context.userId() + ":"
                + view.mode() + ":" + (view.viewId() == null ? "default" : view.viewId())
                + ":" + widgetKey;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private String changeVersion(
            HomeRuntimeContext context,
            HomeExperienceDtos.HomeExperienceResponse experience,
            EffectiveHomeViewQuery.EffectiveView view,
            WidgetCatalogService.RuntimeCatalog catalog,
            List<HomeReadModelDtos.Widget> widgets,
            HomeReadModelDtos.HomeShell shell,
            List<HomeReadModelDtos.AppGroup> appDock,
            String mode,
            String device,
            HomeRuntimeRolloutDecision decision) {
        Map<String, Object> representation = new LinkedHashMap<>();
        representation.put("shell", shell);
        representation.put("appDock", appDock);
        representation.put("composition", view.layout());
        representation.put("deviceOverlay", view.deviceOverlay());
        representation.put("widgets", widgets);
        String material = context.fingerprint() + "\n" + context.locale() + "\n"
                + context.timeZone() + "\n" + mode + "\n" + device + "\n"
                + experience.version() + "\n" + view.revision() + "\n"
                + catalog.catalogRevision() + "\n" + catalog.bindingRevision() + "\n"
                + catalog.policyRevision() + "\n" + catalog.safetyRevision() + "\n"
                + decision.revision() + "\n" + decision.state() + "\n"
                + canonicalJson.fingerprint(representation);
        return sha256(material);
    }

    private boolean technicalDegradation(HomeReadModelDtos.Widget widget) {
        return widget.state() == HomeWidgetProviderContract.State.PARTIAL
                || widget.state() == HomeWidgetProviderContract.State.STALE
                || widget.state() == HomeWidgetProviderContract.State.UNAVAILABLE
                && widget.source().retryable();
    }

    private boolean technicalDegradation(HomeWidgetProviderContract.WidgetResult result) {
        return result != null && result.source() != null
                && (result.state() == HomeWidgetProviderContract.State.PARTIAL
                || result.state() == HomeWidgetProviderContract.State.STALE
                || result.state() == HomeWidgetProviderContract.State.UNAVAILABLE
                && result.source().retryable());
    }

    private String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
