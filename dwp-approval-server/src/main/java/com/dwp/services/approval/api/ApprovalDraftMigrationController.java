package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.domain.ApprovalDraftMigrationDtos;
import com.dwp.services.approval.domain.ApprovalDraftMigrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/requests/{requestId}/draft")
public class ApprovalDraftMigrationController {
    private final ApprovalDraftMigrationService migrations;

    public ApprovalDraftMigrationController(ApprovalDraftMigrationService migrations) {
        this.migrations = migrations;
    }

    @GetMapping("/migration-preview")
    public ApiResponse<ApprovalDraftMigrationDtos.Preview> preview(
            @PathVariable UUID requestId,
            @RequestParam UUID targetFormId,
            @RequestParam UUID targetWorkflowId) {
        return ApiResponse.success(migrations.preview(requestId, targetFormId, targetWorkflowId));
    }

    @PostMapping("/migrate")
    public ApiResponse<ApprovalDraftMigrationDtos.Result> migrate(
            @PathVariable UUID requestId,
            @Valid @RequestBody ApprovalDraftMigrationDtos.MigrateRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(migrations.migrate(requestId, body, idempotencyKey, correlationId));
    }
}
