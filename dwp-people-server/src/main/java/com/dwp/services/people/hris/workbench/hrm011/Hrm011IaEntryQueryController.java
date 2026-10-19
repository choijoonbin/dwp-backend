package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exact owner GET route for IAQ-008 v2. */
@RestController
@RequestMapping(value = "/v1/hris/workbench/operations/contracts/compensation",
        produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm011IaEntryQueryController {
    private static final Set<String> COMMON = Set.of(
            "limit", "filter", "sort", "locale", "timeZone",
            "correlationId", "asOf", "cursor");
    private static final Set<String> FILTERS = Set.of(
            "filter[contractMetadata][keys]",
            "filter[contractMetadata][versions]",
            "filter[compensationBasis][currency]",
            "filter[compensationBasis][minimumAmount]",
            "filter[compensationBasis][maximumAmount]",
            "filter[effectiveOn]");
    private final Hrm011IaEntryQueryService service;

    Hrm011IaEntryQueryController(Hrm011IaEntryQueryService service) {
        this.service = service;
    }

    @GetMapping("/basis")
    ResponseEntity<Hrm011QueryModels.Projection> query(
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam String correlationId,
            @RequestParam(required = false) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireClosed(parameters);
        Hrm011QueryModels.Filter filter = filter(parameters);
        UUID correlation;
        try {
            correlation = UUID.fromString(correlationId);
            if (!correlation.toString().equalsIgnoreCase(correlationId)) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw EmploymentContractException.invalid(
                    "correlationId must be a canonical UUID.");
        }
        Hrm011IaEntryQueryService.QueryResult result = service.query(
                new Hrm011QueryModels.QueryRequest(
                        limit, filter, sort, locale, timeZone, correlation, asOf, cursor));
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set("X-Correlation-ID", correlation.toString());
        headers.set("X-Authorization-Decision-Revision",
                result.authorizationRevision());
        headers.setETag(result.etag());
        return ResponseEntity.ok().headers(headers).body(result.projection());
    }

    private void requireClosed(MultiValueMap<String, String> parameters) {
        parameters.keySet().stream()
                .filter(key -> !COMMON.contains(key) && !FILTERS.contains(key))
                .findFirst().ifPresent(key -> {
                    throw EmploymentContractException.invalid(
                            "Unknown IAQ-008 query parameter: " + key);
                });
        for (String required : Set.of(
                "limit", "locale", "timeZone", "correlationId")) {
            if (parameters.getOrDefault(required, List.of()).size() != 1) {
                throw EmploymentContractException.invalid(
                        required + " must occur exactly once.");
            }
        }
        if (parameters.getOrDefault("sort", List.of()).isEmpty()) {
            throw EmploymentContractException.invalid("sort is required.");
        }
        for (String optional : Set.of("asOf", "cursor")) {
            if (parameters.getOrDefault(optional, List.of()).size() > 1) {
                throw EmploymentContractException.invalid(
                        optional + " must occur at most once.");
            }
        }
        boolean empty = parameters.containsKey("filter");
        boolean nested = parameters.keySet().stream().anyMatch(FILTERS::contains);
        if (empty == nested) {
            throw EmploymentContractException.invalid(
                    "filter must be exactly {} or a closed nested object.");
        }
        if (empty && (parameters.get("filter").size() != 1
                || !"{}".equals(parameters.getFirst("filter")))) {
            throw EmploymentContractException.invalid(
                    "An empty IAQ-008 filter must be exactly {}.");
        }
        for (String scalar : Set.of(
                "filter[compensationBasis][minimumAmount]",
                "filter[compensationBasis][maximumAmount]",
                "filter[compensationBasis][currency]",
                "filter[effectiveOn]")) {
            if (parameters.getOrDefault(scalar, List.of()).size() > 1) {
                throw EmploymentContractException.invalid(
                        scalar + " must occur at most once.");
            }
        }
    }

    private Hrm011QueryModels.Filter filter(MultiValueMap<String, String> values) {
        List<String> keys = list(values, "filter[contractMetadata][keys]");
        List<String> versions = list(values, "filter[contractMetadata][versions]");
        Hrm011QueryModels.MetadataFilter metadata = keys.isEmpty() && versions.isEmpty()
                ? null : new Hrm011QueryModels.MetadataFilter(keys, versions);
        String currency = scalar(values,
                "filter[compensationBasis][currency]");
        String minimum = scalar(values,
                "filter[compensationBasis][minimumAmount]");
        String maximum = scalar(values,
                "filter[compensationBasis][maximumAmount]");
        Hrm011QueryModels.MoneyFilter money = currency == null
                && minimum == null && maximum == null
                ? null : new Hrm011QueryModels.MoneyFilter(
                currency, minimum, maximum);
        String effective = scalar(values, "filter[effectiveOn]");
        LocalDate effectiveOn = null;
        if (effective != null) {
            try {
                effectiveOn = LocalDate.parse(effective);
            } catch (RuntimeException exception) {
                throw EmploymentContractException.invalid("effectiveOn is invalid.");
            }
        }
        return new Hrm011QueryModels.Filter(metadata, money, effectiveOn);
    }

    private List<String> list(MultiValueMap<String, String> values, String key) {
        List<String> result = values.get(key);
        if (result == null) return List.of();
        if (result.size() > 50 || result.stream().anyMatch(
                value -> value == null || value.isBlank() || !value.equals(value.trim()))) {
            throw EmploymentContractException.invalid(key + " is invalid.");
        }
        return List.copyOf(result);
    }

    private String scalar(MultiValueMap<String, String> values, String key) {
        List<String> result = values.get(key);
        return result == null || result.isEmpty() ? null : result.getFirst();
    }

}
