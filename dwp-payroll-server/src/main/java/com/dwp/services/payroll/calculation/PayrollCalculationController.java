package com.dwp.services.payroll.calculation;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.payroll.security.PayrollCalculationAuthority;
import com.dwp.services.payroll.security.PayrollCalculationRequestContext;
import com.dwp.services.payroll.security.PayrollCalculationScopeResolver;
import com.dwp.services.payroll.security.PayrollFieldProjectionGuard;
import com.dwp.services.payroll.security.PayrollStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.ActionCommand;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.PayrollCommandReceipt;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.RunResponse;

@RestController
@RequestMapping("/v1")
@ConditionalOnProperty(
        name = "dwp.hris.payroll.calculation.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class PayrollCalculationController {

    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String EXPECTED_VERSION = "X-DWP-Expected-Object-Version";
    static final String EXPECTED_DECISION = "X-DWP-Expected-Decision-Revision";
    static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    static final String CORRELATION = "X-Correlation-ID";

    private final PayrollCalculationService service;
    private final PayrollCalculationScopeResolver scopes;
    private final PayrollFieldProjectionGuard projection;

    PayrollCalculationController(
            PayrollCalculationService service,
            PayrollCalculationScopeResolver scopes,
            PayrollFieldProjectionGuard projection) {
        this.service = service;
        this.scopes = scopes;
        this.projection = projection;
    }

    @ModelAttribute("payrollCalculationActor")
    PayrollCalculationAuthority.Actor actor(
            @PathVariable(value = "receiptId", required = false) UUID receiptId) {
        PayrollCalculationAuthority.Subject subject =
                PayrollCalculationRequestContext.require();
        PayrollCalculationAuthority.Scope scope =
                subject.routeAction()
                        == PayrollCalculationAuthority.Action.RECEIPT_VIEW
                        ? scopes.resolveReceipt(subject, receiptId)
                        : scopes.resolve(subject);
        return new PayrollCalculationAuthority.Actor(subject, scope);
    }

    @Operation(operationId = "calculatePayrollRun")
    @PostMapping("/runs/{runId}/attempts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<PayrollCommandReceipt> calculate(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollCalculationActor", binding = false)
            PayrollCalculationAuthority.Actor actor,
            @PathVariable UUID runId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(EXPECTED_VERSION) long expectedVersion,
            @RequestHeader(value = CORRELATION, required = false) UUID correlationId,
            @Valid @RequestBody ActionCommand command) {
        projection.requireFull(actor.subject());
        return ApiResponse.success(service.calculate(
                actor, runId, idempotencyKey, expectedVersion, correlationId, command));
    }

    @Operation(operationId = "getPayrollRun")
    @GetMapping("/runs/{runId}")
    ApiResponse<RunResponse> run(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollCalculationActor", binding = false)
            PayrollCalculationAuthority.Actor actor,
            @PathVariable UUID runId) {
        projection.requireFull(actor.subject());
        return ApiResponse.success(service.run(actor, runId));
    }

    @Operation(operationId = "approvePayrollRun")
    @PostMapping("/runs/{runId}/approvals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<PayrollCommandReceipt> approve(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollCalculationActor", binding = false)
            PayrollCalculationAuthority.Actor actor,
            @PathVariable("runId") String runIdText,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(EXPECTED_VERSION) long expectedVersion,
            @RequestHeader(EXPECTED_DECISION) String expectedDecisionRevision,
            @RequestHeader(STEP_UP) String challenge,
            @RequestHeader(value = CORRELATION, required = false) UUID correlationId,
            @Valid @RequestBody ActionCommand command) {
        projection.requireFull(actor.subject());
        UUID runId = pathUuid(runIdText);
        return ApiResponse.success(service.approve(
                actor, runId, runIdText, idempotencyKey, expectedVersion,
                correlationId, command,
                new PayrollStepUpHeaders(
                        challenge, idempotencyKey, expectedDecisionRevision, expectedVersion)));
    }

    @Operation(operationId = "finalizePayrollRun")
    @PostMapping("/runs/{runId}/finalize")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<PayrollCommandReceipt> finalizeRun(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollCalculationActor", binding = false)
            PayrollCalculationAuthority.Actor actor,
            @PathVariable("runId") String runIdText,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(EXPECTED_VERSION) long expectedVersion,
            @RequestHeader(EXPECTED_DECISION) String expectedDecisionRevision,
            @RequestHeader(STEP_UP) String challenge,
            @RequestHeader(value = CORRELATION, required = false) UUID correlationId,
            @Valid @RequestBody ActionCommand command) {
        projection.requireFull(actor.subject());
        UUID runId = pathUuid(runIdText);
        return ApiResponse.success(service.finalizeRun(
                actor, runId, runIdText, idempotencyKey, expectedVersion,
                correlationId, command,
                new PayrollStepUpHeaders(
                        challenge, idempotencyKey, expectedDecisionRevision, expectedVersion)));
    }

    @Operation(operationId = "getPayrollCommandReceipt")
    @GetMapping("/command-receipts/{receiptId}")
    ApiResponse<PayrollCommandReceipt> receipt(
            @Parameter(hidden = true)
            @ModelAttribute(value = "payrollCalculationActor", binding = false)
            PayrollCalculationAuthority.Actor actor,
            @PathVariable UUID receiptId) {
        return ApiResponse.success(service.receipt(actor, receiptId));
    }

    private UUID pathUuid(String value) {
        if (value == null || !value.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                        + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.INVALID_INPUT_VALUE,
                    "runId is not a UUID.");
        }
        return UUID.fromString(value);
    }
}
