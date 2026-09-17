package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.auditrecords.ApprovalAuditApiDtos.*;
import static com.dwp.services.approval.auditrecords.ApprovalAuditModels.*;

@RestController
@RequestMapping("/v1/admin/operations/audit-records")
@Tag(name = "Approval audit records")
public class ApprovalAuditController {
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String OBJECT_VERSION = "X-DWP-Expected-Object-Version";
    private static final String DECISION_REVISION = "X-DWP-Expected-Decision-Revision";
    private static final String STEP_UP = "X-DWP-Step-Up-Challenge";

    private final ApprovalAuditService service;
    private final ApprovalAuditCommandFacade commands;
    private final ApprovalAuditHttpAuthority authority;

    public ApprovalAuditController(
            ApprovalAuditService service,
            ApprovalAuditCommandFacade commands,
            ApprovalAuditHttpAuthority authority) {
        this.service = service;
        this.commands = commands;
        this.authority = authority;
    }

    @GetMapping("/events")
    @Operation(summary = "Search scoped Approval audit events")
    public ApiResponse<SearchPage> events(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) Set<String> eventTypes,
            @RequestParam(required = false) Set<String> outcomes,
            @RequestParam(required = false) UUID requestId,
            @RequestParam(required = false) String text,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Instant cursorOccurredAt,
            @RequestParam(required = false) UUID cursorEventId,
            @RequestParam(defaultValue = "METADATA") AccessLevel accessLevel) {
        SearchInput input = new SearchInput(
                from, to, eventTypes, outcomes, requestId, text, limit,
                cursorOccurredAt, cursorEventId);
        return ApiResponse.success(service.search(
                authority.readScope(), input.filter(50), accessLevel));
    }

    @GetMapping("/events/{eventId}")
    @Operation(summary = "Read one scoped and role-redacted Approval audit event")
    public ApiResponse<EventProjection> event(
            @PathVariable UUID eventId,
            @RequestParam(defaultValue = "METADATA") AccessLevel accessLevel) {
        return ApiResponse.success(service.event(
                authority.readScope(), eventId, accessLevel));
    }

    @GetMapping("/requests/{requestId}/retention-linkage")
    @Operation(summary = "Read legal-hold and retention linkage from the owner record")
    public ApiResponse<RequestGovernanceLinkage> retentionLinkage(
            @PathVariable UUID requestId) {
        return ApiResponse.success(service.requestGovernanceLinkage(
                authority.readScope(), requestId));
    }

    @GetMapping("/saved-views")
    @Operation(summary = "List personal and authorized shared audit views")
    public ApiResponse<List<SavedView>> savedViews() {
        return ApiResponse.success(service.savedViews(authority.readScope()));
    }

    @GetMapping("/saved-views/{savedViewId}")
    @Operation(summary = "Read one authorized audit saved view")
    public ApiResponse<SavedView> savedView(@PathVariable UUID savedViewId) {
        return ApiResponse.success(service.savedView(
                authority.readScope(), savedViewId));
    }

    @PostMapping("/saved-views")
    @Operation(summary = "Create an identity-keyed audit saved view")
    public ApiResponse<SavedView> createSavedView(
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp,
            @Valid @RequestBody SavedViewCreate input) {
        return ApiResponse.success(commands.createSavedView(
                input, expectedVersion,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @GetMapping("/exports/{exportId}")
    @Operation(summary = "Read a governed audit export receipt")
    public ApiResponse<ExportReceipt> export(@PathVariable UUID exportId) {
        return ApiResponse.success(service.exportReceipt(
                authority.readScope(), exportId));
    }

    @GetMapping("/exports/{exportId}/verifications")
    @Operation(summary = "List durable digest-recomputation receipts for an audit export")
    public ApiResponse<List<VerificationReceipt>> verifications(
            @PathVariable UUID exportId) {
        return ApiResponse.success(service.verifications(
                authority.readScope(), exportId));
    }

    @PostMapping("/exports")
    @Operation(summary = "Create an identity-keyed governed audit export")
    public ApiResponse<ExportReceipt> createExport(
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp,
            @Valid @RequestBody ExportCreate input) {
        return ApiResponse.success(commands.createExport(
                input, expectedVersion,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/exports/{exportId}/verifications")
    @Operation(summary = "Independently recompute and seal an audit export digest")
    public ApiResponse<VerificationReceipt> verifyExport(
            @PathVariable UUID exportId,
            @RequestHeader(OBJECT_VERSION) long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp,
            @Valid @RequestBody ExportVerification input) {
        return ApiResponse.success(commands.verifyExport(
                exportId, input, expectedVersion,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    @PostMapping("/exports/{exportId}/external-attestations")
    @Operation(
            summary = "Link exact externally verified export-integrity evidence",
            parameters = @Parameter(
                    name = "X-DWP-Expected-Object-Version",
                    in = ParameterIn.HEADER,
                    required = true))
    public ApiResponse<ExportReceipt> linkExternalAttestation(
            @PathVariable UUID exportId,
            @RequestHeader("X-DWP-Expected-Object-Version") long expectedVersion,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(DECISION_REVISION) String decisionRevision,
            @RequestHeader(STEP_UP) String stepUp,
            @Valid @RequestBody ExternalAttestation input) {
        return ApiResponse.success(commands.linkExternalAttestation(
                exportId, input, expectedVersion,
                headers(stepUp, idempotencyKey, decisionRevision, expectedVersion)));
    }

    private ApprovalStepUpHeaders headers(
            String stepUp,
            String idempotencyKey,
            String decisionRevision,
            long expectedVersion) {
        return ApprovalStepUpHeaders.of(
                stepUp, idempotencyKey, decisionRevision, expectedVersion);
    }
}
