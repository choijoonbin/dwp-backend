package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/admin/widget-runtime-controls")
public class AdminWidgetRuntimeControlController {
    private static final String ACTOR = "X-DWP-User-ID";
    private static final String COMMAND = "Idempotency-Key";
    private static final String CORRELATION = "X-Correlation-ID";
    private final WidgetRuntimeControlService controls;

    public AdminWidgetRuntimeControlController(WidgetRuntimeControlService controls) {
        this.controls = controls;
    }

    @GetMapping
    public ApiResponse<WidgetRegistryDtos.RuntimeControlPage> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.success(controls.list(page, size));
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WidgetRegistryDtos.RuntimeControlResponse> disable(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody WidgetRegistryDtos.RuntimeDisableRequest request) {
        return ApiResponse.success(controls.disable(actorId, commandId, correlationId, request));
    }

    @PostMapping("/{controlId}/enable-approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WidgetRegistryDtos.RuntimeEnableApprovalResponse> approve(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID controlId,
            @Valid @RequestBody WidgetRegistryDtos.RuntimeEnableApprovalRequest request) {
        return ApiResponse.success(controls.approveEnable(
                actorId, commandId, correlationId, controlId, request));
    }

    @PostMapping("/{controlId}/enable")
    public ApiResponse<WidgetRegistryDtos.RuntimeControlResponse> enable(
            @RequestHeader(ACTOR) Long actorId,
            @RequestHeader(COMMAND) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID controlId,
            @Valid @RequestBody WidgetRegistryDtos.RuntimeEnableRequest request) {
        return ApiResponse.success(controls.enable(actorId, commandId, correlationId, controlId, request));
    }
}
