package com.dwp.services.payroll.statutory.pay003;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAYROLL_STATUTORY_INSURANCE.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class Pay003Controller {

    private static final Set<String> COMMON = Set.of(
            "asOf", "cursor", "limit", "locale", "sort", "timeZone",
            "correlationId", "filter");
    private static final Set<String> STATUTORY = Set.of(
            "filter[countryPackMetadata][keys]",
            "filter[countryPackMetadata][versions]",
            "filter[statutoryRule][policyKeys]",
            "filter[statutoryRule][minimumRevision]",
            "filter[activationStatus][codes]",
            "filter[activationStatus][minimumRevision]",
            "filter[effectiveOn]");
    private static final Set<String> RESULTS = Set.of(
            "filter[taxResult][minimumAmount]",
            "filter[taxResult][maximumAmount]",
            "filter[taxResult][currency]",
            "filter[socialInsurance][minimumAmount]",
            "filter[socialInsurance][maximumAmount]",
            "filter[socialInsurance][currency]",
            "filter[policyVersion][policyKeys]",
            "filter[policyVersion][minimumRevision]",
            "filter[effectiveOn]");

    private final Pay003Service service;
    private final Pay003ScopeResolver scopes;

    Pay003Controller(Pay003Service service, Pay003ScopeResolver scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @ModelAttribute("pay003Actor")
    Pay003Authority.Actor actor() {
        Pay003Authority.Subject subject = Pay003RequestContext.require();
        return new Pay003Authority.Actor(subject, scopes.resolve(subject));
    }

    @Operation(operationId = "getStatutoryConfiguration")
    @GetMapping("/workbench/configuration/statutory")
    Pay003Models.EntryQueryResponse statutory(
            @Parameter(hidden = true)
            @ModelAttribute(value = "pay003Actor", binding = false)
            Pay003Authority.Actor actor,
            @RequestParam(required = false) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam int limit,
            @RequestParam String locale,
            @RequestParam List<String> sort,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestHeader("X-Correlation-ID") UUID headerCorrelationId,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireCorrelation(correlationId, headerCorrelationId);
        return service.statutory(actor, query(
                "STATUTORY", asOf, cursor, limit, locale, sort,
                timeZone, correlationId, parameters, STATUTORY));
    }

    @Operation(operationId = "getPayTaxSocialInsurance")
    @GetMapping("/workbench/pay-tax-social-insurance")
    Pay003Models.EntryQueryResponse socialInsurance(
            @Parameter(hidden = true)
            @ModelAttribute(value = "pay003Actor", binding = false)
            Pay003Authority.Actor actor,
            @RequestParam(required = false) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam int limit,
            @RequestParam String locale,
            @RequestParam List<String> sort,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestHeader("X-Correlation-ID") UUID headerCorrelationId,
            @RequestParam MultiValueMap<String, String> parameters) {
        requireCorrelation(correlationId, headerCorrelationId);
        return service.socialInsurance(actor, query(
                "TAX_SOCIAL_INSURANCE", asOf, cursor, limit, locale, sort,
                timeZone, correlationId, parameters, RESULTS));
    }

    @Operation(operationId = "activateCountryPack")
    @PostMapping("/country-packs/{packId}/activate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Pay003Models.PayrollCommandReceipt activate(
            @Parameter(hidden = true)
            @ModelAttribute(value = "pay003Actor", binding = false)
            Pay003Authority.Actor actor,
            @PathVariable String packId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("X-DWP-Expected-Object-Version")
            long trustedExpectedRevision,
            @RequestHeader("X-DWP-Step-Up-Challenge") String challenge,
            @RequestHeader("X-DWP-Expected-Decision-Revision")
            String expectedDecisionRevision,
            @RequestHeader("X-Correlation-ID") UUID correlationId,
            @Valid @RequestBody Pay003Models.ActivationCommand command) {
        String canonicalPackId = Pay003ValueValidation.canonicalUuid(packId, "packId");
        Long parsed = Pay003SecurityFilter.strongIfMatch(ifMatch);
        if (parsed == null || parsed != trustedExpectedRevision) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "If-Match differs from trusted version evidence.");
        }
        return service.activate(
                actor, UUID.fromString(canonicalPackId), command,
                new Pay003Models.ActivationHeaders(
                        idempotencyKey, parsed, challenge,
                        expectedDecisionRevision, correlationId));
    }

    private Pay003Models.QueryRequest query(
            String variant,
            Instant asOf,
            String cursor,
            int limit,
            String locale,
            List<String> sort,
            String timeZone,
            UUID correlationId,
            MultiValueMap<String, String> parameters,
            Set<String> allowedFilters) {
        Set<String> allowed = new java.util.LinkedHashSet<>(COMMON);
        allowed.addAll(allowedFilters);
        parameters.keySet().stream().filter(key -> !allowed.contains(key))
                .findFirst().ifPresent(key -> {
                    throw invalid("Unknown PAY-003 query parameter: " + key);
                });
        boolean marker = parameters.containsKey("filter");
        boolean nested = parameters.keySet().stream()
                .anyMatch(key -> key.startsWith("filter["));
        if ((!marker && !nested) || (marker && nested)
                || (marker && (parameters.get("filter").size() != 1
                || !"{}".equals(parameters.getFirst("filter"))))) {
            throw invalid("filter must use one closed representation.");
        }
        LinkedHashMap<String, List<String>> filter = new LinkedHashMap<>();
        if (nested) {
            parameters.forEach((key, values) -> {
                if (key.startsWith("filter[")) {
                    filter.put(key, List.copyOf(values));
                }
            });
        }
        return new Pay003Models.QueryRequest(
                variant, asOf, cursor, Map.copyOf(filter), limit,
                locale, sort, timeZone, correlationId);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private void requireCorrelation(UUID query, UUID header) {
        if (!query.equals(header)) {
            throw invalid("Query and transport correlation IDs differ.");
        }
    }
}
