package com.dwp.services.platform.mail;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
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
import java.util.Base64;
import java.util.UUID;

@RestController
@RequestMapping("/v1/mail")
public class MailController {

    private final MailService service;

    public MailController(MailService service) {
        this.service = service;
    }

    @GetMapping("/home")
    public ApiResponse<MailDtos.HomeResponse> home(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId) {
        return ApiResponse.success(service.home(tenantId, userId));
    }

    @GetMapping("/threads")
    public ApiResponse<MailDtos.ThreadPage> threads(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestParam(required = false, defaultValue = "") String lane,
            @RequestParam(required = false, defaultValue = "") String state,
            @RequestParam(required = false, defaultValue = "") String folder,
            @RequestParam(defaultValue = "false") boolean sharedOnly,
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        return ApiResponse.success(service.threads(
                tenantId, userId, lane, state, folder, sharedOnly, query, page, pageSize));
    }

    @PostMapping("/messages")
    public ApiResponse<MailDtos.ThreadDetail> compose(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody MailDtos.ComposeRequest request) {
        return ApiResponse.success(service.compose(
                tenantId, userId, correlationId, request));
    }

    @PutMapping("/threads/{threadId}/draft")
    public ApiResponse<MailDtos.ThreadDetail> updateDraft(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID threadId,
            @Valid @RequestBody MailDtos.DraftUpdateRequest request) {
        return ApiResponse.success(service.updateDraft(
                tenantId, userId, threadId, correlationId, request));
    }

    @GetMapping("/threads/{threadId}")
    public ApiResponse<MailDtos.ThreadDetail> thread(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @PathVariable UUID threadId) {
        return ApiResponse.success(service.thread(tenantId, userId, threadId));
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
            @PathVariable UUID threadId,
            @Valid @RequestBody MailDtos.ReplyRequest request) {
        return ApiResponse.success(service.reply(
                tenantId, userId, threadId, correlationId, request));
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
