package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.payroll.foundation.PayrollFoundationAccess.Actor;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ConfigurationView;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CreateConfigurationRequest;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.MutationResult;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ReversalCommand;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.UpdateConfigurationRequest;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.VersionCommand;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.WorkspaceView;

/** Settings-only transport adapter; all business behavior remains in PayrollFoundationService. */
@RestController
@RequestMapping(PayrollFoundationSettingsRoute.PATH_PREFIX)
@ConditionalOnProperty(
        name = {
                "dwp.hris.payroll-foundation.wave1.enabled",
                "dwp.hris.payroll-foundation.settings.enabled"
        },
        havingValue = "true",
        matchIfMissing = false)
class PayrollFoundationSettingsController {

    private final PayrollFoundationService service;
    private final PayrollFoundationAccessPolicyProvider accessPolicyProvider;
    private final PayrollLegalEntityScopeResolver legalEntityScopeResolver;

    PayrollFoundationSettingsController(
            PayrollFoundationService service,
            PayrollFoundationAccessPolicyProvider accessPolicyProvider,
            PayrollLegalEntityScopeResolver legalEntityScopeResolver) {
        this.service = service;
        this.accessPolicyProvider = accessPolicyProvider;
        this.legalEntityScopeResolver = legalEntityScopeResolver;
    }

    @ModelAttribute("payrollFoundationSettingsActor")
    Actor actor() {
        PayrollFoundationRequestContext.VerifiedSubject subject =
                PayrollFoundationRequestContext.require();
        return PayrollFoundationAccess.gatewayActor(
                subject.tenantId(),
                subject.actorId(),
                subject.routeAction(),
                subject.projectedActions(),
                subject.executionPurpose(),
                subject.projectionPurpose(),
                subject.contextScopeKey(),
                subject.policyRevision(),
                subject.authorizationRevision(),
                legalEntityScopeResolver.resolve(subject),
                accessPolicyProvider.policyFor(subject.tenantId()));
    }

    @Operation(operationId = "settingsPayrollFoundationConfigurations")
    @GetMapping("/configurations")
    ApiResponse<WorkspaceView> configurations(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollFoundationSettingsActor", binding = false) Actor actor) {
        return ApiResponse.success(service.list(actor));
    }

    @Operation(operationId = "settingsPayrollFoundationCreateConfiguration")
    @PostMapping("/configurations")
    ApiResponse<MutationResult> create(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollFoundationSettingsActor", binding = false) Actor actor,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody CreateConfigurationRequest request) {
        return ApiResponse.success(service.create(actor, commandId, correlationId, request));
    }

    @Operation(operationId = "settingsPayrollFoundationConfiguration")
    @GetMapping("/configurations/{configurationId}")
    ApiResponse<ConfigurationView> configuration(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollFoundationSettingsActor", binding = false) Actor actor,
            @PathVariable UUID configurationId) {
        return ApiResponse.success(service.get(actor, configurationId));
    }

    @Operation(operationId = "settingsPayrollFoundationConfigurationVersions")
    @GetMapping("/configurations/{configurationId}/versions")
    ApiResponse<List<ConfigurationView>> versions(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollFoundationSettingsActor", binding = false) Actor actor,
            @PathVariable UUID configurationId) {
        return ApiResponse.success(service.versions(actor, configurationId));
    }

    @Operation(operationId = "settingsPayrollFoundationUpdateConfiguration")
    @PutMapping("/configurations/{configurationId}")
    ApiResponse<MutationResult> update(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollFoundationSettingsActor", binding = false) Actor actor,
            @PathVariable UUID configurationId,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody UpdateConfigurationRequest request) {
        return ApiResponse.success(
                service.update(actor, configurationId, commandId, correlationId, request));
    }

    @Operation(operationId = "settingsPayrollFoundationSimulateConfiguration")
    @PostMapping("/configurations/{configurationId}/simulations")
    ApiResponse<MutationResult> simulate(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollFoundationSettingsActor", binding = false) Actor actor,
            @PathVariable UUID configurationId,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionCommand command) {
        return ApiResponse.success(
                service.simulate(actor, configurationId, commandId, correlationId, command));
    }

    @Operation(operationId = "settingsPayrollFoundationPublishConfiguration")
    @PostMapping("/configurations/{configurationId}/publish")
    ApiResponse<MutationResult> publish(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollFoundationSettingsActor", binding = false) Actor actor,
            @PathVariable UUID configurationId,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody VersionCommand command) {
        return ApiResponse.success(
                service.publish(actor, configurationId, commandId, correlationId, command));
    }

    @Operation(operationId = "settingsPayrollFoundationReverseConfiguration")
    @PostMapping("/configurations/{configurationId}/reversals")
    ApiResponse<MutationResult> reverse(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollFoundationSettingsActor", binding = false) Actor actor,
            @PathVariable UUID configurationId,
            @RequestHeader("Idempotency-Key") UUID commandId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody ReversalCommand command) {
        return ApiResponse.success(
                service.reverse(actor, configurationId, commandId, correlationId, command));
    }

    @Operation(operationId = "settingsPayrollFoundationCommandReceipt")
    @GetMapping("/receipts/{commandId}")
    ApiResponse<MutationResult> receipt(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollFoundationSettingsActor", binding = false) Actor actor,
            @PathVariable UUID commandId) {
        return ApiResponse.success(service.receipt(actor, commandId));
    }

    @Operation(operationId = "settingsPayrollFoundationReconcileCommand")
    @PostMapping("/receipts/{commandId}/reconcile")
    ApiResponse<MutationResult> reconcile(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollFoundationSettingsActor", binding = false) Actor actor,
            @PathVariable UUID commandId) {
        return ApiResponse.success(service.reconcile(actor, commandId));
    }
}
