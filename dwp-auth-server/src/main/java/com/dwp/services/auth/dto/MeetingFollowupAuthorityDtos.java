package com.dwp.services.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Minimal, source-bound contract for Meeting follow-up authority decisions. */
public final class MeetingFollowupAuthorityDtos {

    private MeetingFollowupAuthorityDtos() {
    }

    public enum Action {
        READ,
        CREATE,
        REASSIGN
    }

    public enum Denial {
        AUTHORITY_UNVERIFIED,
        AUTHORITY_REVOKED,
        SCOPE_FORBIDDEN,
        IDENTITY_PLANE_MISMATCH,
        ACTION_NOT_AUTHORIZED
    }

    public record Source(
            @NotNull UUID meetingId,
            @NotNull UUID reportId,
            @NotNull UUID candidateId) {
    }

    public record EvaluateRequest(
            @Positive long tenantId,
            @Positive long actorUserId,
            @NotNull @Valid Source source,
            @NotNull Action action,
            @Positive Long targetAssigneeUserId,
            @PositiveOrZero Long expectedSourceVersion) {
    }

    public record AuthorityResult(
            long tenantId,
            long actorUserId,
            Source source,
            Action action,
            Long targetAssigneeUserId,
            Long expectedSourceVersion,
            boolean allowed,
            Denial denial,
            String authRevision,
            String policyRevision,
            OffsetDateTime validUntil,
            String evidenceRef) {

        public static AuthorityResult allowed(
                EvaluateRequest request,
                ProductSurfaceAuthorityDtos.AuthorityResult authority) {
            return new AuthorityResult(
                    request.tenantId(), request.actorUserId(), request.source(), request.action(),
                    request.targetAssigneeUserId(), request.expectedSourceVersion(), true, null,
                    authority.authRevision(), authority.policyRevision(), authority.revalidateAt(),
                    authority.evidenceRef());
        }

        public static AuthorityResult denied(EvaluateRequest request, Denial denial) {
            return new AuthorityResult(
                    request == null ? 0 : request.tenantId(),
                    request == null ? 0 : request.actorUserId(),
                    request == null ? null : request.source(),
                    request == null ? null : request.action(),
                    request == null ? null : request.targetAssigneeUserId(),
                    request == null ? null : request.expectedSourceVersion(),
                    false, denial, null, null, null, null);
        }
    }
}
