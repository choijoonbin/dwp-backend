package com.dwp.services.people.organization;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workforce/organization/scenarios")
public class OrganizationScenarioController {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final OrganizationScenarioService service;
    private final OrganizationScenarioDecisionService decisionService;

    public OrganizationScenarioController(
            OrganizationScenarioService service,
            OrganizationScenarioDecisionService decisionService) {
        this.service = service;
        this.decisionService = decisionService;
    }

    @GetMapping
    public ApiResponse<List<OrganizationScenarioDtos.Scenario>> scenarios() {
        return ApiResponse.success(service.scenarios());
    }

    @PostMapping
    public ApiResponse<OrganizationScenarioDtos.Scenario> create(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.CreateScenarioRequest request) {
        return ApiResponse.success(service.create(request, correlationId));
    }

    @PostMapping("/{scenarioId}/clone")
    public ApiResponse<OrganizationScenarioDtos.Scenario> cloneScenario(
            @PathVariable UUID scenarioId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.CloneScenarioRequest request) {
        return ApiResponse.success(service.cloneScenario(scenarioId, request, correlationId));
    }

    @PostMapping("/{scenarioId}/moves")
    public ApiResponse<OrganizationScenarioDtos.Scenario> addMove(
            @PathVariable UUID scenarioId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.AddOrganizationMoveRequest request) {
        return ApiResponse.success(service.addMove(scenarioId, request, correlationId));
    }

    @PostMapping("/{scenarioId}/position-moves")
    public ApiResponse<OrganizationScenarioDtos.Scenario> addPositionMove(
            @PathVariable UUID scenarioId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.AddPositionMoveRequest request) {
        return ApiResponse.success(service.addPositionMove(scenarioId, request, correlationId));
    }

    @PostMapping("/{scenarioId}/positions")
    public ApiResponse<OrganizationScenarioDtos.Scenario> createPosition(
            @PathVariable UUID scenarioId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.CreatePositionRequest request) {
        return ApiResponse.success(service.createPosition(scenarioId, request, correlationId));
    }

    @PostMapping("/{scenarioId}/positions/{positionId}/close")
    public ApiResponse<OrganizationScenarioDtos.Scenario> closePosition(
            @PathVariable UUID scenarioId,
            @PathVariable UUID positionId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.ClosePositionRequest request) {
        return ApiResponse.success(service.closePosition(
                scenarioId, positionId, request, correlationId));
    }

    @GetMapping("/{scenarioId}/decision-pack")
    public ApiResponse<OrganizationScenarioDtos.DecisionPack> decisionPack(
            @PathVariable UUID scenarioId) {
        return ApiResponse.success(decisionService.preview(scenarioId));
    }

    @GetMapping("/{scenarioId}/decision-pack/history")
    public ApiResponse<List<OrganizationScenarioDtos.ValidationRunSummary>> decisionPackHistory(
            @PathVariable UUID scenarioId) {
        return ApiResponse.success(decisionService.history(scenarioId));
    }

    @PostMapping("/{scenarioId}/decision-pack/validate")
    public ApiResponse<OrganizationScenarioDtos.DecisionPack> validateDecisionPack(
            @PathVariable UUID scenarioId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.ValidateScenarioRequest request) {
        return ApiResponse.success(decisionService.validate(scenarioId, request, correlationId));
    }

    @DeleteMapping("/{scenarioId}/changes/{changeId}")
    public ApiResponse<OrganizationScenarioDtos.Scenario> removeChange(
            @PathVariable UUID scenarioId,
            @PathVariable UUID changeId,
            @RequestParam long version,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        return ApiResponse.success(service.removeChange(
                scenarioId, changeId, version, correlationId));
    }

    @PostMapping("/{scenarioId}/submit")
    public ApiResponse<OrganizationScenarioDtos.Scenario> submit(
            @PathVariable UUID scenarioId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.SubmitScenarioRequest request) {
        return ApiResponse.success(service.submit(scenarioId, request, correlationId));
    }

    @PostMapping("/{scenarioId}/approval")
    public ApiResponse<OrganizationScenarioDtos.Scenario> decide(
            @PathVariable UUID scenarioId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.DecideScenarioRequest request) {
        return ApiResponse.success(service.decide(scenarioId, request, correlationId));
    }

    @PostMapping("/{scenarioId}/cancel")
    public ApiResponse<OrganizationScenarioDtos.Scenario> cancel(
            @PathVariable UUID scenarioId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.CancelScenarioRequest request) {
        return ApiResponse.success(service.cancel(scenarioId, request, correlationId));
    }

    @PostMapping("/{scenarioId}/publish")
    public ApiResponse<OrganizationScenarioDtos.Scenario> publish(
            @PathVariable UUID scenarioId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody OrganizationScenarioDtos.PublishScenarioRequest request) {
        return ApiResponse.success(service.publish(scenarioId, request, correlationId));
    }
}
