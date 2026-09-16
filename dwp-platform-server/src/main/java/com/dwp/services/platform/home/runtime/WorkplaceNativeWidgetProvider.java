package com.dwp.services.platform.home.runtime;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.workplace.WorkplaceDtos;
import com.dwp.services.platform.workplace.WorkplaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Recipient-bound projection of the existing Workplace booking service. */
@Component
public class WorkplaceNativeWidgetProvider implements WidgetProviderPort {

    private final WorkplaceService workplace;
    private final ObjectMapper objectMapper;
    private final HomeCanonicalJson canonicalJson;

    public WorkplaceNativeWidgetProvider(
            WorkplaceService workplace,
            ObjectMapper objectMapper,
            HomeCanonicalJson canonicalJson) {
        this.workplace = workplace;
        this.objectMapper = objectMapper;
        this.canonicalJson = canonicalJson;
    }

    @Override
    public String providerKey() {
        return "workplace";
    }

    @Override
    public HomeWidgetProviderContract.BatchResponse readBatch(
            HomeRuntimeContext context,
            List<Request> requests,
            OffsetDateTime deadline) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!deadline.isAfter(now)) {
            throw new WidgetProviderException(
                    WidgetProviderException.Kind.TIMEOUT,
                    "PROVIDER_DEADLINE_EXCEEDED",
                    "The Workplace Home provider deadline elapsed before execution.");
        }
        int maximumItems = requests.stream().mapToInt(Request::itemLimit).max().orElse(1);
        List<WorkplaceDtos.Booking> bookings = workplace.myBookings(
                context.tenantId(), context.userId(), now.minusHours(1), now.plusDays(7),
                context.locale(), context.groupsHeader());
        List<WorkplaceDtos.Booking> projected = bookings.stream()
                .filter(booking -> booking.endsAt().isAfter(now))
                .limit(Math.max(1, Math.min(maximumItems,
                        HomeWidgetProviderContract.MAX_ITEM_LIMIT)))
                .toList();
        String resultVersion = canonicalJson.fingerprint(projected);
        List<HomeWidgetProviderContract.WidgetResult> results = new ArrayList<>();
        for (Request request : requests) {
            if (!"APP.WORKPLACE".equals(request.definition().sourceAppResourceKey())) {
                throw new WidgetProviderException(
                        WidgetProviderException.Kind.MALFORMED,
                        "DEFINITION_NOT_SUPPORTED",
                        "The Workplace provider does not own the requested definition.");
            }
            List<WorkplaceDtos.Booking> items = projected.stream()
                    .limit(Math.max(1, Math.min(request.itemLimit(),
                            HomeWidgetProviderContract.MAX_ITEM_LIMIT)))
                    .toList();
            HomeWidgetProviderContract.State state = items.isEmpty()
                    ? HomeWidgetProviderContract.State.EMPTY
                    : HomeWidgetProviderContract.State.AVAILABLE;
            int freshness = Math.max(1, Math.min(request.definition().freshnessSeconds(), 120));
            Map<String, Object> payload = items.isEmpty()
                    ? Map.of()
                    : Map.of(
                            "items", objectMapper.convertValue(items, Object.class),
                            "visibleCount", items.size());
            List<HomeWidgetProviderContract.Action> actions = items.isEmpty()
                    ? List.of()
                    : List.of(new HomeWidgetProviderContract.Action(
                            "open-workplace", "home.action.openWorkplace",
                            HomeWidgetProviderContract.ActionKind.SOURCE_ROUTE,
                            "/workplace/home", null, null, true));
            results.add(new HomeWidgetProviderContract.WidgetResult(
                    request.instanceId(), request.definition().definitionKey(),
                    request.definition().manifestHash(),
                    request.definition().rendererBindingRevision(), state,
                    new HomeWidgetProviderContract.SourceState(
                            "WORKPLACE_HOME", now, now.plusSeconds(freshness), now,
                            null, false, resultVersion),
                    payload, actions, List.of()));
        }
        return new HomeWidgetProviderContract.BatchResponse(
                HomeWidgetProviderContract.SCHEMA_VERSION,
                context.tenantId(), context.userId(),
                context.authorityDecisionRevision(), List.copyOf(results));
    }
}
