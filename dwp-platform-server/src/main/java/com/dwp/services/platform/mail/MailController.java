package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

@RestController
@RequestMapping("/v1/mail")
public class MailController {

    private final MailService service;
    private final MailDraftService drafts;
    private final MailWorkspaceService workspace;
    private final MailProposalOutcomePort proposalOutcomes;

    public MailController(MailService service, MailDraftService drafts) {
        this(service, drafts, null, null);
    }

    public MailController(
            MailService service,
            MailDraftService drafts,
            MailWorkspaceService workspace) {
        this(service, drafts, workspace, null);
    }

    @Autowired
    public MailController(
            MailService service,
            MailDraftService drafts,
            MailWorkspaceService workspace,
            MailProposalOutcomePort proposalOutcomes) {
        this.service = service;
        this.drafts = drafts;
        this.workspace = workspace;
        this.proposalOutcomes = proposalOutcomes;
    }

    @GetMapping("/home")
    public ApiResponse<MailDtos.HomeResponse> home(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestParam(required = false) UUID accountId) {
        return ApiResponse.success(service.home(tenantId, userId, accountId));
    }

    @GetMapping("/threads")
    public ApiResponse<MailDtos.ThreadPage> threads(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestParam(required = false, defaultValue = "") String lane,
            @RequestParam(required = false, defaultValue = "") String state,
            @RequestParam(required = false, defaultValue = "") String folder,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(defaultValue = "false") boolean sharedOnly,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false, defaultValue = "") String scope,
            @RequestParam(required = false) UUID sharedInboxId,
            @RequestParam(required = false, defaultValue = "") String assignment,
            @RequestParam(name = "from", required = false, defaultValue = "") String sender,
            @RequestParam(name = "to", required = false, defaultValue = "") String recipient,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) Boolean needsReply,
            @RequestParam(required = false) Boolean hasAttachment,
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.success(service.threadsAdvanced(
                tenantId, userId, lane, state, folder, folderId,
                sharedOnly, query, accountId, scope, sharedInboxId, assignment,
                sender, recipient, dateFrom, dateTo, unread, needsReply,
                hasAttachment, page, pageSize));
    }

    @PostMapping("/messages")
    public ApiResponse<MailDtos.ThreadDetail> compose(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MailDtos.ComposeRequest request) {
        if (workspace != null
                && request.deliveryMode() == com.dwp.services.platform.mail.MailTypes.DeliveryMode.SEND
                && request.composeOptions() != null) {
            return ApiResponse.success(workspace.compose(
                    tenantId, userId, correlationId, request));
        }
        return ApiResponse.success(service.compose(
                tenantId, userId, correlationId, request));
    }

    @PostMapping("/drafts")
    public ApiResponse<MailDtos.ThreadDetail> createDraft(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MailDtos.DraftSaveRequest request) {
        MailDtos.ThreadDetail detail = drafts.create(
                tenantId, userId, correlationId, request);
        if (workspace != null && request.composeOptions() != null) {
            workspace.saveDraftOptions(
                    tenantId, userId, detail.thread().threadId(), request.composeOptions());
            detail = workspace.enrichDraft(tenantId, userId, detail);
        }
        return ApiResponse.success(detail);
    }

    @PutMapping("/drafts/{threadId}")
    public ApiResponse<MailDtos.ThreadDetail> saveDraft(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailDtos.DraftSaveRequest request) {
        MailDtos.ThreadDetail detail = drafts.save(
                tenantId, userId, threadId, correlationId, request);
        if (workspace != null && request.composeOptions() != null) {
            workspace.saveDraftOptions(tenantId, userId, threadId, request.composeOptions());
            detail = workspace.enrichDraft(tenantId, userId, detail);
        }
        return ApiResponse.success(detail);
    }

    @PutMapping("/threads/{threadId}/draft")
    public ApiResponse<MailDtos.ThreadDetail> updateDraft(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailDtos.DraftUpdateRequest request) {
        if (workspace != null
                && request.deliveryMode() == com.dwp.services.platform.mail.MailTypes.DeliveryMode.SEND
                && request.composeOptions() != null) {
            return ApiResponse.success(workspace.sendDraft(
                    tenantId, userId, threadId, correlationId, request));
        }
        return ApiResponse.success(service.updateDraft(
                tenantId, userId, threadId, correlationId, request));
    }

    @GetMapping("/threads/{threadId}")
    public ApiResponse<MailDtos.ThreadDetail> thread(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID threadId) {
        MailDtos.ThreadDetail detail = service.thread(tenantId, userId, threadId);
        if (workspace != null
                && detail.thread().workflowState()
                == com.dwp.services.platform.mail.MailTypes.WorkflowState.DRAFT) {
            detail = workspace.enrichDraft(tenantId, userId, detail);
        }
        return ApiResponse.success(detail);
    }

    @PostMapping("/threads/{threadId}/actions")
    public ApiResponse<MailDtos.ThreadDetail> action(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailDtos.ThreadActionRequest request) {
        return ApiResponse.success(service.applyAction(
                tenantId, userId, threadId, correlationId, request));
    }

    @PostMapping("/threads/{threadId}/snooze")
    public ApiResponse<MailDtos.ThreadDetail> snooze(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailDtos.SnoozeRequest request) {
        return ApiResponse.success(service.snooze(
                tenantId, userId, threadId, correlationId, request));
    }

    @PostMapping("/threads/{threadId}/assignment")
    public ApiResponse<MailDtos.ThreadDetail> assign(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailDtos.AssignRequest request) {
        return ApiResponse.success(service.assign(
                tenantId, userId, threadId, correlationId, request));
    }

    @PostMapping("/threads/{threadId}/comments")
    public ApiResponse<MailDtos.ThreadDetail> comment(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Display-Name-B64", required = false) String displayName,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailDtos.CommentRequest request) {
        return ApiResponse.success(service.comment(
                tenantId, userId, decoded(displayName), threadId, correlationId, request));
    }

    @PostMapping("/threads/{threadId}/replies")
    public ApiResponse<MailDtos.ThreadDetail> reply(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-DWP-Mail-Proposal-ID", required = false) UUID proposalId,
            @RequestHeader(value = "X-DWP-Mail-Command-ID", required = false) UUID commandId,
            @RequestHeader(value = "X-DWP-Mail-Proposal-Version", required = false) Long proposalVersion,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailDtos.ReplyRequest request) {
        return ApiResponse.success(service.reply(
                tenantId, userId, threadId, correlationId, request,
                MailProposalHandoffBinding.optional(
                        proposalId, commandId, proposalVersion)));
    }

    @PostMapping("/threads/{threadId}/messages/{messageId}/retry")
    public ApiResponse<MailDtos.ThreadDetail> retryDelivery(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID threadId,
            @PathVariable UUID messageId) {
        return ApiResponse.success(service.retryDelivery(
                tenantId, userId, threadId, messageId, correlationId));
    }

    @PostMapping("/proposals/{proposalId}/decision")
    public ApiResponse<MailDtos.ActionProposal> decideProposal(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID proposalId,
            @Valid @RequestBody MailDtos.ProposalDecisionRequest request) {
        return ApiResponse.success(service.decideProposal(
                tenantId, userId, permissions, proposalId, correlationId, request));
    }

    @GetMapping("/proposals")
    public ApiResponse<java.util.List<MailDtos.ActionProposal>> proposals(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestParam(required = false, defaultValue = "") String status,
            @RequestParam(required = false, defaultValue = "") String type) {
        return ApiResponse.success(service.proposals(tenantId, userId, status, type));
    }

    @PutMapping("/proposals/{proposalId}")
    public ApiResponse<MailDtos.ActionProposal> updateProposal(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID proposalId,
            @Valid @RequestBody MailDtos.ProposalUpdateRequest request) {
        return ApiResponse.success(service.updateProposal(
                tenantId, userId, proposalId, correlationId, request));
    }

    @GetMapping("/proposals/{proposalId}/handoff")
    public ApiResponse<MailDtos.ProposalHandoff> proposalHandoff(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID proposalId) {
        return ApiResponse.success(service.proposalHandoff(tenantId, userId, proposalId));
    }

    @PostMapping("/proposals/{proposalId}/handoff/cancel")
    public ApiResponse<MailDtos.ProposalHandoff> cancelProposalHandoff(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID proposalId,
            @Valid @RequestBody MailDtos.ProposalHandoffCancelRequest request) {
        if (proposalOutcomes == null) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The Mail proposal owner service is unavailable.");
        }
        return ApiResponse.success(proposalOutcomes.cancel(
                tenantId, userId, proposalId, request.commandId(),
                request.version(), correlationId));
    }

    private String decoded(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(value.trim()), StandardCharsets.UTF_8).trim();
            return decoded.isBlank() || decoded.length() > 160 ? null : decoded;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
