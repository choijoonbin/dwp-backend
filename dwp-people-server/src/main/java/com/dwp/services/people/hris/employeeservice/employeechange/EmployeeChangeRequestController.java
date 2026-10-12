package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmStepUpHeaders;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exact owner routes for HRM-010 and IAQ-009. */
@RestController
@RequestMapping(value = "/v1/hris", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeRequestController {

    private static final String CORRELATION = "X-Correlation-ID";
    private static final String PURPOSE = "X-Purpose-Code";
    private static final String AUTHORIZATION_REVISION =
            "X-Authorization-Decision-Revision";
    private static final Set<String> FILTER_KEYS = Set.of(
            "filter[changeRequestSummary][minimumItemCount]",
            "filter[changeRequestSummary][maximumItemCount]",
            "filter[approvalStatus][codes]",
            "filter[approvalStatus][minimumRevision]",
            "filter[employment][activeOn]",
            "filter[employment][from]",
            "filter[employment][to]",
            "filter[effectiveOn]");

    private final EmployeeChangeService service;

    EmployeeChangeRequestController(EmployeeChangeService service) {
        this.service = service;
    }

    @GetMapping("/workbench/operations/employee/change/requests")
    ResponseEntity<EmployeeChangeDtos.WorkbenchProjection> workbench(
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestParam(required = false) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> parameters) {
        EmployeeChangeDtos.WorkbenchQuery query = new EmployeeChangeDtos.WorkbenchQuery(
                limit, filter(parameters), sort, locale, timeZone,
                correlationId, asOf, cursor);
        EmployeeChangeService.WorkbenchResult result = service.workbench(query);
        return ResponseEntity.ok()
                .headers(headers(
                        correlationId, result.authorizationRevision(), result.etag()))
                .body(result.projection());
    }

    @PostMapping(value = "/employee-change-requests",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommandReceiptV1> create(
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(CORRELATION) UUID correlationId,
            @RequestHeader(value = PURPOSE, required = false) String purpose,
            @Valid @RequestBody EmployeeChangeDtos.CreateRequest request) {
        return accepted(service.create(
                request, idempotencyKey, ifMatch, correlationId, purpose));
    }

    @PostMapping(value = "/employee-change-requests/{requestPublicId}/submit",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommandReceiptV1> submit(
            @PathVariable UUID requestPublicId,
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(CORRELATION) UUID correlationId,
            @RequestHeader(value = PURPOSE, required = false) String purpose,
            @Valid @RequestBody EmployeeChangeDtos.EmptyCommand ignored) {
        return accepted(service.submit(
                requestPublicId, idempotencyKey, ifMatch, correlationId, purpose));
    }

    @PostMapping(value = "/employee-change-requests/{requestPublicId}/apply",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommandReceiptV1> apply(
            @PathVariable UUID requestPublicId,
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(HcmStepUpHeaders.CHALLENGE) String challenge,
            @RequestHeader(HcmStepUpHeaders.DECISION_REVISION) String decisionRevision,
            @RequestHeader(HcmStepUpHeaders.EXPECTED_OBJECT_VERSION) Long expectedObjectVersion,
            @RequestHeader(CORRELATION) UUID correlationId,
            @RequestHeader(value = PURPOSE, required = false) String purpose,
            @Valid @RequestBody EmployeeChangeDtos.EmptyCommand ignored) {
        return accepted(service.apply(
                requestPublicId, idempotencyKey, ifMatch, correlationId, purpose,
                new HcmStepUpHeaders(
                        challenge, idempotencyKey, decisionRevision, expectedObjectVersion)));
    }

    private ResponseEntity<CommandReceiptV1> accepted(
            EmployeeChangeService.CommandResult result) {
        CommandReceiptV1 receipt = result.receipt();
        String etag = "\"v:" + result.entityVersion() + "\"";
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .headers(headers(
                        receipt.correlationId(),
                        HcmPepContext.require().decisionRevision(), etag))
                .body(receipt);
    }

    private HttpHeaders headers(UUID correlationId, String revision, String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set(CORRELATION, correlationId.toString());
        headers.set(AUTHORIZATION_REVISION, revision);
        headers.setETag(etag);
        return headers;
    }

    private EmployeeChangeDtos.WorkbenchFilter filter(
            MultiValueMap<String, String> parameters) {
        parameters.keySet().stream()
                .filter(key -> key.startsWith("filter[") && !FILTER_KEYS.contains(key))
                .findFirst()
                .ifPresent(key -> {
                    throw invalid("Unknown IAQ-009 filter: " + key);
                });
        Integer minimumCount = integer(parameters,
                "filter[changeRequestSummary][minimumItemCount]");
        Integer maximumCount = integer(parameters,
                "filter[changeRequestSummary][maximumItemCount]");
        EmployeeChangeDtos.AggregateFilter summary = minimumCount == null
                && maximumCount == null ? null
                : new EmployeeChangeDtos.AggregateFilter(minimumCount, maximumCount);
        List<String> codes = values(parameters, "filter[approvalStatus][codes]");
        Long minimumRevision = longValue(parameters,
                "filter[approvalStatus][minimumRevision]");
        EmployeeChangeDtos.StatusFilter approval = codes.isEmpty()
                && minimumRevision == null ? null
                : new EmployeeChangeDtos.StatusFilter(codes, minimumRevision);
        LocalDate activeOn = date(parameters, "filter[employment][activeOn]");
        LocalDate from = date(parameters, "filter[employment][from]");
        LocalDate to = date(parameters, "filter[employment][to]");
        EmployeeChangeDtos.EffectiveRangeFilter employment = activeOn == null
                && from == null && to == null ? null
                : new EmployeeChangeDtos.EffectiveRangeFilter(activeOn, from, to);
        return new EmployeeChangeDtos.WorkbenchFilter(
                summary, approval, employment,
                date(parameters, "filter[effectiveOn]"));
    }

    private List<String> values(MultiValueMap<String, String> source, String key) {
        List<String> result = new ArrayList<>();
        for (String value : source.getOrDefault(key, List.of())) {
            if (value == null || value.isBlank()) continue;
            for (String part : value.split(",")) {
                if (!part.isBlank()) result.add(part);
            }
        }
        return List.copyOf(result);
    }

    private Integer integer(MultiValueMap<String, String> source, String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw invalid(key + " must be an integer.");
        }
    }

    private Long longValue(MultiValueMap<String, String> source, String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw invalid(key + " must be an integer.");
        }
    }

    private LocalDate date(MultiValueMap<String, String> source, String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw invalid(key + " must be an ISO date.");
        }
    }

    private String single(MultiValueMap<String, String> source, String key) {
        List<String> values = source.get(key);
        if (values == null || values.isEmpty()) return null;
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
            throw invalid(key + " must be a single non-blank value.");
        }
        return values.getFirst();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
