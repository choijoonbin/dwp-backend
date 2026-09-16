package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.overview.HomeOverviewDtos;
import com.dwp.services.platform.home.overview.HomeOverviewService;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import com.dwp.services.platform.calendar.CalendarDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class PlatformNativeWidgetProvider implements WidgetProviderPort {

    private final HomeOverviewService overviewService;
    private final ObjectMapper objectMapper;

    public PlatformNativeWidgetProvider(
            HomeOverviewService overviewService,
            ObjectMapper objectMapper) {
        this.overviewService = overviewService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerKey() {
        return "platform";
    }

    @Override
    public HomeWidgetProviderContract.BatchResponse readBatch(
            HomeRuntimeContext context,
            List<Request> requests,
            OffsetDateTime deadline) {
        if (!deadline.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new WidgetProviderException(
                    WidgetProviderException.Kind.TIMEOUT,
                    "PROVIDER_DEADLINE_EXCEEDED",
                    "The Platform Home provider deadline elapsed before execution.");
        }
        HomeOverviewDtos.HomeOverviewResponse overview = overviewService.overview(
                context.tenantId(),
                context.userId(),
                context.personPublicId(),
                context.permissionsHeader(),
                context.rolesHeader(),
                context.locale(),
                context.timeZone(),
                context.groupsHeader());
        List<HomeWidgetProviderContract.WidgetResult> results = new ArrayList<>();
        for (Request request : requests) results.add(result(request, overview));
        return new HomeWidgetProviderContract.BatchResponse(
                HomeWidgetProviderContract.SCHEMA_VERSION,
                context.tenantId(),
                context.userId(),
                context.authorityDecisionRevision(),
                List.copyOf(results));
    }

    private HomeWidgetProviderContract.WidgetResult result(
            Request request,
            HomeOverviewDtos.HomeOverviewResponse overview) {
        String key = request.definition().legacyWidgetKey();
        HomeOverviewDtos.Section<?> section;
        boolean empty;
        switch (key) {
            case "command-rail", "focus", "focus-balance" -> {
                section = overview.work();
                WorkspaceDtos.WorkQueue data = overview.work().data();
                empty = data != null && data.items().isEmpty() && data.summary().active() == 0;
            }
            case "daily-brief" -> {
                section = overview.recommendationSection();
                empty = section.data() instanceof List<?> list && list.isEmpty();
            }
            case "schedule", "meeting-load" -> {
                section = overview.calendar();
                CalendarDtos.HomeResponse data = overview.calendar().data();
                empty = data != null && data.today().isEmpty() && data.attention().isEmpty();
            }
            case "activity" -> {
                section = overview.activity();
                WorkspaceDtos.ActivityFeed data = overview.activity().data();
                empty = data != null && data.events().isEmpty();
            }
            default -> throw new WidgetProviderException(
                    WidgetProviderException.Kind.MALFORMED,
                    "DEFINITION_NOT_SUPPORTED",
                    "The Platform provider does not own the requested definition.");
        }
        HomeWidgetProviderContract.State state = state(section.status(), empty);
        String reason = switch (state) {
            case FORBIDDEN -> "AUTHORIZATION_SOURCE_FORBIDDEN";
            case UNAVAILABLE -> section.reason() == null
                    ? "PROVIDER_UNAVAILABLE" : normalizeReason(section.reason());
            case EMPTY -> null;
            default -> section.reason() == null ? null : normalizeReason(section.reason());
        };
        OffsetDateTime generated = section.generatedAt() == null
                ? overview.generatedAt() : section.generatedAt();
        int freshness = Math.max(1, Math.min(request.definition().freshnessSeconds(), 120));
        Map<String, Object> payload = state == HomeWidgetProviderContract.State.AVAILABLE
                || state == HomeWidgetProviderContract.State.PARTIAL
                ? Map.of("data", objectMapper.convertValue(section.data(), Object.class))
                : Map.of();
        return new HomeWidgetProviderContract.WidgetResult(
                request.instanceId(),
                request.definition().definitionKey(),
                request.definition().manifestHash(),
                request.definition().rendererBindingRevision(),
                state,
                new HomeWidgetProviderContract.SourceState(
                        section.source(),
                        generated,
                        generated.plusSeconds(freshness),
                        state == HomeWidgetProviderContract.State.AVAILABLE
                                || state == HomeWidgetProviderContract.State.EMPTY
                                ? generated : null,
                        reason,
                        state == HomeWidgetProviderContract.State.UNAVAILABLE,
                        generated.toInstant().toString()),
                payload,
                List.of(),
                List.of());
    }

    private HomeWidgetProviderContract.State state(
            HomeOverviewDtos.SectionStatus status,
            boolean empty) {
        return switch (status) {
            case FORBIDDEN -> HomeWidgetProviderContract.State.FORBIDDEN;
            case UNAVAILABLE -> HomeWidgetProviderContract.State.UNAVAILABLE;
            case AVAILABLE -> empty
                    ? HomeWidgetProviderContract.State.EMPTY
                    : HomeWidgetProviderContract.State.AVAILABLE;
        };
    }

    private String normalizeReason(String value) {
        String normalized = value.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9_.-]", "_");
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }
}
