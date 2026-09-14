package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.domain.ApprovalDraftService;
import com.dwp.services.approval.domain.ApprovalWorkDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ApprovalDraftController {
    private final ApprovalDraftService drafts;

    public ApprovalDraftController(ApprovalDraftService drafts) { this.drafts = drafts; }

    @GetMapping("/requests/{requestId}/draft/revisions")
    public ApiResponse<ApprovalWorkDtos.Page<ApprovalWorkDtos.DraftRevision>> revisions(@PathVariable UUID requestId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return ApiResponse.success(drafts.revisions(requestId, page, size));
    }

    @GetMapping("/requests/{requestId}/draft/revisions/{revision}")
    public ApiResponse<ApprovalWorkDtos.DraftRevisionDetail> revision(@PathVariable UUID requestId, @PathVariable int revision) {
        return ApiResponse.success(drafts.revision(requestId, revision));
    }

    @PostMapping("/requests/{requestId}/draft/recover")
    public ApiResponse<ApprovalWorkDtos.DraftState> recover(@PathVariable UUID requestId,
            @Valid @RequestBody ApprovalWorkDtos.RecoverDraft body,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(drafts.recover(requestId, body, correlationId));
    }

    @PostMapping("/requests/{requestId}/draft/delete")
    public ApiResponse<ApprovalWorkDtos.DraftState> delete(@PathVariable UUID requestId,
            @Valid @RequestBody ApprovalWorkDtos.DraftCommand body,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(drafts.delete(requestId, body, correlationId));
    }

    @PostMapping("/requests/{requestId}/draft/restore")
    public ApiResponse<ApprovalWorkDtos.DraftState> restore(@PathVariable UUID requestId,
            @Valid @RequestBody ApprovalWorkDtos.DraftCommand body,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        return ApiResponse.success(drafts.restore(requestId, body, correlationId));
    }

    @GetMapping("/draft-commands/{idempotencyKey}")
    public ApiResponse<ApprovalWorkDtos.DraftReconciliation> reconcile(@PathVariable String idempotencyKey) {
        return ApiResponse.success(drafts.reconcile(idempotencyKey));
    }
}
