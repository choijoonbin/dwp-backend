package com.dwp.services.people.hris.employeeservice.certificate;

import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
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

/** Exact two GET owner routes and the single POST owner route for Stage A. */
@RestController
@RequestMapping(value = "/v1/hris", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = Hrm009RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class CertificateController {

    private static final String CORRELATION = "X-Correlation-ID";
    private static final String PURPOSE = "X-Purpose-Code";
    private static final String AUTHORIZATION_REVISION =
            "X-Authorization-Decision-Revision";
    private static final Set<String> COMMON_QUERY = Set.of(
            "limit", "filter", "sort", "locale", "timeZone", "correlationId",
            "asOf", "cursor");
    private static final Set<String> REQUIRED_SCALARS = Set.of(
            "limit", "locale", "timeZone", "correlationId");
    private static final Set<String> OPTIONAL_SCALARS = Set.of("asOf", "cursor");
    private static final Set<String> SELF_FILTERS = Set.of(
            "filter[requestSummary][minimumItemCount]",
            "filter[requestSummary][maximumItemCount]",
            "filter[requestStatus][codes]",
            "filter[requestStatus][minimumRevision]",
            "filter[commandReceipt][receiptIds]",
            "filter[commandReceipt][states]",
            "filter[effectiveOn]");
    private static final Set<String> OPERATIONS_FILTERS = Set.of(
            "filter[certificateMetadata][keys]",
            "filter[certificateMetadata][versions]",
            "filter[documentMetadata][keys]",
            "filter[documentMetadata][versions]",
            "filter[effectiveOn]");

    private final CertificateService service;

    CertificateController(CertificateService service) {
        this.service = service;
    }

    @GetMapping("/workbench/self-service/requests")
    ResponseEntity<CertificateDtos.Projection> selfRequests(
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestParam(required = false) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireAllowed(parameters, SELF_FILTERS);
        requireTopLevelCardinality(parameters);
        requireFilter(parameters, SELF_FILTERS);
        CertificateDtos.SelfRequestsFilter filter = new CertificateDtos.SelfRequestsFilter(
                aggregate(parameters, "requestSummary"),
                status(parameters, "requestStatus"),
                receipt(parameters, "commandReceipt"),
                date(parameters, "filter[effectiveOn]"));
        return query(CertificateQueryDefinition.SELF_REQUESTS,
                new CertificateDtos.QueryRequest(
                        limit, filter, sort, locale, timeZone,
                        correlationId, asOf, cursor));
    }

    @GetMapping("/workbench/operations/certificates/documents")
    ResponseEntity<CertificateDtos.Projection> operationsCertificates(
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestParam(required = false) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireAllowed(parameters, OPERATIONS_FILTERS);
        requireTopLevelCardinality(parameters);
        requireFilter(parameters, OPERATIONS_FILTERS);
        CertificateDtos.OperationsFilter filter = new CertificateDtos.OperationsFilter(
                metadata(parameters, "certificateMetadata"),
                metadata(parameters, "documentMetadata"),
                date(parameters, "filter[effectiveOn]"));
        return query(CertificateQueryDefinition.OPERATIONS_CERTIFICATES,
                new CertificateDtos.QueryRequest(
                        limit, filter, sort, locale, timeZone,
                        correlationId, asOf, cursor));
    }

    @PostMapping(value = "/certificates", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommandReceiptV1> requestCertificate(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(CORRELATION) UUID correlationId,
            @RequestHeader(value = PURPOSE, required = false) String purpose,
            @RequestHeader(HcmStepUpHeaders.CHALLENGE) String challenge,
            @RequestHeader(HcmStepUpHeaders.DECISION_REVISION) String decisionRevision,
            @RequestHeader(HcmStepUpHeaders.EXPECTED_OBJECT_VERSION) Long expectedVersion,
            @Valid @RequestBody CertificateDtos.RequestCertificate request) {
        requireCommandHeaders(authorization, purpose);
        CertificateService.CommandResult result = service.requestCertificate(
                request, idempotencyKey, ifMatch, correlationId, purpose,
                new HcmStepUpHeaders(
                        challenge, idempotencyKey, decisionRevision, expectedVersion));
        CommandReceiptV1 receipt = result.receipt();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .headers(headers(
                        receipt.correlationId(), decisionRevision,
                        "\"v:" + result.entityVersion() + "\""))
                .body(receipt);
    }

    private void requireCommandHeaders(String authorization, String purpose) {
        if (authorization == null
                || !authorization.matches("Bearer [A-Za-z0-9._~+/=-]+")
                || purpose != null && (purpose.length() < 3 || purpose.length() > 64)) {
            throw CertificateException.invalid(
                    "The certificate command headers are invalid.");
        }
    }

    private ResponseEntity<CertificateDtos.Projection> query(
            CertificateQueryDefinition definition,
            CertificateDtos.QueryRequest request) {
        CertificateService.QueryResult result = service.query(definition, request);
        return ResponseEntity.ok()
                .headers(headers(
                        request.correlationId(), result.authorizationRevision(),
                        result.etag()))
                .body(result.projection());
    }

    private HttpHeaders headers(
            UUID correlationId,
            String authorizationRevision,
            String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set(CORRELATION, correlationId.toString());
        headers.set(AUTHORIZATION_REVISION, authorizationRevision);
        headers.setETag(etag);
        return headers;
    }

    private void requireAllowed(
            MultiValueMap<String, String> parameters,
            Set<String> filters) {
        parameters.keySet().stream()
                .filter(key -> !COMMON_QUERY.contains(key) && !filters.contains(key))
                .findFirst()
                .ifPresent(key -> {
                    throw CertificateException.invalid(
                            "Unknown HRM-009 query parameter: " + key);
                });
    }

    private void requireTopLevelCardinality(
            MultiValueMap<String, String> parameters) {
        for (String scalar : REQUIRED_SCALARS) {
            if (parameters.getOrDefault(scalar, List.of()).size() != 1) {
                throw CertificateException.invalid(
                        scalar + " must occur exactly once.");
            }
        }
        for (String scalar : OPTIONAL_SCALARS) {
            if (parameters.getOrDefault(scalar, List.of()).size() > 1) {
                throw CertificateException.invalid(
                        scalar + " must occur at most once.");
            }
        }
        List<String> sort = parameters.get("sort");
        if (sort == null || sort.isEmpty()
                || sort.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw CertificateException.invalid(
                    "sort must occur one or more times with non-blank values.");
        }
    }

    private void requireFilter(
            MultiValueMap<String, String> parameters,
            Set<String> filterKeys) {
        boolean base = parameters.containsKey("filter");
        boolean nested = parameters.keySet().stream().anyMatch(filterKeys::contains);
        if (base == nested) {
            throw CertificateException.invalid(
                    base
                            ? "HRM-009 filter cannot mix an empty envelope and nested leaves."
                            : "HRM-009 filter is required.");
        }
        if (base) {
            List<String> values = parameters.get("filter");
            if (values == null || values.size() != 1
                    || !"{}".equals(values.getFirst())) {
                throw CertificateException.invalid(
                        "HRM-009 empty filter must be exactly one {} value.");
            }
        }
    }

    private CertificateDtos.AggregateFilter aggregate(
            MultiValueMap<String, String> values,
            String group) {
        Integer minimum = integer(values,
                "filter[" + group + "][minimumItemCount]");
        Integer maximum = integer(values,
                "filter[" + group + "][maximumItemCount]");
        return minimum == null && maximum == null ? null
                : new CertificateDtos.AggregateFilter(minimum, maximum);
    }

    private CertificateDtos.StatusFilter status(
            MultiValueMap<String, String> values,
            String group) {
        List<String> codes = list(values, "filter[" + group + "][codes]");
        Long revision = longValue(values,
                "filter[" + group + "][minimumRevision]");
        return codes.isEmpty() && revision == null ? null
                : new CertificateDtos.StatusFilter(codes, revision);
    }

    private CertificateDtos.ReceiptFilter receipt(
            MultiValueMap<String, String> values,
            String group) {
        List<String> ids = list(values, "filter[" + group + "][receiptIds]");
        List<String> states = list(values, "filter[" + group + "][states]");
        return ids.isEmpty() && states.isEmpty() ? null
                : new CertificateDtos.ReceiptFilter(ids, states);
    }

    private CertificateDtos.MetadataFilter metadata(
            MultiValueMap<String, String> values,
            String group) {
        List<String> keys = list(values, "filter[" + group + "][keys]");
        List<String> versions = list(values, "filter[" + group + "][versions]");
        return keys.isEmpty() && versions.isEmpty() ? null
                : new CertificateDtos.MetadataFilter(keys, versions);
    }

    private List<String> list(
            MultiValueMap<String, String> source,
            String key) {
        List<String> result = new ArrayList<>();
        for (String value : source.getOrDefault(key, List.of())) {
            if (value == null || value.isBlank()) {
                throw CertificateException.invalid(
                        key + " contains a blank value.");
            }
            for (String part : value.split(",", -1)) {
                if (part.isBlank()) {
                    throw CertificateException.invalid(
                            key + " contains a blank value.");
                }
                result.add(part);
            }
        }
        return List.copyOf(result);
    }

    private Integer integer(
            MultiValueMap<String, String> source,
            String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw CertificateException.invalid(key + " must be an integer.");
        }
    }

    private Long longValue(
            MultiValueMap<String, String> source,
            String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw CertificateException.invalid(key + " must be an integer.");
        }
    }

    private LocalDate date(
            MultiValueMap<String, String> source,
            String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw CertificateException.invalid(key + " must be an ISO date.");
        }
    }

    private String single(
            MultiValueMap<String, String> source,
            String key) {
        List<String> values = source.get(key);
        if (values == null || values.isEmpty()) return null;
        if (values.size() != 1 || values.getFirst() == null
                || values.getFirst().isBlank()) {
            throw CertificateException.invalid(
                    key + " must be a single non-blank value.");
        }
        return values.getFirst();
    }
}
