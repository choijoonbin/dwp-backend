package com.dwp.services.platform.workhub.assignment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.services.platform.workhub.personal.PersonalWorkDtos.AccessContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static com.dwp.services.platform.workhub.assignment.WorkAssignmentDtos.*;
import static com.dwp.services.platform.workhub.assignment.MeetingFollowupProtocol.*;

/** Reads only the owner-confirmed Work terms. Original Meeting content is never ingested. */
@Component
public final class MeetingFollowupSourceAuthority implements WorkAssignmentSourceAuthority {
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final MeetingFollowupWorkloadSigner signer;
    private final HttpClient http;
    private final Duration timeout;

    @Autowired
    public MeetingFollowupSourceAuthority(ObjectMapper mapper,
            @Value("${DWP_WORK_MEETING_SOURCE_BASE_URL:}") String baseUrl,
            @Value("${DWP_WORK_MEETING_ASSERTION_KEY_ID:}") String keyId,
            @Value("${DWP_WORK_MEETING_ASSERTION_SECRET_BASE64:}") String secret,
            @Value("${DWP_WORK_MEETING_SOURCE_ALLOW_HTTP:false}") boolean allowHttp) {
        this(mapper, baseUrl, keyId, secret, allowHttp,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(5));
    }

    MeetingFollowupSourceAuthority(ObjectMapper mapper, String baseUrl, String keyId, String secret,
                                   boolean allowHttp, HttpClient http, Duration timeout) {
        this.mapper = mapper;
        this.http = http;
        this.timeout = timeout;
        URI destination = null;
        MeetingFollowupWorkloadSigner configuredSigner = null;
        try {
            URI base = URI.create(baseUrl);
            if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                    || base.getRawFragment() != null || !(base.getPath().isEmpty() || "/".equals(base.getPath()))
                    || !("https".equals(base.getScheme()) || allowHttp && "http".equals(base.getScheme())))
                throw new IllegalArgumentException("Invalid Meeting source endpoint.");
            configuredSigner = new MeetingFollowupWorkloadSigner(keyId, secret, mapper);
            destination = base.resolve(PATH);
        } catch (IllegalArgumentException | NullPointerException exception) {
            // An unconfigured adapter remains unavailable; no fallback secret or local fake source.
        }
        this.endpoint = destination;
        this.signer = configuredSigner;
    }

    @Override public ConfirmedTask confirmCreate(AccessContext actor, SourceIdentity source, long expectedSourceVersion) {
        if (expectedSourceVersion < 0) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        Response response = resolve(request(actor, source, Operation.CREATE, null, expectedSourceVersion));
        requireAllowed(response);
        if (!Boolean.TRUE.equals(response.canAssign()) || response.approvedTask() == null) throw unavailable();
        if (!Objects.equals(response.sourceVersion(), expectedSourceVersion))
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        ApprovedTask task = response.approvedTask();
        if (task.assigneeUserId() <= 0 || task.title() == null || task.title().isBlank()
                || task.title().length() > 500 || task.priority() == null
                || task.description() != null && task.description().length() > 4000) throw unavailable();
        return new ConfirmedTask(source, expectedSourceVersion, task.assigneeUserId(),
                task.title().trim(), task.description(), task.priority(), task.dueAt());
    }

    @Override public void requireReassignment(AccessContext actor, SourceIdentity source, long assigneeUserId) {
        if (assigneeUserId <= 0) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        Response response = resolve(request(actor, source, Operation.REASSIGN, assigneeUserId, null));
        requireAllowed(response);
        if (!Boolean.TRUE.equals(response.canAssign()) || response.approvedTask() != null) throw unavailable();
    }

    @Override public Inspection inspect(AccessContext actor, SourceIdentity source, long confirmedSourceVersion) {
        try {
            Response response = resolve(request(actor, source, Operation.READ, null, null));
            if (!Boolean.TRUE.equals(response.allowed()) || response.originalAccess() != OriginalAccess.AVAILABLE
                    || response.sourceVersion() == null || response.sourceVersion() < confirmedSourceVersion
                    || response.approvedTask() != null) return hidden();
            String route = "/meetings/follow-ups?meetingId=" + source.meetingId()
                    + "&reportId=" + source.reportId() + "&candidateId=" + source.candidateId();
            return new Inspection(new SourceView(SourceAvailability.AVAILABLE, source,
                    confirmedSourceVersion, route), Boolean.TRUE.equals(response.canAssign()));
        } catch (BaseException exception) {
            return hidden();
        }
    }

    private Request request(AccessContext actor, SourceIdentity source, Operation action, Long target, Long version) {
        if (actor == null || actor.tenantId() == null || actor.tenantId() <= 0 || actor.userId() == null
                || actor.userId() <= 0 || source == null || source.sourceSystem() != SourceSystem.MEETING_FOLLOWUP
                || source.meetingId() == null || source.reportId() == null || source.candidateId() == null)
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        return new Request(actor.tenantId(), actor.userId(),
                new Source(source.meetingId(), source.reportId(), source.candidateId()), action, target, version);
    }

    private Response resolve(Request request) {
        if (endpoint == null || signer == null) throw unavailable();
        try {
            byte[] body = mapper.writeValueAsBytes(request);
            HttpHeaders observability = new HttpHeaders();
            OutboundHttpHeaders.propagateObservability(observability);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header(ASSERTION_HEADER, signer.sign(request, body));
            observability.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            HttpRequest httpRequest = builder.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(httpRequest, info -> new WorkSourceBodySubscriber());
            HttpResponse<byte[]> result;
            try { result = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
            finally { if (!pending.isDone()) pending.cancel(true); }
            if (result.statusCode() == 409) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            if (result.statusCode() == 401 || result.statusCode() == 403 || result.statusCode() == 404)
                throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE);
            if (result.statusCode() != 200 || !result.headers().firstValue("Content-Type")
                    .orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) throw unavailable();
            Response response = mapper.readerFor(Response.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(result.body());
            if (response == null || !Objects.equals(response.tenantId(), request.tenantId())
                    || !Objects.equals(response.actorUserId(), request.actorUserId())
                    || !Objects.equals(response.source(), request.source()) || response.action() != request.action()
                    || response.allowed() == null || response.canAssign() == null || response.originalAccess() == null)
                throw unavailable();
            return response;
        } catch (BaseException exception) { throw exception; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception exception) { throw unavailable(); }
    }

    private void requireAllowed(Response response) {
        if (!Boolean.TRUE.equals(response.allowed()) || response.originalAccess() != OriginalAccess.AVAILABLE) {
            if ("SOURCE_VERSION_CONFLICT".equals(response.denialCode()))
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE);
        }
    }

    private Inspection hidden() {
        return new Inspection(new SourceView(SourceAvailability.UNAVAILABLE, null, null, null), false);
    }
    private BaseException unavailable() {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, "Meeting follow-up source verification is unavailable.");
    }
}
