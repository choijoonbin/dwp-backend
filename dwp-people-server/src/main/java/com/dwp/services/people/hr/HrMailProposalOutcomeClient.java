package com.dwp.services.people.hr;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class HrMailProposalOutcomeClient {

    static final String SERVICE_IDENTITY = "dwp-people-server";
    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final RestClient platform;
    private final String serviceToken;

    @Autowired
    public HrMailProposalOutcomeClient(
            RestClient.Builder builder,
            @Value("${dwp.mail-proposal-outcomes.platform-url:http://localhost:8002}")
            String platformUrl,
            @Value("${dwp.mail-proposal-outcomes.service-token:}") String serviceToken) {
        this(builder.baseUrl(platformUrl).build(), serviceToken);
    }

    HrMailProposalOutcomeClient(RestClient platform, String serviceToken) {
        this.platform = platform;
        this.serviceToken = serviceToken == null ? "" : serviceToken.strip();
    }

    public void preflight(
            long tenantId,
            long actorId,
            HrMailProposalBinding binding,
            HrDtos.CreateLeaveRequest leaveRequest) {
        requireConfigured();
        try {
            platform.post()
                    .uri("/internal/v1/mail/proposal-outcomes/preflight")
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                    .header(SERVICE_TOKEN_HEADER, serviceToken)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .header(TENANT_HEADER, Long.toString(tenantId))
                    .header(USER_HEADER, Long.toString(actorId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(OwnerOutcomeRequest.preflight(binding, leaveRequest))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw preflightFailure(exception);
        }
    }

    public void record(HrMailProposalOutcomeOutboxRepository.PendingOutcome outcome) {
        requireConfigured();
        try {
            var request = platform.post()
                    .uri("/internal/v1/mail/proposal-outcomes")
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                    .header(SERVICE_TOKEN_HEADER, serviceToken)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .header(TENANT_HEADER, Long.toString(outcome.tenantId()))
                    .header(USER_HEADER, Long.toString(outcome.actorId()));
            if (outcome.correlationId() != null && !outcome.correlationId().isBlank()) {
                request.header(CORRELATION_HEADER, outcome.correlationId().strip());
            }
            request.contentType(MediaType.APPLICATION_JSON)
                    .body(new OwnerOutcomeRequest(
                            outcome.proposalId(),
                            outcome.commandId(),
                            outcome.proposalVersion(),
                            outcome.resultRef(),
                            null))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new DeliveryException(
                    exception.getStatusCode().is5xxServerError(),
                    "Platform Mail proposal outcome delivery returned HTTP "
                            + exception.getStatusCode().value(),
                    exception);
        }
    }

    private void requireConfigured() {
        if (serviceToken.isBlank()) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The Platform service token is not configured for Mail proposal outcomes.");
        }
    }

    private BaseException preflightFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        ErrorCode code;
        if (status.value() == 400) {
            code = ErrorCode.INVALID_INPUT_VALUE;
        } else if (status.value() == 403) {
            code = ErrorCode.FORBIDDEN;
        } else if (status.value() == 404) {
            code = ErrorCode.NOT_FOUND;
        } else if (status.value() == 409) {
            code = ErrorCode.RESOURCE_CONFLICT;
        } else {
            code = ErrorCode.EXTERNAL_SERVICE_ERROR;
        }
        return new BaseException(
                code,
                "The Mail proposal owner binding could not be validated.",
                exception);
    }

    record OwnerOutcomeRequest(
            UUID proposalId,
            UUID commandId,
            long proposalVersion,
            String resultRef,
            Map<String, Object> ownerPayload) {

        static OwnerOutcomeRequest preflight(
                HrMailProposalBinding binding,
                HrDtos.CreateLeaveRequest request) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("planId", request.planId().toString());
            payload.put("startAt", request.startAt().toString());
            payload.put("endAt", request.endAt().toString());
            payload.put("startsOn", request.startAt().atZone(ZoneOffset.UTC)
                    .toLocalDate().toString());
            payload.put("endsOn", request.endAt().atZone(ZoneOffset.UTC)
                    .toLocalDate().toString());
            payload.put("requestedMinutes", request.requestedMinutes());
            payload.put("durationDays", ChronoUnit.DAYS.between(
                    request.startAt().atZone(ZoneOffset.UTC).toLocalDate(),
                    request.endAt().atZone(ZoneOffset.UTC).toLocalDate()) + 1);
            payload.put("reason", request.reason());
            return new OwnerOutcomeRequest(
                    binding.proposalId(),
                    binding.commandId(),
                    binding.proposalVersion(),
                    null,
                    payload);
        }
    }

    static final class DeliveryException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final boolean retryable;

        DeliveryException(boolean retryable, String message, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        boolean retryable() {
            return retryable;
        }
    }
}
