package com.dwp.services.approval.home;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestCodec;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestException;
import com.dwp.platform.contract.home.HomeWidgetProviderResponses;
import com.dwp.platform.contract.home.HomeWidgetProviderSecurity;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalService;
import com.dwp.services.approval.security.ApprovalRequestContext;
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

/** Recipient-bound Home projections owned and reauthorized by Approval. */
@RestController
@Hidden
public class ApprovalHomeWidgetProviderController {

    static final Set<String> DEFINITIONS = Set.of(
            "approval.focus-queue", "approval.my-requests");
    private static final String SOURCE = "APPROVAL_HOME";
    private static final String ROUTE = "/approvals/home";

    private final ApprovalService service;
    private final ObjectMapper objectMapper;
    private final String serviceToken;

    public ApprovalHomeWidgetProviderController(
            ApprovalService service,
            ObjectMapper objectMapper,
            @Value("${dwp.approval.home-runtime-service-token:}") String serviceToken) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.serviceToken = serviceToken;
    }

    @PostMapping(
            value = HomeWidgetProviderContract.BATCH_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public HomeWidgetProviderContract.BatchResponse batch(
            @RequestBody JsonNode raw,
            HttpServletRequest request) {
        HomeWidgetProviderSecurity.Context context = authorize(request);
        HomeWidgetProviderContract.BatchRequest batch =
                HomeWidgetProviderRequestCodec.decode(raw, objectMapper);
        requireOwned(batch);
        ApprovalDtos.HomeResponse home = null;
        RuntimeException failure = null;
        boolean denied = false;
        try {
            ApprovalRequestContext.set(
                    context.userId(), context.tenantId(), context.personPublicId(),
                    context.roles(), context.permissions());
            home = service.home();
        } catch (RuntimeException exception) {
            failure = exception;
            denied = isDenied(exception);
        } finally {
            ApprovalRequestContext.clear();
        }
        List<HomeWidgetProviderContract.WidgetResult> results = new ArrayList<>();
        for (HomeWidgetProviderContract.WidgetRequest widget : batch.widgets()) {
            results.add(result(context, widget, home, failure, denied));
        }
        return new HomeWidgetProviderContract.BatchResponse(
                HomeWidgetProviderContract.SCHEMA_VERSION,
                context.tenantId(), context.userId(),
                context.authorityDecisionRevision(), results);
    }

    @PostMapping(
            value = HomeWidgetProviderContract.COMMAND_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> command(HttpServletRequest request) {
        authorize(request);
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "reasonCode", "HOME_PROVIDER_COMMAND_NOT_DECLARED",
                "message", "Approval Home widgets expose source routes only."));
    }

    private HomeWidgetProviderContract.WidgetResult result(
            HomeWidgetProviderSecurity.Context context,
            HomeWidgetProviderContract.WidgetRequest widget,
            ApprovalDtos.HomeResponse home,
            RuntimeException failure,
            boolean denied) {
        boolean task = "approval.focus-queue".equals(widget.definitionKey());
        String permission = task ? "ACTION.APPROVAL_TASK" : "ACTION.APPROVAL_REQUEST";
        if (!context.has("APP.APPROVALS", "VIEW")
                || !context.has(permission, "VIEW", "MANAGE")) {
            return HomeWidgetProviderResponses.forbidden(
                    widget, SOURCE, "AUTHORIZATION_APPROVAL_REQUIRED");
        }
        if (denied) return HomeWidgetProviderResponses.forbidden(
                widget, SOURCE, "AUTHORIZATION_APPROVAL_SOURCE_REVOKED");
        if (failure != null || home == null) {
            return HomeWidgetProviderResponses.unavailable(
                    widget, SOURCE, "PROVIDER_APPROVAL_PROJECTION_UNAVAILABLE");
        }
        int limit = widget.itemLimit();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (task) {
            if (home.focusQueue().isEmpty()
                    && home.metrics().pending() == 0
                    && home.metrics().dueToday() == 0
                    && home.metrics().overdue() == 0) {
                return HomeWidgetProviderResponses.empty(widget, SOURCE, ROUTE);
            }
            payload.put("pendingCount", home.metrics().pending());
            payload.put("dueTodayCount", home.metrics().dueToday());
            payload.put("overdueCount", home.metrics().overdue());
            payload.put("items", home.focusQueue().stream().limit(limit)
                    .map(this::task).toList());
        } else {
            if (home.recentRequests().isEmpty()
                    && home.metrics().myRequestsInFlight() == 0) {
                return HomeWidgetProviderResponses.empty(widget, SOURCE, ROUTE);
            }
            payload.put("inFlightCount", home.metrics().myRequestsInFlight());
            payload.put("items", home.recentRequests().stream().limit(limit)
                    .map(this::request).toList());
        }
        return HomeWidgetProviderResponses.available(widget, SOURCE, payload, ROUTE);
    }

    private boolean isDenied(RuntimeException failure) {
        return failure instanceof BaseException exception
                && exception.getErrorCode().getHttpStatus().is4xxClientError();
    }

    private Map<String, Object> task(ApprovalDtos.TaskSummary value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", value.taskId().toString());
        item.put("requestNumber", value.requestNumber());
        item.put("title", value.title());
        item.put("status", value.status());
        item.put("priority", value.priority());
        if (value.dueAt() != null) item.put("dueAt", value.dueAt().toString());
        item.put("version", value.version());
        return item;
    }

    private Map<String, Object> request(ApprovalDtos.RequestSummary value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", value.requestId().toString());
        item.put("requestNumber", value.requestNumber());
        item.put("title", value.title());
        item.put("status", value.status());
        item.put("priority", value.priority());
        if (value.dueAt() != null) item.put("dueAt", value.dueAt().toString());
        item.put("version", value.version());
        return item;
    }

    private void requireOwned(HomeWidgetProviderContract.BatchRequest batch) {
        if (batch.widgets().stream().anyMatch(widget ->
                !DEFINITIONS.contains(widget.definitionKey()))) {
            throw new HomeWidgetProviderRequestException(
                    HomeWidgetProviderRequestException.Kind.BAD_REQUEST,
                    "HOME_PROVIDER_DEFINITION_NOT_OWNED",
                    "Approval does not own a requested widget definition.");
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
                .stream().anyMatch(name ->
                        java.util.Collections.list(request.getHeaders(name)).size() > 1);
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
