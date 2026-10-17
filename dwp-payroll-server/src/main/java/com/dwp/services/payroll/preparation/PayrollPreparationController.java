package com.dwp.services.payroll.preparation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.MultiValueMap;

import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_RUN_PREPARE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollPreparationController {

    private static final Set<String> FILTER_KEYS = Set.of(
            "filter[payrollPopulation][minimumItemCount]",
            "filter[payrollPopulation][maximumItemCount]",
            "filter[projectionVersion][keys]",
            "filter[projectionVersion][versions]",
            "filter[effectiveOn]");

    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String EXPECTED_VERSION = "X-DWP-Expected-Object-Version";
    static final String EXPECTED_DECISION = "X-DWP-Expected-Decision-Revision";
    static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    static final String CORRELATION = "X-Correlation-ID";

    private final PayrollPreparationService service;
    private final PayrollPreparationScopeResolver scopes;

    PayrollPreparationController(
            PayrollPreparationService service,
            PayrollPreparationScopeResolver scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @ModelAttribute("payrollPreparationActor")
    PayrollPreparationAuthority.Actor actor() {
        PayrollPreparationAuthority.Subject subject =
                PayrollPreparationRequestContext.require();
        return new PayrollPreparationAuthority.Actor(
                subject, scopes.resolve(subject));
    }

    @Operation(operationId = "getPayrollPopulationPreflight")
    @GetMapping("/workbench/population")
    PayrollPreparationModels.EntryQueryResponse population(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollPreparationActor", binding = false)
            PayrollPreparationAuthority.Actor actor,
            @RequestParam(required = false) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam int limit,
            @RequestParam String locale,
            @RequestParam List<String> sort,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestParam MultiValueMap<String, String> parameters) {
        return service.population(
                actor, new PayrollPreparationModels.EntryQuery(
                        asOf, cursor,
                        filter(parameters),
                        limit, locale, sort, timeZone, correlationId));
    }

    @Operation(operationId = "preparePayrollRun")
    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PayrollPreparationModels.PayrollCommandReceipt prepare(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollPreparationActor", binding = false)
            PayrollPreparationAuthority.Actor actor,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(EXPECTED_VERSION) long expectedVersion,
            @RequestHeader(EXPECTED_DECISION) String expectedDecisionRevision,
            @RequestHeader(STEP_UP) String challenge,
            @RequestHeader(CORRELATION) UUID correlationId,
            @Valid @RequestBody PayrollPreparationModels.PrepareCommand command) {
        return service.prepare(
                actor, command, new PayrollPreparationStepUpGuard.Headers(
                        challenge, idempotencyKey,
                        expectedDecisionRevision, expectedVersion), correlationId);
    }

    private PayrollPreparationModels.EntryQueryFilter filter(
            MultiValueMap<String, String> parameters) {
        parameters.keySet().stream()
                .filter(key -> key.startsWith("filter[")
                        && !FILTER_KEYS.contains(key))
                .findFirst()
                .ifPresent(key -> {
                    throw invalid("Unknown IAQ-014 filter: " + key);
                });
        List<String> marker = parameters.get("filter");
        boolean nestedPresent = parameters.keySet().stream()
                .anyMatch(key -> key.startsWith("filter["));
        if (marker != null && (marker.size() != 1
                || !"{}".equals(marker.getFirst()))) {
            throw invalid("filter must be the empty object marker {}.");
        }
        if (marker == null && parameters.keySet().stream()
                .noneMatch(key -> key.startsWith("filter["))) {
            throw invalid("filter is required.");
        }
        if (marker != null && nestedPresent) {
            throw invalid("filter must use exactly one closed representation.");
        }
        Long minimum = longValue(parameters,
                "filter[payrollPopulation][minimumItemCount]");
        Long maximum = longValue(parameters,
                "filter[payrollPopulation][maximumItemCount]");
        List<String> keys = values(parameters,
                "filter[projectionVersion][keys]");
        List<String> versions = values(parameters,
                "filter[projectionVersion][versions]");
        return new PayrollPreparationModels.EntryQueryFilter(
                minimum == null && maximum == null ? null
                        : new PayrollPreparationModels.AggregateFilter(minimum, maximum),
                keys.isEmpty() && versions.isEmpty() ? null
                        : new PayrollPreparationModels.MetadataFilter(keys, versions),
                date(parameters, "filter[effectiveOn]"));
    }

    private List<String> values(
            MultiValueMap<String, String> source, String key) {
        List<String> result = new ArrayList<>();
        for (String value : source.getOrDefault(key, List.of())) {
            if (value == null || value.isBlank()
                    || !value.equals(value.strip()) || value.indexOf(',') >= 0) {
                throw invalid(key + " values must be repeated canonical tokens.");
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private Long longValue(
            MultiValueMap<String, String> source, String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw invalid(key + " must be an integer.");
        }
    }

    private LocalDate date(
            MultiValueMap<String, String> source, String key) {
        String value = single(source, key);
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            throw invalid(key + " must be an ISO date.");
        }
    }

    private String single(
            MultiValueMap<String, String> source, String key) {
        List<String> values = source.get(key);
        if (values == null || values.isEmpty()) return null;
        if (values.size() != 1 || values.getFirst() == null
                || values.getFirst().isBlank()) {
            throw invalid(key + " must be one non-blank value.");
        }
        return values.getFirst();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
