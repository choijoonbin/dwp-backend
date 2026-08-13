package com.dwp.services.auth.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PrivilegedAccessDtos {

    private PrivilegedAccessDtos() {
    }

    public record PolicySummary(
            Long policyId,
            Long roleId,
            String roleCode,
            String roleName,
            String activationMode,
            int maximumDurationMinutes,
            String assuranceLevel,
            int approvalQuorum,
            String emergencyMode,
            boolean ticketRequired,
            String lifecycleState,
            long version) {
    }

    public record UpdatePolicyRequest(
            @NotBlank @Pattern(regexp = "SELF_SERVICE|APPROVAL|DISABLED")
                    String activationMode,
            @NotNull @Min(15) @Max(480) Integer maximumDurationMinutes,
            @NotBlank @Pattern(regexp = "SESSION|MFA|PHISHING_RESISTANT")
                    String assuranceLevel,
            @NotNull @Min(1) @Max(2) Integer approvalQuorum,
            @NotBlank @Pattern(regexp = "DISABLED|REGISTERED_PRINCIPAL|DUAL_APPROVAL")
                    String emergencyMode,
            boolean ticketRequired,
            @NotBlank @Pattern(regexp = "ACTIVE|RETIRED") String lifecycleState,
            @NotNull @Min(0) Long version) {
    }

    public record EligibilitySummary(
            UUID eligibilityId,
            String principalType,
            Long principalId,
            String principalDisplayName,
            Long roleId,
            String roleCode,
            String roleName,
            String scopeType,
            String scopeRef,
            Instant validFrom,
            Instant validTo,
            String justification,
            String lifecycleState,
            long version) {
    }

    public record CreateEligibilityRequest(
            @NotBlank @Pattern(regexp = "USER|GROUP") String principalType,
            @NotNull Long principalId,
            @NotNull Long roleId,
            @NotBlank @Pattern(regexp = "TENANT|ORG_UNIT|RESOURCE") String scopeType,
            @Size(max = 160) String scopeRef,
            Instant validFrom,
            @Future Instant validTo,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record ActivationRequest(
            UUID eligibilityId,
            Long roleId,
            @NotBlank @Pattern(regexp = "JIT|EMERGENCY") String requestType,
            @NotNull @Min(15) @Max(480) Integer durationMinutes,
            @NotBlank @Size(min = 10, max = 1000) String justification,
            @Size(max = 160) String ticketReference) {
    }

    public record ApprovalDecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVE|DENY") String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotNull @Min(0) Long version) {
    }

    public record RevokeRequest(
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @NotNull @Min(0) Long version) {
    }

    public record ApprovalSummary(
            Long approverUserId,
            String approverDisplayName,
            String decision,
            String reason,
            Instant decidedAt) {
    }

    public record RequestSummary(
            UUID requestId,
            Long requesterUserId,
            String requesterDisplayName,
            Long roleId,
            String roleCode,
            String roleName,
            UUID eligibilityId,
            String requestType,
            String scopeType,
            String scopeRef,
            int durationMinutes,
            String justification,
            String ticketReference,
            String assuranceLevel,
            int approvalQuorum,
            String lifecycleState,
            Instant requestedAt,
            Instant activatedAt,
            Instant expiresAt,
            Instant revokedAt,
            long version,
            List<ApprovalSummary> approvals) {
    }

    public record RegisterEmergencyPrincipalRequest(
            @NotNull Long userId,
            @NotBlank @Size(min = 10, max = 1000) String justification,
            @NotNull @Future Instant reviewDueAt) {
    }

    public record EmergencyPrincipalSummary(
            UUID emergencyPrincipalId,
            Long userId,
            String displayName,
            String justification,
            Instant reviewDueAt,
            String lifecycleState,
            long version) {
    }

    public record CreateDelegatedScopeRequest(
            @NotNull Long administratorUserId,
            @NotBlank @Pattern(regexp = "TENANT|ORG_UNIT|RESOURCE") String scopeType,
            @Size(max = 160) String scopeRef,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_.-]{2,79}") String actionCode,
            Instant validFrom,
            @Future Instant validTo,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record DelegatedScopeSummary(
            UUID scopeId,
            Long administratorUserId,
            String administratorDisplayName,
            String scopeType,
            String scopeRef,
            String actionCode,
            Instant validFrom,
            Instant validTo,
            String lifecycleState,
            String justification,
            long version) {
    }
}
