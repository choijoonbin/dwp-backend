package com.dwp.services.platform.activity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Privacy-minimized observations; neither a source permission grant nor a compliance attestation. */
public final class ActivityEvidenceDtos {
    private ActivityEvidenceDtos() { }

    @Schema(name = "ActivityEvidenceReceipt", description = "Authorized audit linkage and the audit owner's reported daily checkpoint, not a compliance attestation.")
    public record Evidence(
            UUID eventId, UUID auditRecordId,
            @Schema(allowableValues = {"LINKED", "NOT_LINKED"}) String linkStatus,
            @Schema(allowableValues = {"AVAILABLE", "RESTRICTED"}) String auditAccess,
            @Schema(nullable = true, pattern = "^[0-9a-f]{64}$") String recordHash,
            @Schema(nullable = true, allowableValues = {"SHA-256"}) String hashAlgorithm,
            @Schema(allowableValues = {"VERIFIED", "FAILED", "PENDING", "UNAVAILABLE"}) String integrityStatus,
            @Schema(allowableValues = {"DAILY_CHECKPOINT_REPORTED"}) String integrityScope,
            OffsetDateTime verifiedAt, OffsetDateTime observedAt) { }

    @Schema(name = "ActivitySourceStatus", description = "A personal source ledger observation; LOCAL_FIXTURE is explicitly seeded development data.")
    public record SourceStatus(
            String sourceId, String label,
            @Schema(allowableValues = {"MAIL", "CALENDAR"}) String resourceKind,
            @Schema(allowableValues = {"READY", "SYNCING", "STALE", "RESET_REQUIRED", "AUTHENTICATION_REQUIRED", "SUSPENDED", "NOT_CONNECTED", "REAUTHORIZATION_REQUIRED", "REVOKED", "CONFIGURATION_REQUIRED", "DEGRADED", "UNAVAILABLE", "DRAFT", "REVIEW_REQUIRED", "BLOCKED"}) String status,
            OffsetDateTime lastAttemptAt, OffsetDateTime lastSuccessAt,
            OffsetDateTime observedAt,
            @Schema(allowableValues = {"PERSONAL_SYNC_LEDGER", "LOCAL_FIXTURE"}) String semantics) { }

    @Schema(name = "ActivitySourceStatuses")
    public record SourceStatuses(OffsetDateTime observedAt, List<SourceStatus> sources) { }
}
