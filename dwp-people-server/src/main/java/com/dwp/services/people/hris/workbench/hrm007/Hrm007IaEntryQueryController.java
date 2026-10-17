package com.dwp.services.people.hris.workbench.hrm007;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exact GET owner bindings for the three sealed HRM-007 query variants. */
@RestController
@RequestMapping(value = "/v1/hris/workbench", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = Hrm007RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class Hrm007IaEntryQueryController {

    private static final String CORRELATION = "X-Correlation-ID";
    private static final String AUTHORIZATION_REVISION =
            "X-Authorization-Decision-Revision";
    private static final Set<String> COMMON_KEYS = Set.of(
            "limit", "filter", "sort", "locale", "timeZone", "correlationId", "asOf",
            "cursor");
    private static final Set<String> REQUIRED_SCALAR_KEYS = Set.of(
            "limit", "locale", "timeZone", "correlationId");
    private static final Set<String> OPTIONAL_SCALAR_KEYS = Set.of("asOf", "cursor");
    private static final Set<String> PEOPLE_CHANGE_KEYS = Set.of(
            "filter[changeRequestSummary][minimumItemCount]",
            "filter[changeRequestSummary][maximumItemCount]",
            "filter[limitedEmployment][codes]",
            "filter[limitedEmployment][minimumRevision]",
            "filter[approvalStatus][codes]",
            "filter[approvalStatus][minimumRevision]",
            "filter[effectiveOn]");
    private static final Set<String> APPOINTMENT_KEYS = Set.of(
            "filter[appointment][startFrom]",
            "filter[appointment][startTo]",
            "filter[appointment][endFrom]",
            "filter[appointment][endTo]",
            "filter[assignment][codes]",
            "filter[assignment][publicIds]",
            "filter[employment][activeOn]",
            "filter[employment][from]",
            "filter[employment][to]",
            "filter[effectiveOn]");
    private static final Set<String> PEOPLE_EMPLOYMENT_KEYS = Set.of(
            "filter[policyMetadata][policyKeys]",
            "filter[policyMetadata][minimumRevision]",
            "filter[employmentRule][policyKeys]",
            "filter[employmentRule][minimumRevision]",
            "filter[impactSummary][minimumItemCount]",
            "filter[impactSummary][maximumItemCount]",
            "filter[effectiveOn]");

    private final Hrm007IaEntryQueryService service;

    Hrm007IaEntryQueryController(Hrm007IaEntryQueryService service) {
        this.service = service;
    }

    @Operation(operationId = "IAQ.OP.005")
    @GetMapping("/team/people-changes")
    ResponseEntity<Hrm007Dtos.Projection> peopleChanges(
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireKeys(parameters, PEOPLE_CHANGE_KEYS);
        requireTopLevelCardinality(parameters);
        requireFilter(parameters, PEOPLE_CHANGE_KEYS);
        Hrm007Dtos.AggregateFilter summary = aggregate(
                parameters, "filter[changeRequestSummary]");
        Hrm007Dtos.StatusFilter limited = status(
                parameters, "filter[limitedEmployment]");
        Hrm007Dtos.StatusFilter approval = status(
                parameters, "filter[approvalStatus]");
        return response(service.query(
                Hrm007QueryDefinition.PEOPLE_CHANGES,
                new Hrm007Dtos.QueryRequest(
                        limit, new Hrm007Dtos.PeopleChangesFilter(
                        summary, limited, approval,
                        date(parameters, "filter[effectiveOn]")),
                        sort, locale, timeZone, correlationId, asOf, cursor)));
    }

    @Operation(operationId = "IAQ.OP.007")
    @GetMapping("/operations/appointments")
    ResponseEntity<Hrm007Dtos.Projection> appointments(
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireKeys(parameters, APPOINTMENT_KEYS);
        requireTopLevelCardinality(parameters);
        requireFilter(parameters, APPOINTMENT_KEYS);
        Hrm007Dtos.TemporalFilter appointment = temporal(
                parameters, "filter[appointment]");
        Hrm007Dtos.ReferenceFilter assignment = reference(
                parameters, "filter[assignment]");
        Hrm007Dtos.EffectiveRangeFilter employment = range(
                parameters, "filter[employment]");
        return response(service.query(
                Hrm007QueryDefinition.APPOINTMENTS,
                new Hrm007Dtos.QueryRequest(
                        limit, new Hrm007Dtos.AppointmentsFilter(
                        appointment, assignment, employment,
                        date(parameters, "filter[effectiveOn]")),
                        sort, locale, timeZone, correlationId, asOf, cursor)));
    }

    @Operation(operationId = "IAQ.OP.013")
    @GetMapping("/configuration/people-employment")
    ResponseEntity<Hrm007Dtos.Projection> peopleEmployment(
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireKeys(parameters, PEOPLE_EMPLOYMENT_KEYS);
        requireTopLevelCardinality(parameters);
        requireFilter(parameters, PEOPLE_EMPLOYMENT_KEYS);
        Hrm007Dtos.PolicyFilter metadata = policy(
                parameters, "filter[policyMetadata]");
        Hrm007Dtos.PolicyFilter rule = policy(
                parameters, "filter[employmentRule]");
        Hrm007Dtos.AggregateFilter impact = aggregate(
                parameters, "filter[impactSummary]");
        return response(service.query(
                Hrm007QueryDefinition.PEOPLE_EMPLOYMENT,
                new Hrm007Dtos.QueryRequest(
                        limit, new Hrm007Dtos.PeopleEmploymentFilter(
                        metadata, rule, impact,
                        date(parameters, "filter[effectiveOn]")),
                        sort, locale, timeZone, correlationId, asOf, cursor)));
    }

    private ResponseEntity<Hrm007Dtos.Projection> response(
            Hrm007IaEntryQueryService.QueryResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set(CORRELATION,
                result.projection().queryState().correlationId().toString());
        headers.set(AUTHORIZATION_REVISION, result.authorizationDecisionRevision());
        headers.setETag(result.etag());
        return ResponseEntity.ok().headers(headers).body(result.projection());
    }

    private void requireKeys(
            MultiValueMap<String, String> parameters,
            Set<String> filterKeys) {
        Set<String> allowed = new HashSet<>(COMMON_KEYS);
        allowed.addAll(filterKeys);
        parameters.keySet().stream().filter(key -> !allowed.contains(key)).findFirst()
                .ifPresent(key -> {
                    throw Hrm007Exception.invalid("Unknown HRM-007 parameter: " + key);
                });
    }

    private void requireFilter(
            MultiValueMap<String, String> parameters,
            Set<String> filterKeys) {
        boolean base = parameters.containsKey("filter");
        boolean nested = parameters.keySet().stream().anyMatch(filterKeys::contains);
        if (base == nested) {
            throw Hrm007Exception.invalid(
                    base
                            ? "HRM-007 filter cannot mix an empty envelope and nested leaves."
                            : "HRM-007 filter is required.");
        }
        if (base) {
            List<String> values = parameters.get("filter");
            if (values == null || values.size() != 1 || !"{}".equals(values.getFirst())) {
                throw Hrm007Exception.invalid(
                        "HRM-007 empty filter must be exactly one {} value.");
            }
        }
    }

    private void requireTopLevelCardinality(
            MultiValueMap<String, String> parameters) {
        for (String key : REQUIRED_SCALAR_KEYS) {
            if (parameters.getOrDefault(key, List.of()).size() != 1) {
                throw Hrm007Exception.invalid(
                        key + " must occur exactly once.");
            }
        }
        for (String key : OPTIONAL_SCALAR_KEYS) {
            if (parameters.getOrDefault(key, List.of()).size() > 1) {
                throw Hrm007Exception.invalid(
                        key + " must occur at most once.");
            }
        }
        List<String> sort = parameters.get("sort");
        if (sort == null || sort.isEmpty()
                || sort.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw Hrm007Exception.invalid(
                    "sort must occur one or more times with non-blank values.");
        }
    }

    private Hrm007Dtos.AggregateFilter aggregate(
            MultiValueMap<String, String> source,
            String prefix) {
        Integer minimum = integer(source, prefix + "[minimumItemCount]");
        Integer maximum = integer(source, prefix + "[maximumItemCount]");
        return minimum == null && maximum == null
                ? null : new Hrm007Dtos.AggregateFilter(minimum, maximum);
    }

    private Hrm007Dtos.StatusFilter status(
            MultiValueMap<String, String> source,
            String prefix) {
        List<String> codes = values(source, prefix + "[codes]");
        Long revision = longValue(source, prefix + "[minimumRevision]");
        return codes.isEmpty() && revision == null
                ? null : new Hrm007Dtos.StatusFilter(codes, revision);
    }

    private Hrm007Dtos.TemporalFilter temporal(
            MultiValueMap<String, String> source,
            String prefix) {
        Instant startFrom = instant(source, prefix + "[startFrom]");
        Instant startTo = instant(source, prefix + "[startTo]");
        Instant endFrom = instant(source, prefix + "[endFrom]");
        Instant endTo = instant(source, prefix + "[endTo]");
        return startFrom == null && startTo == null && endFrom == null && endTo == null
                ? null : new Hrm007Dtos.TemporalFilter(
                startFrom, startTo, endFrom, endTo);
    }

    private Hrm007Dtos.ReferenceFilter reference(
            MultiValueMap<String, String> source,
            String prefix) {
        List<String> codes = values(source, prefix + "[codes]");
        List<String> publicIds = values(source, prefix + "[publicIds]");
        return codes.isEmpty() && publicIds.isEmpty()
                ? null : new Hrm007Dtos.ReferenceFilter(codes, publicIds);
    }

    private Hrm007Dtos.EffectiveRangeFilter range(
            MultiValueMap<String, String> source,
            String prefix) {
        LocalDate activeOn = date(source, prefix + "[activeOn]");
        LocalDate from = date(source, prefix + "[from]");
        LocalDate to = date(source, prefix + "[to]");
        return activeOn == null && from == null && to == null
                ? null : new Hrm007Dtos.EffectiveRangeFilter(activeOn, from, to);
    }

    private Hrm007Dtos.PolicyFilter policy(
            MultiValueMap<String, String> source,
            String prefix) {
        List<String> keys = values(source, prefix + "[policyKeys]");
        Long revision = longValue(source, prefix + "[minimumRevision]");
        return keys.isEmpty() && revision == null
                ? null : new Hrm007Dtos.PolicyFilter(keys, revision);
    }

    private List<String> values(MultiValueMap<String, String> source, String key) {
        List<String> result = new ArrayList<>();
        for (String value : source.getOrDefault(key, List.of())) {
            if (value == null || value.isBlank()) {
                throw Hrm007Exception.invalid(key + " contains a blank value.");
            }
            for (String part : value.split(",", -1)) {
                if (part.isBlank()) {
                    throw Hrm007Exception.invalid(key + " contains a blank value.");
                }
                result.add(part);
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
            throw Hrm007Exception.invalid(key + " must be an integer.");
        }
    }

    private Long longValue(MultiValueMap<String, String> source, String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw Hrm007Exception.invalid(key + " must be an integer.");
        }
    }

    private LocalDate date(MultiValueMap<String, String> source, String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw Hrm007Exception.invalid(key + " must be an ISO date.");
        }
    }

    private Instant instant(MultiValueMap<String, String> source, String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw Hrm007Exception.invalid(key + " must be an RFC 3339 instant.");
        }
    }

    private String single(MultiValueMap<String, String> source, String key) {
        List<String> values = source.get(key);
        if (values == null || values.isEmpty()) return null;
        if (values.size() != 1 || values.getFirst() == null
                || values.getFirst().isBlank()) {
            throw Hrm007Exception.invalid(key + " must be one non-blank value.");
        }
        return values.getFirst();
    }
}
