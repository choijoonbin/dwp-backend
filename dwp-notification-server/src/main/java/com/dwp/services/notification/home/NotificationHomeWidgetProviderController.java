package com.dwp.services.notification.home;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestCodec;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestException;
import com.dwp.platform.contract.home.HomeWidgetProviderResponses;
import com.dwp.platform.contract.home.HomeWidgetProviderSecurity;
import com.dwp.services.notification.domain.NotificationAppSummaryModels;
import com.dwp.services.notification.domain.NotificationAppSummaryService;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.dwp.services.notification.common.NotificationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Content-free authoritative counters for Home launcher and response surfaces. */
@RestController
@Hidden
public class NotificationHomeWidgetProviderController {

    static final Set<String> DEFINITIONS = Set.of(
            "notification.app-badges", "notification.response-queue");
    private static final String SOURCE = "NOTIFICATION_APP_SUMMARY";
    private static final String ROUTE = "/notifications/home";

    private final NotificationAppSummaryService service;
    private final ObjectMapper objectMapper;
    private final String serviceToken;

    public NotificationHomeWidgetProviderController(
            NotificationAppSummaryService service,
            ObjectMapper objectMapper,
            @Value("${dwp.notification.home-runtime-service-token:}") String serviceToken) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.serviceToken = serviceToken;
    }

    @PostMapping(value = HomeWidgetProviderContract.BATCH_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public HomeWidgetProviderContract.BatchResponse batch(
            @RequestBody JsonNode raw,
            HttpServletRequest request) {
        HomeWidgetProviderSecurity.Context context = authorize(request);
        HomeWidgetProviderContract.BatchRequest batch =
                HomeWidgetProviderRequestCodec.decode(raw, objectMapper);
        requireOwned(batch);
        NotificationAppSummaryModels.AppNotificationSummary summary = null;
        RuntimeException failure = null;
        boolean denied = false;
        if (context.has("APP.NOTIFICATIONS", "VIEW")) {
            try {
                summary = service.summary(new NotificationRequestContext.Actor(
                        context.tenantId(), context.userId(), context.roles(),
                        context.permissions(), false,
                        HomeWidgetProviderSecurity.TRUSTED_SERVICE_IDENTITY));
            } catch (RuntimeException exception) {
                failure = exception;
                denied = isDenied(exception);
            }
        }
        List<HomeWidgetProviderContract.WidgetResult> results = new ArrayList<>();
        for (HomeWidgetProviderContract.WidgetRequest widget : batch.widgets()) {
            results.add(result(context, widget, summary, failure, denied));
        }
        return new HomeWidgetProviderContract.BatchResponse(
                HomeWidgetProviderContract.SCHEMA_VERSION, context.tenantId(),
                context.userId(), context.authorityDecisionRevision(), results);
    }

    @PostMapping(value = HomeWidgetProviderContract.COMMAND_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> command(HttpServletRequest request) {
        authorize(request);
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "reasonCode", "HOME_PROVIDER_COMMAND_NOT_DECLARED",
                "message", "Notification Home widgets expose source routes only."));
    }

    private HomeWidgetProviderContract.WidgetResult result(
            HomeWidgetProviderSecurity.Context context,
            HomeWidgetProviderContract.WidgetRequest widget,
            NotificationAppSummaryModels.AppNotificationSummary summary,
            RuntimeException failure,
            boolean denied) {
        if (!context.has("APP.NOTIFICATIONS", "VIEW")) {
            return HomeWidgetProviderResponses.forbidden(
                    widget, SOURCE, "AUTHORIZATION_NOTIFICATION_REQUIRED");
        }
        if (denied) return HomeWidgetProviderResponses.forbidden(
                widget, SOURCE, "AUTHORIZATION_NOTIFICATION_SOURCE_REVOKED");
        if (failure != null || summary == null) {
            return HomeWidgetProviderResponses.unavailable(
                    widget, SOURCE, "PROVIDER_NOTIFICATION_PROJECTION_UNAVAILABLE");
        }
        if (!summary.partial() && summary.apps().isEmpty()) {
            return HomeWidgetProviderResponses.empty(widget, SOURCE, ROUTE);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("counterVersion", summary.counterVersion());
        payload.put("items", summary.apps().stream().limit(widget.itemLimit())
                .map(this::counter).toList());
        if (summary.partial()) {
            List<String> redactions = summary.unavailableSources().isEmpty()
                    ? List.of("NOTIFICATION_SOURCE_UNAVAILABLE")
                    : summary.unavailableSources();
            return HomeWidgetProviderResponses.partial(
                    widget, SOURCE, payload, ROUTE,
                    "PROVIDER_NOTIFICATION_PARTIAL", redactions);
        }
        return HomeWidgetProviderResponses.available(widget, SOURCE, payload, ROUTE);
    }

    private boolean isDenied(RuntimeException failure) {
        return failure instanceof NotificationException exception
                && exception.errorCode().status().is4xxClientError();
    }

    private Map<String, Object> counter(
            NotificationAppSummaryModels.AppNotificationCounter value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("appKey", value.appKey());
        item.put("totalUnread", value.totalUnread());
        item.put("actionableUnread", value.actionableUnread());
        item.put("urgentUnread", value.urgentUnread());
        item.put("lastActivityAt", value.lastActivityAt().toString());
        return item;
    }

    private void requireOwned(HomeWidgetProviderContract.BatchRequest batch) {
        if (batch.widgets().stream().anyMatch(widget ->
                !DEFINITIONS.contains(widget.definitionKey()))) {
            throw new HomeWidgetProviderRequestException(
                    HomeWidgetProviderRequestException.Kind.BAD_REQUEST,
                    "HOME_PROVIDER_DEFINITION_NOT_OWNED",
                    "Notification does not own a requested widget definition.");
        }
    }

    private HomeWidgetProviderSecurity.Context authorize(HttpServletRequest request) {
        return HomeWidgetProviderSecurity.authorize(serviceToken, evidence(request));
    }

    private HomeWidgetProviderSecurity.Evidence evidence(HttpServletRequest request) {
        return new HomeWidgetProviderSecurity.Evidence(
                request.getHeader(HomeWidgetProviderContract.SERVICE_IDENTITY_HEADER),
                request.getHeader(HomeWidgetProviderContract.SERVICE_TOKEN_HEADER),
                request.getHeader("X-DWP-Tenant-ID"), request.getHeader("X-DWP-User-ID"),
                request.getHeader("X-DWP-Person-Public-ID"),
                request.getHeader("X-DWP-Permissions"), request.getHeader("X-DWP-Roles"),
                request.getHeader("X-DWP-Group-Refs"),
                request.getHeader(HomeWidgetProviderContract.AUTHORITY_REVISION_HEADER),
                request.getHeader("X-DWP-Current-Revalidate-At"),
                request.getHeader("X-DWP-Home-Deadline-At"),
                request.getHeader("Accept-Language"), request.getHeader("Authorization"),
                request.getHeader("Cookie"), request.getHeader("X-DWP-Support-Session-ID"),
                request.getHeader("X-DWP-Provider-Tenant-ID"),
                request.getHeader("X-DWP-Actor-Tenant-ID"), duplicateHeaders(request));
    }

    private boolean duplicateHeaders(HttpServletRequest request) {
        return List.of(HomeWidgetProviderContract.SERVICE_IDENTITY_HEADER,
                        HomeWidgetProviderContract.SERVICE_TOKEN_HEADER,
                        HomeWidgetProviderContract.AUTHORITY_REVISION_HEADER,
                        "X-DWP-Tenant-ID", "X-DWP-User-ID", "X-DWP-Person-Public-ID",
                        "X-DWP-Permissions", "X-DWP-Roles", "X-DWP-Group-Refs",
                        "X-DWP-Current-Revalidate-At", "X-DWP-Home-Deadline-At")
                .stream().anyMatch(name -> java.util.Collections.list(request.getHeaders(name)).size() > 1);
    }

    @ExceptionHandler(HomeWidgetProviderRequestException.class)
    ResponseEntity<Map<String, String>> requestFailure(HomeWidgetProviderRequestException failure) {
        HttpStatus status = switch (failure.kind()) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(Map.of(
                "reasonCode", failure.reasonCode(), "message", failure.getMessage()));
    }
}
