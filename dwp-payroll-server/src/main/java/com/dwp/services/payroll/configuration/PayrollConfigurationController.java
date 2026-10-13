package com.dwp.services.payroll.configuration;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.payroll.configuration.PayrollConfigurationAccess.Actor;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CreateElementVersionRequest;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CreateFormulaVersionRequest;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CommandReceipt;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.MutationResult;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PolicyFilter;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.SimulationCommand;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.StatusFilter;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.VersionCommand;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.WorkbenchFilter;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.WorkbenchQuery;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.WorkbenchView;

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(
        name = "dwp.hris.payroll-configuration.wave2.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollConfigurationController {

    static final String EXPECTED_VERSION = "X-DWP-Expected-Object-Version";

    private final PayrollConfigurationService service;
    private final PayrollConfigurationScopeResolver scopeResolver;

    PayrollConfigurationController(
            PayrollConfigurationService service,
            PayrollConfigurationScopeResolver scopeResolver) {
        this.service = service;
        this.scopeResolver = scopeResolver;
    }

    @ModelAttribute("payrollConfigurationActor")
    Actor actor() {
        PayrollConfigurationRequestContext.VerifiedSubject subject =
                PayrollConfigurationRequestContext.require();
        PayrollConfigurationScopeResolver.Scope scope = scopeResolver.resolve(subject);
        return new Actor(
                subject.tenantId(), subject.actorId(), subject.subjectPrincipalPublicId(),
                subject.projectedActions(), scope.legalEntityIds(), scope.payrollGroupIds(),
                subject.purposeCode(), scope.scopeDigest(), subject.fieldPolicyRevision(),
                scope.scopeDecisionRevision(), subject.authorizationRevision());
    }

    @Operation(operationId = "payrollSettingsWorkbench")
    @GetMapping("/workbench/settings-payroll")
    ApiResponse<WorkbenchView> workbench(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollConfigurationActor", binding = false)
            Actor actor,
            @RequestParam(value = "asOf", required = false) Instant asOf,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam("limit") int limit,
            @RequestParam("locale") String locale,
            @RequestParam("sort") List<String> sort,
            @RequestParam("timeZone") String timeZone,
            @RequestParam("correlationId") UUID correlationId,
            @RequestParam(value = "filter.effectiveOn", required = false)
            LocalDate effectiveOn,
            @RequestParam(value = "filter.effectiveStatus.codes", required = false)
            List<String> effectiveStatusCodes,
            @RequestParam(value = "filter.effectiveStatus.minimumRevision", required = false)
            Long effectiveStatusMinimumRevision,
            @RequestParam(value = "filter.payrollPolicy.policyKeys", required = false)
            List<String> payrollPolicyKeys,
            @RequestParam(value = "filter.payrollPolicy.minimumRevision", required = false)
            Long payrollPolicyMinimumRevision,
            @RequestParam(value = "filter.policyVersion.policyKeys", required = false)
            List<String> policyVersionKeys,
            @RequestParam(value = "filter.policyVersion.minimumRevision", required = false)
            Long policyVersionMinimumRevision) {
        WorkbenchQuery query = new WorkbenchQuery(
                asOf, cursor,
                new WorkbenchFilter(
                        effectiveOn,
                        effectiveStatusCodes == null && effectiveStatusMinimumRevision == null
                                ? null : new StatusFilter(
                                effectiveStatusCodes, effectiveStatusMinimumRevision),
                        payrollPolicyKeys == null && payrollPolicyMinimumRevision == null
                                ? null : new PolicyFilter(
                                payrollPolicyKeys, payrollPolicyMinimumRevision),
                        policyVersionKeys == null && policyVersionMinimumRevision == null
                                ? null : new PolicyFilter(
                                policyVersionKeys, policyVersionMinimumRevision)),
                limit, locale, sort, timeZone, correlationId);
        return ApiResponse.success(service.workbench(actor, query));
    }

    @Operation(operationId = "payrollElementVersionCreate")
    @PostMapping("/elements/versions")
    ApiResponse<CommandReceipt> createElementVersion(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollConfigurationActor", binding = false)
            Actor actor,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader("X-Correlation-Id") UUID correlationId,
            @RequestHeader(value = EXPECTED_VERSION, required = false) Long expectedVersion,
            @Valid @RequestBody CreateElementVersionRequest request) {
        requireExpectedHeader(expectedVersion, request.expectedVersion());
        return ApiResponse.success(service.createElementVersion(
                actor, commandId, correlationId.toString(), request).receipt());
    }

    @Operation(operationId = "payrollFormulaVersionCreate")
    @PostMapping("/formulas/versions")
    ApiResponse<CommandReceipt> createFormulaVersion(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollConfigurationActor", binding = false)
            Actor actor,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader("X-Correlation-Id") UUID correlationId,
            @RequestHeader(value = EXPECTED_VERSION, required = false) Long expectedVersion,
            @Valid @RequestBody CreateFormulaVersionRequest request) {
        requireExpectedHeader(expectedVersion, request.expectedVersion());
        return ApiResponse.success(service.createFormulaVersion(
                actor, commandId, correlationId.toString(), request).receipt());
    }

    @Operation(operationId = "payrollFormulaValidate")
    @PostMapping("/formulas/{formulaId}/validations")
    ApiResponse<CommandReceipt> validateFormula(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollConfigurationActor", binding = false)
            Actor actor,
            @PathVariable UUID formulaId,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader("X-Correlation-Id") UUID correlationId,
            @RequestHeader(EXPECTED_VERSION) long expectedVersion,
            @Valid @RequestBody VersionCommand command) {
        requireExpectedHeader(expectedVersion, command.expectedVersion());
        return ApiResponse.success(service.validateFormula(
                actor, formulaId, commandId, correlationId.toString(), command).receipt());
    }

    @Operation(operationId = "payrollFormulaSimulate")
    @PostMapping("/formulas/{formulaId}/simulations")
    ApiResponse<CommandReceipt> simulateFormula(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollConfigurationActor", binding = false)
            Actor actor,
            @PathVariable UUID formulaId,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader("X-Correlation-Id") UUID correlationId,
            @RequestHeader(EXPECTED_VERSION) long expectedVersion,
            @Valid @RequestBody SimulationCommand command) {
        requireExpectedHeader(expectedVersion, command.expectedVersion());
        return ApiResponse.success(service.simulateFormula(
                actor, formulaId, commandId, correlationId.toString(), command).receipt());
    }

    @Operation(operationId = "payrollFormulaPublish")
    @PostMapping("/formulas/{formulaId}/publish")
    ApiResponse<CommandReceipt> publishFormula(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollConfigurationActor", binding = false)
            Actor actor,
            @PathVariable UUID formulaId,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader("X-Correlation-Id") UUID correlationId,
            @RequestHeader(EXPECTED_VERSION) long expectedVersion,
            @RequestHeader("X-DWP-Step-Up-Challenge") String stepUpChallenge,
            @Valid @RequestBody VersionCommand command) {
        requireExpectedHeader(expectedVersion, command.expectedVersion());
        if (stepUpChallenge.isBlank()) {
            throw new BaseException(
                    ErrorCode.STEP_UP_REQUIRED,
                    "X-DWP-Step-Up-Challenge is required.");
        }
        return ApiResponse.success(service.publishFormula(
                actor, formulaId, commandId, correlationId.toString(), command).receipt());
    }

    private void requireExpectedHeader(Long header, Long body) {
        if (!Objects.equals(header, body)) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "X-DWP-Expected-Object-Version must match body expectedVersion.");
        }
    }
}
