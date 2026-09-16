package com.dwp.services.people.home;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestCodec;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestException;
import com.dwp.platform.contract.home.HomeWidgetProviderResponses;
import com.dwp.platform.contract.home.HomeWidgetProviderSecurity;
import com.dwp.services.people.hr.HrDtos;
import com.dwp.services.people.hr.HrService;
import com.dwp.services.people.security.PeopleRequestContext;
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

/** HCM-owned learning and tightly authorized workforce pulse projections for Home. */
@RestController
@Hidden
public class PeopleHomeWidgetProviderController {

    static final Set<String> DEFINITIONS = Set.of("hr.edu", "hr.team-pulse");
    private static final String SOURCE = "HCM_HOME";
    private static final String ROUTE = "/hr";

    private final HrService service;
    private final ObjectMapper objectMapper;
    private final String serviceToken;

    public PeopleHomeWidgetProviderController(
            HrService service,
            ObjectMapper objectMapper,
            @Value("${dwp.people.home-runtime-service-token:}") String serviceToken) {
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
        HrDtos.HomeOverview home = null;
        RuntimeException failure = null;
        boolean denied = false;
        if (hasHcm(context)) {
            try {
                PeopleRequestContext.set(
                        context.userId(), context.tenantId(), context.personPublicId(),
                        context.roles(), context.permissions());
                home = service.home();
            } catch (RuntimeException exception) {
                failure = exception;
                denied = isDenied(exception);
            } finally {
                PeopleRequestContext.clear();
            }
        }
        List<HomeWidgetProviderContract.WidgetResult> results = new ArrayList<>();
        for (HomeWidgetProviderContract.WidgetRequest widget : batch.widgets()) {
            results.add(result(context, widget, home, failure, denied));
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
                "message", "HCM Home widgets expose source routes only."));
    }

    private HomeWidgetProviderContract.WidgetResult result(
            HomeWidgetProviderSecurity.Context context,
            HomeWidgetProviderContract.WidgetRequest widget,
            HrDtos.HomeOverview home,
            RuntimeException failure,
            boolean denied) {
        if (!hasHcm(context)) {
            return HomeWidgetProviderResponses.forbidden(
                    widget, SOURCE, "AUTHORIZATION_HCM_REQUIRED");
        }
        if ("hr.team-pulse".equals(widget.definitionKey())
                && !context.has("DATA.WORKFORCE", "VIEW", "MANAGE")) {
            return HomeWidgetProviderResponses.forbidden(
                    widget, SOURCE, "AUTHORIZATION_WORKFORCE_SCOPE_REQUIRED");
        }
        if (denied) return HomeWidgetProviderResponses.forbidden(
                widget, SOURCE, "AUTHORIZATION_HCM_SOURCE_REVOKED");
        if (failure != null || home == null) {
            return HomeWidgetProviderResponses.unavailable(
                    widget, SOURCE, "PROVIDER_HCM_PROJECTION_UNAVAILABLE");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("hr.edu".equals(widget.definitionKey())) {
            if (home.requiredLearningCount() == 0 && home.activeGoalCount() == 0) {
                return HomeWidgetProviderResponses.empty(widget, SOURCE, ROUTE);
            }
            payload.put("requiredLearningCount", home.requiredLearningCount());
            payload.put("activeGoalCount", home.activeGoalCount());
            payload.put("state", domain(home, "TALENT"));
        } else {
            if (home.teamPendingCount() == 0
                    && home.teamTimePendingCount() == 0
                    && home.teamAbsencePendingCount() == 0) {
                return HomeWidgetProviderResponses.empty(widget, SOURCE, ROUTE);
            }
            payload.put("teamPendingCount", home.teamPendingCount());
            payload.put("teamTimePendingCount", home.teamTimePendingCount());
            payload.put("teamAbsencePendingCount", home.teamAbsencePendingCount());
            payload.put("state", domain(home, "TEAM"));
        }
        return HomeWidgetProviderResponses.available(widget, SOURCE, payload, ROUTE);
    }

    private boolean isDenied(RuntimeException failure) {
        return failure instanceof BaseException exception
                && exception.getErrorCode().getHttpStatus().is4xxClientError();
    }

    private Map<String, Object> domain(HrDtos.HomeOverview home, String key) {
        HrDtos.HomeDomainState state = home.domainStates().get(key);
        if (state == null) return Map.of("availability", "UNAVAILABLE");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("availability", state.availability().name());
        value.put("dataOrigin", state.dataOrigin().name());
        if (state.reasonCode() != null) value.put("reasonCode", state.reasonCode());
        return value;
    }

    private boolean hasHcm(HomeWidgetProviderSecurity.Context context) {
        return context.has("APP.HCM", "VIEW", "MANAGE")
                || context.has("APP.HRIS", "VIEW", "MANAGE");
    }

    private void requireOwned(HomeWidgetProviderContract.BatchRequest batch) {
        if (batch.widgets().stream().anyMatch(widget ->
                !DEFINITIONS.contains(widget.definitionKey()))) {
            throw new HomeWidgetProviderRequestException(
                    HomeWidgetProviderRequestException.Kind.BAD_REQUEST,
                    "HOME_PROVIDER_DEFINITION_NOT_OWNED",
                    "People does not own a requested widget definition.");
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
