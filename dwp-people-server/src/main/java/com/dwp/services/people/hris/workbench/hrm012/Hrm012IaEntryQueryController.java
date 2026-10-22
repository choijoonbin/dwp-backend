package com.dwp.services.people.hris.workbench.hrm012;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exact closed owner GET boundary for IAQ-012. */
@RestController
@RequestMapping(value = "/v1/hris/workbench/operations/data/quality",
        produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm012IaEntryQueryController {
    private static final Set<String> COMMON = Set.of(
            "limit", "filter", "sort", "locale", "timeZone",
            "correlationId", "asOf", "cursor");
    private static final Set<String> FILTERS = Set.of(
            "filter[ingestionStatus][codes]",
            "filter[ingestionStatus][minimumRevision]",
            "filter[qualityFinding][decisionCodes]",
            "filter[qualityFinding][evidenceDigest]",
            "filter[commandReceipt][receiptIds]",
            "filter[commandReceipt][states]",
            "filter[effectiveOn]");

    private final Hrm012IaEntryQueryService service;

    Hrm012IaEntryQueryController(Hrm012IaEntryQueryService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<Hrm012QueryModels.Projection> query(
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam String correlationId,
            @RequestParam(required = false) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireClosed(parameters);
        UUID correlation = uuid(correlationId, "correlationId");
        Hrm012IaEntryQueryService.QueryResult result = service.query(
                new Hrm012QueryModels.QueryRequest(limit, filter(parameters), sort,
                        locale, timeZone, correlation, asOf, cursor));
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
                    throw Hrm012Problem.invalid(
                            "Unknown IAQ-012 query parameter: " + key);
                });
        for (String required : Set.of(
                "limit", "locale", "timeZone", "correlationId")) {
            if (parameters.getOrDefault(required, List.of()).size() != 1) {
                throw Hrm012Problem.invalid(required + " must occur exactly once.");
            }
        }
        if (parameters.getOrDefault("sort", List.of()).isEmpty()) {
            throw Hrm012Problem.invalid("sort is required.");
        }
        for (String optional : Set.of("asOf", "cursor")) {
            if (parameters.getOrDefault(optional, List.of()).size() > 1) {
                throw Hrm012Problem.invalid(optional + " must occur at most once.");
            }
        }
        boolean empty = parameters.containsKey("filter");
        boolean nested = parameters.keySet().stream().anyMatch(FILTERS::contains);
        if (empty == nested) {
            throw Hrm012Problem.invalid(
                    "filter must be exactly {} or a closed nested object.");
        }
        if (empty && (parameters.get("filter").size() != 1
                || !"{}".equals(parameters.getFirst("filter")))) {
            throw Hrm012Problem.invalid("An empty IAQ-012 filter must be exactly {}.");
        }
        for (String scalar : Set.of(
                "filter[ingestionStatus][minimumRevision]",
                "filter[qualityFinding][evidenceDigest]",
                "filter[effectiveOn]")) {
            if (parameters.getOrDefault(scalar, List.of()).size() > 1) {
                throw Hrm012Problem.invalid(scalar + " must occur at most once.");
            }
        }
    }

    private Hrm012QueryModels.Filter filter(MultiValueMap<String, String> values) {
        List<String> statusCodes = strings(values,
                "filter[ingestionStatus][codes]");
        Long minimumRevision = positiveLong(scalar(values,
                "filter[ingestionStatus][minimumRevision]"), "minimumRevision");
        Hrm012QueryModels.IngestionStatusFilter status =
                statusCodes.isEmpty() && minimumRevision == null ? null
                        : new Hrm012QueryModels.IngestionStatusFilter(
                        statusCodes, minimumRevision);

        List<String> findingCodes = strings(values,
                "filter[qualityFinding][decisionCodes]");
        String evidenceDigest = scalar(values,
                "filter[qualityFinding][evidenceDigest]");
        Hrm012QueryModels.QualityFindingFilter finding =
                findingCodes.isEmpty() && evidenceDigest == null ? null
                        : new Hrm012QueryModels.QualityFindingFilter(
                        findingCodes, evidenceDigest);

        List<UUID> receiptIds = uuids(values,
                "filter[commandReceipt][receiptIds]");
        List<String> receiptStates = strings(values,
                "filter[commandReceipt][states]");
        Hrm012QueryModels.CommandReceiptFilter receipt =
                receiptIds.isEmpty() && receiptStates.isEmpty() ? null
                        : new Hrm012QueryModels.CommandReceiptFilter(
                        receiptIds, receiptStates);

        String rawDate = scalar(values, "filter[effectiveOn]");
        LocalDate effectiveOn = null;
        if (rawDate != null) {
            try {
                effectiveOn = LocalDate.parse(rawDate);
            } catch (RuntimeException exception) {
                throw Hrm012Problem.invalid("effectiveOn is invalid.");
            }
        }
        return new Hrm012QueryModels.Filter(status, finding, receipt, effectiveOn);
    }

    private List<String> strings(MultiValueMap<String, String> values, String key) {
        List<String> result = values.get(key);
        if (result == null) return List.of();
        if (result.size() > 50 || result.stream().anyMatch(value -> value == null
                || value.isBlank() || !value.equals(value.strip())
                || value.length() > 128)) {
            throw Hrm012Problem.invalid(key + " is invalid.");
        }
        return List.copyOf(result);
    }

    private List<UUID> uuids(MultiValueMap<String, String> values, String key) {
        List<UUID> result = new ArrayList<>();
        for (String value : strings(values, key)) result.add(uuid(value, key));
        return List.copyOf(result);
    }

    private UUID uuid(String value, String field) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException exception) {
            throw Hrm012Problem.invalid(field + " must contain canonical UUIDs.");
        }
    }

    private Long positiveLong(String value, String field) {
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1 || !Long.toString(parsed).equals(value)) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw Hrm012Problem.invalid(field + " is invalid.");
        }
    }

    private String scalar(MultiValueMap<String, String> values, String key) {
        List<String> result = values.get(key);
        return result == null || result.isEmpty() ? null : result.getFirst();
    }
}
