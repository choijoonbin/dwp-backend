package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/home-composer/proposals")
public class HomeComposerController {
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String USER = "X-DWP-User-ID";
    private static final String PERMISSIONS = "X-DWP-Permissions";
    private static final String CORRELATION = "X-Correlation-ID";
    private static final String IDEMPOTENCY = "Idempotency-Key";

    private final HomeComposerService service;

    public HomeComposerController(HomeComposerService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<HomeComposerDtos.ComposerProposalResponse> create(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody HomeComposerDtos.CreateComposerProposalRequest request) {
        return ApiResponse.success(service.create(
                tenantId, userId, permissions, commandId, correlationId, request));
    }

    @GetMapping("/{proposalId}")
    public ApiResponse<HomeComposerDtos.ComposerProposalResponse> get(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @PathVariable UUID proposalId) {
        return ApiResponse.success(service.get(tenantId, userId, proposalId));
    }

    @PostMapping("/{proposalId}/apply")
    public ApiResponse<HomeComposerDtos.ComposerProposalResponse> apply(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(value = PERMISSIONS, required = false) String permissions,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID proposalId,
            @Valid @RequestBody HomeComposerDtos.ApplyComposerProposalRequest request) {
        return ApiResponse.success(service.apply(
                tenantId, userId, permissions, proposalId,
                commandId, correlationId, request));
    }

    @PostMapping("/{proposalId}/undo")
    public ApiResponse<HomeComposerDtos.ComposerProposalResponse> undo(
            @RequestHeader(TENANT) Long tenantId,
            @RequestHeader(USER) Long userId,
            @RequestHeader(IDEMPOTENCY) UUID commandId,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID proposalId,
            @Valid @RequestBody HomeComposerDtos.ApplyComposerProposalRequest request) {
        return ApiResponse.success(service.undo(
                tenantId, userId, proposalId, commandId, correlationId, request));
    }
}
