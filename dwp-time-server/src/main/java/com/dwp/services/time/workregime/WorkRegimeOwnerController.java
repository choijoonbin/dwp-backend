package com.dwp.services.time.workregime;

import static com.dwp.services.time.workregime.WorkRegimeOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.time.workregime.WorkRegimeApiModels.CreateDraftRequest;
import com.dwp.services.time.workregime.WorkRegimeApiModels.CreateDraftView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.LifecycleRequest;
import com.dwp.services.time.workregime.WorkRegimeApiModels.ReceiptView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SimulationCommandView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SimulationRequest;
import com.dwp.services.time.workregime.WorkRegimeApiModels.StudioView;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeOwnerAuthoritySource.VerifiedRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Owner HTTP adapter protected by the Gateway-verified TIM authority source. */
@RestController
@RequestMapping("/v1/hris")
@ConditionalOnProperty(name = "dwp.time.work-regime-api.enabled", havingValue = "true")
public final class WorkRegimeOwnerController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final WorkRegimeApplicationService service;

    public WorkRegimeOwnerController(WorkRegimeApplicationService service) {
        this.service = service;
    }

    @Operation(operationId = "hrisTimeWorkPlans")
    @GetMapping("/work-plans")
    public ApiResponse<StudioView> list(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedRequest verified,
            @RequestParam LocalDate effectiveOn) {
        return ApiResponse.success(service.list(verified, effectiveOn));
    }

    @Operation(operationId = "hrisTimeCreateWorkPlanDraft")
    @PostMapping("/work-plans/drafts")
    public ApiResponse<CreateDraftView> createDraft(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedRequest verified,
            @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
            @RequestBody CreateDraftRequest request) {
        return ApiResponse.success(service.createDraft(verified, idempotencyKey, request));
    }

    @Operation(operationId = "hrisTimeSimulateWorkPlan")
    @PostMapping("/work-plans/{workPlanId}/simulations")
    public ApiResponse<SimulationCommandView> simulate(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedRequest verified,
            @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID workPlanId,
            @RequestBody SimulationRequest request) {
        return ApiResponse.success(
                service.simulate(verified, workPlanId, idempotencyKey, request));
    }

    @Operation(operationId = "hrisTimeTransitionWorkPlan")
    @PostMapping("/work-plans/{workPlanId}/actions/{action}")
    public ApiResponse<ReceiptView> transition(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedRequest verified,
            @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID workPlanId,
            @Parameter(schema = @Schema(allowableValues = {
                    "apply-approval", "publish", "submit-review", "validate"
            }))
            @PathVariable String action,
            @RequestBody LifecycleRequest request) {
        return ApiResponse.success(service.transition(
                verified,
                workPlanId,
                idempotencyKey,
                lifecycleAction(action),
                request.expectedVersion()));
    }

    @Operation(operationId = "hrisTimeWorkPlanReceipt")
    @GetMapping("/work-plan-receipts/{receiptId}")
    public ApiResponse<SimulationCommandView> receipt(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedRequest verified,
            @PathVariable UUID receiptId) {
        return ApiResponse.success(service.receipt(verified, receiptId));
    }

    private static LifecycleAction lifecycleAction(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "validate" -> LifecycleAction.VALIDATE;
            case "submit-review" -> LifecycleAction.SUBMIT_REVIEW;
            case "apply-approval" -> LifecycleAction.APPLY_APPROVAL;
            case "publish" -> LifecycleAction.PUBLISH;
            default -> throw new IllegalArgumentException("Unsupported lifecycle action");
        };
    }
}
