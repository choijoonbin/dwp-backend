package com.dwp.services.meeting.videomeeting.home;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestCodec;
import com.dwp.platform.contract.home.HomeWidgetProviderRequestException;
import com.dwp.platform.contract.home.HomeWidgetProviderResponses;
import com.dwp.platform.contract.home.HomeWidgetProviderSecurity;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingService;
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

/** Recipient-scoped meeting preparation and follow-up projections for Home. */
@RestController
@Hidden
public class MeetingHomeWidgetProviderController {

    static final Set<String> DEFINITIONS = Set.of(
            "meetings.next-prep", "meetings.followup-candidates");
    private static final String SOURCE = "MEETING_HOME";
    private static final String ROUTE = "/meetings/home";

    private final VideoMeetingService service;
    private final ObjectMapper objectMapper;
    private final String serviceToken;

    public MeetingHomeWidgetProviderController(
            VideoMeetingService service,
            ObjectMapper objectMapper,
            @Value("${dwp.meeting.home-runtime-service-token:}") String serviceToken) {
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
        VideoMeetingDtos.HomeResponse home = null;
        RuntimeException failure = null;
        boolean denied = false;
        if (context.has("APP.MEETINGS", "VIEW")) {
            try {
                MeetingRequestContext.set(new MeetingRequestContext.Subject(
                        context.userId(), context.tenantId(), context.personPublicId(), null,
                        context.roles(), context.permissions(), context.groups()));
                home = service.home(context.locale().equals("ko-KR") ? "Asia/Seoul" : "UTC");
            } catch (RuntimeException exception) {
                failure = exception;
                denied = isDenied(exception);
            } finally {
                MeetingRequestContext.clear();
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
                "message", "Meeting Home widgets expose source routes only."));
    }

    private HomeWidgetProviderContract.WidgetResult result(
            HomeWidgetProviderSecurity.Context context,
            HomeWidgetProviderContract.WidgetRequest widget,
            VideoMeetingDtos.HomeResponse home,
            RuntimeException failure,
            boolean denied) {
        if (!context.has("APP.MEETINGS", "VIEW")) {
            return HomeWidgetProviderResponses.forbidden(
                    widget, SOURCE, "AUTHORIZATION_MEETING_REQUIRED");
        }
        if (denied) return HomeWidgetProviderResponses.forbidden(
                widget, SOURCE, "AUTHORIZATION_MEETING_SOURCE_REVOKED");
        if (failure != null || home == null) {
            return HomeWidgetProviderResponses.unavailable(
                    widget, SOURCE, "PROVIDER_MEETING_PROJECTION_UNAVAILABLE");
        }
        int limit = widget.itemLimit();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("meetingsToday", home.metrics().meetingsToday());
        payload.put("meetingMinutesToday", home.metrics().meetingMinutesToday());
        if ("meetings.next-prep".equals(widget.definitionKey())) {
            List<VideoMeetingDtos.MeetingSummary> meetings = home.today().stream()
                    .limit(limit).toList();
            if (meetings.isEmpty() && home.metrics().meetingsToday() == 0) {
                return HomeWidgetProviderResponses.empty(widget, SOURCE, ROUTE);
            }
            payload.put("items", meetings.stream().map(this::meeting).toList());
            return HomeWidgetProviderResponses.available(widget, SOURCE, payload, ROUTE);
        }
        List<Map<String, Object>> recent = home.recent().stream()
                .limit(limit).map(this::meeting).toList();
        if (recent.isEmpty()) return HomeWidgetProviderResponses.empty(widget, SOURCE, ROUTE);
        payload.put("items", recent);
        return HomeWidgetProviderResponses.partial(
                widget, SOURCE, payload, ROUTE,
                "PROVIDER_MEETING_FOLLOWUP_SUMMARY_ONLY",
                List.of("FOLLOWUP_CANDIDATE_DETAIL_NOT_PROJECTED"));
    }

    private boolean isDenied(RuntimeException failure) {
        return failure instanceof BaseException exception
                && exception.getErrorCode().getHttpStatus().is4xxClientError();
    }

    private Map<String, Object> meeting(VideoMeetingDtos.MeetingSummary value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", value.meetingId().toString());
        item.put("title", value.title());
        item.put("state", value.lifecycleState());
        if (value.startsAt() != null) item.put("startsAt", value.startsAt().toString());
        if (value.endsAt() != null) item.put("endsAt", value.endsAt().toString());
        item.put("attendeeCount", value.attendeeCount());
        item.put("version", value.version());
        return item;
    }

    private void requireOwned(HomeWidgetProviderContract.BatchRequest batch) {
        if (batch.widgets().stream().anyMatch(widget ->
                !DEFINITIONS.contains(widget.definitionKey()))) {
            throw new HomeWidgetProviderRequestException(
                    HomeWidgetProviderRequestException.Kind.BAD_REQUEST,
                    "HOME_PROVIDER_DEFINITION_NOT_OWNED",
                    "Meeting does not own a requested widget definition.");
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
