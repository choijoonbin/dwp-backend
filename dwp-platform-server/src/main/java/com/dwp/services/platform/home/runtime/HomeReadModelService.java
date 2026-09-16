package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.ApprovedHomeApplicationCatalog;
import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.HomeExperienceService;
import com.dwp.services.platform.home.personalization.EffectiveHomeViewQuery;
import com.dwp.services.platform.home.personalization.HomeViewDtos;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final Set<String> MODES = Set.of("CLASSIC", "FLOW_V1");
    private static final Set<String> CLASSIFICATIONS = Set.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> RETENTIONS = Set.of("NONE", "SESSION", "SHORT_LIVED");

    private final HomeExperienceService experiences;
    private final EffectiveHomeViewQuery views;
    private final WidgetCatalogService catalog;
    private final WidgetRuntimeBroker broker;
    private final ObjectMapper objectMapper;

    public HomeReadModelService(
            HomeExperienceService experiences,
            EffectiveHomeViewQuery views,
            WidgetCatalogService catalog,
            WidgetRuntimeBroker broker,
            ObjectMapper objectMapper) {
        this.experiences = experiences;
        this.views = views;
        this.catalog = catalog;
        this.broker = broker;
        this.objectMapper = objectMapper;
    }

    public HomeReadModelDtos.ReadResult read(
            HomeRuntimeContext context,
            String requestedMode,
            String deviceClass) {
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
        Map<String, WidgetCatalogService.RuntimeDefinition> definitions =
                runtimeCatalog.definitions().stream().collect(Collectors.toMap(
                        WidgetCatalogService.RuntimeDefinition::legacyWidgetKey,
                        Function.identity()));
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
            int itemLimit = configuration == null || configuration.itemLimit() == null
                    ? 10 : configuration.itemLimit();
            providerRequests.add(new WidgetProviderPort.Request(
                    instanceId, definition, configurationMap, itemLimit));
        }
        WidgetRuntimeBroker.Revisions revisions = new WidgetRuntimeBroker.Revisions(
                mode,
                device,
                Long.toString(view.revision()),
                runtimeCatalog.catalogRevision(),
                runtimeCatalog.policyRevision(),
                runtimeCatalog.safetyRevision());
        Map<UUID, HomeWidgetProviderContract.WidgetResult> providerResults = broker.read(
                        context, revisions, providerRequests).stream()
                .collect(Collectors.toMap(
                        HomeWidgetProviderContract.WidgetResult::instanceId,
                        Function.identity()));
        List<HomeReadModelDtos.Widget> widgets = new ArrayList<>(fixed);
        for (WidgetProviderPort.Request request : providerRequests) {
            HomeWidgetProviderContract.WidgetResult result = providerResults.get(request.instanceId());
            if (result == null) continue;
            widgets.add(widget(request.definition(), result));
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
        if (context.authorityRevalidateAt().isBefore(expiresAt)) {
            expiresAt = context.authorityRevalidateAt();
        }
        List<String> unavailableSources = widgets.stream()
                .filter(widget -> technicalDegradation(widget.state()))
                .map(widget -> widget.source().sourceKey())
                .distinct().sorted().toList();
        boolean partial = !unavailableSources.isEmpty();
        String changeVersion = changeVersion(
                context, experience, view, runtimeCatalog, widgets, mode, device);
        HomeReadModelDtos.HomeReadModel model = new HomeReadModelDtos.HomeReadModel(
                HomeReadModelDtos.SCHEMA_VERSION,
                mode,
                new HomeReadModelDtos.EffectiveView(
                        view.viewId(), view.revision(), view.source(), mode, device,
                        view.layout(), view.deviceOverlay()),
                HomeReadModelDtos.shell(experience),
                appDock(experience, context),
                List.copyOf(widgets),
                now,
                expiresAt,
                partial,
                unavailableSources,
                changeVersion,
                runtimeCatalog.registryMode());
        return new HomeReadModelDtos.ReadResult(model, "\"" + changeVersion + "\"");
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
                || definition.requiredAuthorities() == null
                || definition.requiredAuthorities().isEmpty()
                || !CLASSIFICATIONS.contains(definition.classification())
                || !RETENTIONS.contains(definition.retention())) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "Widget governance metadata is incomplete.");
        }
        return new HomeReadModelDtos.Governance(
                definition.ownerProductKey(),
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
                        now, now, null, forbidden
                                ? "AUTHORIZATION_WIDGET_FORBIDDEN" : "WIDGET_POLICY_UNAVAILABLE",
                        !forbidden, null),
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
                        "WIDGET_REGISTRY", now, now, null,
                        "DEFINITION_UNSUPPORTED", false, null),
                Map.of(), List.of(), List.of(),
                new HomeReadModelDtos.Governance(
                        "dwp-platform-home", List.of("APP.WORK:VIEW"),
                        "INTERNAL", "NONE", "/home"));
    }

    private HomeReadModelDtos.Widget widget(
            WidgetCatalogService.RuntimeDefinition definition,
            HomeWidgetProviderContract.WidgetResult result) {
        HomeWidgetProviderContract.SourceState source = result.source();
        return new HomeReadModelDtos.Widget(
                result.instanceId(), definition.definitionKey(), definition.semanticVersion(),
                definition.manifestHash(), definition.rendererBindingRevision(),
                definition.rendererKey(), result.state(),
                new HomeReadModelDtos.SourceState(
                        source.sourceKey(), source.generatedAt(), source.expiresAt(),
                        source.lastSuccessAt(), source.reasonCode(), source.retryable(),
                        source.resultVersion()),
                result.payload(), result.actions(), result.redactions(), governance(definition));
    }

    private List<HomeReadModelDtos.AppGroup> appDock(
            HomeExperienceDtos.HomeExperienceResponse experience,
            HomeRuntimeContext context) {
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
                                        HomeReadModelDtos.BadgeState.NOT_REQUESTED,
                                        null))
                                .toList()))
                .toList();
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
            String mode,
            String device) {
        String material = context.fingerprint() + "\n" + mode + "\n" + device + "\n"
                + experience.version() + "\n" + view.revision() + "\n"
                + catalog.catalogRevision() + "\n" + catalog.bindingRevision() + "\n"
                + catalog.policyRevision() + "\n" + catalog.safetyRevision() + "\n"
                + widgets.stream().map(widget -> widget.instanceId() + ":" + widget.state() + ":"
                        + widget.source().resultVersion()).collect(Collectors.joining("\n"));
        return sha256(material);
    }

    private boolean technicalDegradation(HomeWidgetProviderContract.State state) {
        return state == HomeWidgetProviderContract.State.PARTIAL
                || state == HomeWidgetProviderContract.State.UNAVAILABLE
                || state == HomeWidgetProviderContract.State.STALE;
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
