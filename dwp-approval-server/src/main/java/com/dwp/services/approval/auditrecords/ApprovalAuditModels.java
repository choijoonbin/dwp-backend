package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ApprovalAuditModels {
    private ApprovalAuditModels() {
    }

    public enum Capability {
        VIEW,
        VIEW_PRIVILEGED,
        VIEW_AUDITOR,
        MANAGE_PERSONAL_VIEWS,
        MANAGE_SHARED_VIEWS,
        EXPORT,
        VERIFY_EXPORT,
        LINK_EXTERNAL_ATTESTATION
    }

    public enum AccessLevel {
        METADATA,
        PRIVILEGED,
        AUDITOR
    }

    public enum Visibility {
        PERSONAL,
        SHARED
    }

    public record Scope(
            long tenantId,
            String resourceSetKey,
            long actorUserId,
            Set<Capability> capabilities) {
        public Scope {
            if (tenantId <= 0 || actorUserId <= 0
                    || resourceSetKey == null
                    || !resourceSetKey.matches("[A-Z][A-Z0-9_]{2,79}")) {
                throw new IllegalArgumentException("The audit scope is invalid.");
            }
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }

        public void require(Capability capability) {
            if (!capabilities.contains(capability)) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "The audit capability is not available.");
            }
        }
    }

    public record Cursor(Instant occurredAt, UUID eventId) {
        public Cursor {
            if (occurredAt == null || eventId == null) {
                throw new IllegalArgumentException("The audit cursor is incomplete.");
            }
        }
    }

    public record SearchFilter(
            Instant from,
            Instant to,
            Set<String> eventTypes,
            Set<String> outcomes,
            UUID requestId,
            String text,
            int limit,
            Cursor cursor) {
        public SearchFilter {
            eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
            outcomes = outcomes == null ? Set.of() : Set.copyOf(outcomes);
            text = text == null ? null : text.strip();
        }
    }

    public record ActorProjection(String type, String identifier, boolean pseudonymized) {
    }

    public record RetentionLinkage(
            String status,
            boolean legalHoldActive,
            boolean legalHoldPending,
            Instant retainUntil) {
    }

    public record RequestGovernanceLinkage(
            UUID requestId,
            RetentionLinkage retention,
            String legalHoldAuthority,
            String canonicalLegalHoldPath,
            Instant evaluatedAt) {
    }

    public record EventProjection(
            UUID eventId,
            UUID requestId,
            String requestNumber,
            String eventType,
            String outcome,
            ActorProjection actor,
            String message,
            Map<String, Object> evidence,
            RetentionLinkage retention,
            Instant occurredAt) {
    }

    public record SearchPage(
            Instant generatedAt,
            AccessLevel accessLevel,
            List<EventProjection> events,
            Cursor nextCursor) {
    }

    public record SavedView(
            UUID savedViewId,
            String name,
            Visibility visibility,
            SearchFilter filter,
            long ownerUserId,
            long version,
            Instant updatedAt) {
    }

    public record ExportEntry(
            UUID eventId,
            UUID requestId,
            String evidenceSha256,
            RetentionLinkage retention) {
    }

    public record ExportManifest(
            UUID exportId,
            Instant generatedAt,
            String resourceSetKey,
            AccessLevel accessLevel,
            int eventCount,
            List<ExportEntry> entries,
            Map<String, Integer> retentionSummary,
            List<String> assuranceBoundaries) {
    }

    public record ExportReceipt(
            UUID exportId,
            String status,
            String integrityStatus,
            String manifestSha256,
            ExportManifest manifest,
            String externalAttestationType,
            String externalAttestationReference,
            Instant externalAttestedAt,
            String externalAttestationIssuer,
            String externalAttestorIdentity,
            String externalAttestationKeyId,
            String externalVerificationReference,
            long version,
            Instant completedAt) {
    }

    public record ExternalAttestationSubmission(
            String type,
            String reference,
            Instant attestedAt,
            String evidencePayloadBase64Url,
            String evidenceSignatureBase64Url) {
    }

    public record VerifiedExternalAttestation(
            String type,
            String reference,
            Instant attestedAt,
            String issuer,
            String attestorIdentity,
            String keyId,
            String verificationReference) {
    }

    public record VerificationCommand(
            UUID verificationId,
            long expectedExportVersion) {
    }

    public record VerificationReceipt(
            UUID verificationId,
            UUID exportId,
            long exportVersion,
            String manifestSha256,
            String recomputedSha256,
            String result,
            long verifiedBy,
            Instant verifiedAt) {
    }
}
