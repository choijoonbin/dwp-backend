package com.dwp.services.people.hr.performance.populationfreeze;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.people.security.HcmStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/hris/performance")
@ConditionalOnProperty(
        name = "dwp.hris.performance.population-freeze.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class PopulationFreezeController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String IF_MATCH_HEADER = "If-Match";
    static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final PopulationFreezeService service;

    public PopulationFreezeController(PopulationFreezeService service) {
        this.service = service;
    }

    @Operation(operationId = "hrisPerformanceFreezePopulation")
    @PostMapping("/cycles/{cycleId}/population-freezes")
    public ApiResponse<PopulationFreezeDtos.FreezeCommandResult> freeze(
            @PathVariable UUID cycleId,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestHeader(IF_MATCH_HEADER) long expectedCycleRevision,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @RequestHeader(HcmStepUpHeaders.CHALLENGE) String challenge,
            @RequestHeader(HcmStepUpHeaders.DECISION_REVISION) String decisionRevision,
            @RequestHeader(HcmStepUpHeaders.EXPECTED_OBJECT_VERSION)
            Long expectedObjectVersion,
            @Valid @RequestBody PopulationFreezeDtos.FreezePopulationRequest request) {
        return ApiResponse.success(service.freeze(
                cycleId, expectedCycleRevision, request, idempotencyKey, correlationId,
                new HcmStepUpHeaders(challenge, idempotencyKey,
                        decisionRevision, expectedObjectVersion)));
    }
}
