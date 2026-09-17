package com.dwp.services.space.home;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestCodec;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestException;
import com.dwp.platform.contract.home.HomeWidgetProviderResponses;
import com.dwp.platform.contract.home.HomeWidgetProviderSecurity;
import com.dwp.services.space.domain.SpaceDtos;
import com.dwp.services.space.domain.SpaceService;
import com.dwp.services.space.security.SpaceRequestContext;
import com.dwp.core.exception.BaseException;
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

/** Space-owned ACL-filtered collaboration projections for Home. */
@RestController
@Hidden
public class SpaceHomeWidgetProviderController {

    static final Set<String> DEFINITIONS = Set.of(
            "space.change-feed", "space.response-queue");
    private static final String SOURCE = "SPACE_HOME";
    private static final String ROUTE = "/spaces/home";

    private final SpaceService service;
    private final ObjectMapper objectMapper;
    private final String serviceToken;

    public SpaceHomeWidgetProviderController(
            SpaceService service,
            ObjectMapper objectMapper,
            @Value("${dwp.space.home-runtime-service-token:}") String serviceToken) {
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
        SpaceDtos.HomeResponse home = null;
        RuntimeException failure = null;
        boolean denied = false;
        if (context.has("APP.SPACES", "VIEW")) {
            try {
                SpaceRequestContext.set(new SpaceRequestContext.Subject(
                        context.userId(), context.tenantId(), context.personPublicId(), null,
                        context.roles(), context.permissions(), context.groups()));
                home = service.home();
            } catch (RuntimeException exception) {
                failure = exception;
                denied = isDenied(exception);
            } finally {
                SpaceRequestContext.clear();
            }
        }
        List<HomeWidgetProviderContract.WidgetResult> results = new ArrayList<>();
        for (HomeWidgetProviderContract.WidgetRequest widget : batch.widgets()) {
            results.add(result(context, widget, home, failure, denied));
        }
        return response(context, results);
    }

    @PostMapping(value = HomeWidgetProviderContract.COMMAND_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> command(HttpServletRequest request) {
        authorize(request);
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "reasonCode", "HOME_PROVIDER_COMMAND_NOT_DECLARED",
                "message", "Space Home widgets expose source routes only."));
    }

    private HomeWidgetProviderContract.WidgetResult result(
            HomeWidgetProviderSecurity.Context context,
            HomeWidgetProviderContract.WidgetRequest widget,
            SpaceDtos.HomeResponse home,
            RuntimeException failure,
            boolean denied) {
        if (!context.has("APP.SPACES", "VIEW")) {
            return HomeWidgetProviderResponses.forbidden(
                    widget, SOURCE, "AUTHORIZATION_SPACE_REQUIRED");
        }
        if (denied) return HomeWidgetProviderResponses.forbidden(
                widget, SOURCE, "AUTHORIZATION_SPACE_SOURCE_REVOKED");
        if (failure != null || home == null) {
            return HomeWidgetProviderResponses.unavailable(
                    widget, SOURCE, "PROVIDER_SPACE_PROJECTION_UNAVAILABLE");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("space.change-feed".equals(widget.definitionKey())) {
            if (home.recentActivity().isEmpty() && home.metrics().unreadSignals() == 0) {
                return HomeWidgetProviderResponses.empty(widget, SOURCE, ROUTE);
            }
            payload.put("unreadSignals", home.metrics().unreadSignals());
            payload.put("items", home.recentActivity().stream().limit(widget.itemLimit())
                    .map(this::activity).toList());
        } else {
            if (home.focusSpaces().isEmpty()
                    && home.metrics().pendingRequests() == 0
                    && home.metrics().reviewQueue() == 0) {
                return HomeWidgetProviderResponses.empty(widget, SOURCE, ROUTE);
            }
            payload.put("pendingRequests", home.metrics().pendingRequests());
            payload.put("reviewQueue", home.metrics().reviewQueue());
            payload.put("items", home.focusSpaces().stream().limit(widget.itemLimit())
                    .map(this::space).toList());
        }
        return HomeWidgetProviderResponses.available(widget, SOURCE, payload, ROUTE);
    }

    private boolean isDenied(RuntimeException failure) {
        return failure instanceof BaseException exception
                && exception.getErrorCode().getHttpStatus().is4xxClientError();
    }

    private Map<String, Object> activity(SpaceDtos.ActivitySummary value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", value.activityId().toString());
        item.put("spaceKey", value.spaceKey());
        item.put("spaceNameKo", value.spaceNameKo());
        item.put("spaceNameEn", value.spaceNameEn());
        item.put("activityType", value.activityType());
        item.put("titleKo", value.titleKo());
        item.put("titleEn", value.titleEn());
        item.put("occurredAt", value.occurredAt().toString());
        return item;
    }

    private Map<String, Object> space(SpaceDtos.SpaceSummary value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", value.spaceId().toString());
        item.put("spaceKey", value.spaceKey());
        item.put("nameKo", value.nameKo());
        item.put("nameEn", value.nameEn());
        item.put("memberRole", value.memberRole());
        item.put("unreadCount", value.unreadCount());
        item.put("version", value.version());
        return item;
    }

    private void requireOwned(HomeWidgetProviderContract.BatchRequest batch) {
        if (batch.widgets().stream().anyMatch(widget ->
                !DEFINITIONS.contains(widget.definitionKey()))) {
            throw new HomeWidgetProviderRequestException(
                    HomeWidgetProviderRequestException.Kind.BAD_REQUEST,
                    "HOME_PROVIDER_DEFINITION_NOT_OWNED",
                    "Space does not own a requested widget definition.");
        }
    }

    private HomeWidgetProviderContract.BatchResponse response(
            HomeWidgetProviderSecurity.Context context,
            List<HomeWidgetProviderContract.WidgetResult> results) {
        return new HomeWidgetProviderContract.BatchResponse(
                HomeWidgetProviderContract.SCHEMA_VERSION, context.tenantId(),
                context.userId(), context.authorityDecisionRevision(), results);
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
