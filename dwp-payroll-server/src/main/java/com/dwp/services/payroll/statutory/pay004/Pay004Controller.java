package com.dwp.services.payroll.statutory.pay004;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(
        name = "dwp.features.HRIS_PAY_RETIREMENT_WORKBENCH.enabled",
        havingValue = "true", matchIfMissing = false)
final class Pay004Controller {

    private static final Set<String> COMMON = Set.of(
            "asOf", "cursor", "limit", "locale", "sort", "timeZone",
            "correlationId", "filter");

    private final Pay004Service service;
    private final Pay004ScopeResolver scopes;

    Pay004Controller(Pay004Service service, Pay004ScopeResolver scopes) {
        this.service = service;
        this.scopes = scopes;
    }

    @ModelAttribute("pay004Actor")
    Pay004Authority.Actor actor() {
        Pay004Authority.Subject subject = Pay004RequestContext.require();
        return new Pay004Authority.Actor(subject, scopes.resolve(subject));
    }

    @Operation(operationId = "getPayRetirement")
    @GetMapping("/workbench/pay-retirement")
    Pay004Models.EntryQueryResponse retirement(
            @Parameter(hidden = true)
            @ModelAttribute(value = "pay004Actor", binding = false)
            Pay004Authority.Actor actor,
            @RequestParam(required = false) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam int limit,
            @RequestParam String locale,
            @RequestParam List<String> sort,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestHeader("X-Correlation-ID") UUID headerCorrelationId,
            @RequestParam MultiValueMap<String, String> parameters) {
        if (!correlationId.equals(headerCorrelationId)) {
            throw invalid("Query and transport correlation IDs differ.");
        }
        return service.retirement(actor, new Pay004Models.QueryRequest(
                Pay004Models.VARIANT, asOf, cursor, filters(parameters), limit,
                locale, sort, timeZone, correlationId));
    }

    private Map<String, List<String>> filters(
            MultiValueMap<String, String> parameters) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>(COMMON);
        allowed.addAll(Pay004Models.FILTERS);
        parameters.keySet().stream().filter(key -> !allowed.contains(key))
                .findFirst().ifPresent(key -> {
                    throw invalid("Unknown PAY-004 query parameter: " + key);
                });
        boolean marker = parameters.containsKey("filter");
        boolean nested = parameters.keySet().stream()
                .anyMatch(key -> key.startsWith("filter["));
        if ((!marker && !nested) || marker && nested
                || marker && (parameters.get("filter").size() != 1
                || !"{}".equals(parameters.getFirst("filter")))) {
            throw invalid("filter must use one closed representation.");
        }
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        if (nested) {
            parameters.forEach((key, values) -> {
                if (key.startsWith("filter[")) {
                    result.put(key, List.copyOf(values));
                }
            });
        }
        return Map.copyOf(result);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
