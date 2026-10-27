package com.dwp.services.time.clockingestion;

import com.dwp.services.time.clockingestion.ClockIngestionModels.RecordClockCommand;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Stable request, payload and command identities for immutable clock writes. */
final class ClockCommandDigests {

    private ClockCommandDigests() {
    }

    static String recordRequestDigest(
            VerifiedClockRequest authority,
            RecordClockCommand command) {
        return ClockDigests.sha256(
                "ClockCommand.v1",
                command.sourcePublicId(),
                command.sourceEventKey(),
                command.sourceSequence(),
                command.sourceType(),
                command.devicePublicId(),
                command.captureMode(),
                command.offlineBatchPublicId(),
                command.workerPublicId(),
                command.peopleAssignmentPublicId(),
                command.peopleAssignmentRevision(),
                command.eventType(),
                command.occurredAt(),
                command.sourceLocalDateTime(),
                command.sourceTimeZone(),
                command.sourceUtcOffsetMinutes(),
                command.dstResolution(),
                command.instantAuthority(),
                command.tzdbVersion(),
                command.timeZoneRuleVersion(),
                command.sourceProvenanceDigest(),
                command.trustLevel(),
                authority.subjectPrincipalPublicId(),
                authority.populationScopeDigest(),
                authority.fieldPolicyRevision(),
                authority.purposeCode(),
                authority.authorizationRevision());
    }

    static String recordPayloadDigest(
            long tenantId,
            UUID eventId,
            Instant receivedAt,
            RecordClockCommand command) {
        return ClockDigests.sha256(
                "ClockEventRecorded.v1",
                tenantId,
                eventId,
                command.workerPublicId(),
                command.peopleAssignmentPublicId(),
                command.eventType(),
                command.occurredAt(),
                receivedAt,
                command.sourcePublicId(),
                command.sourceSequence(),
                command.sourceUtcOffsetMinutes(),
                command.sourceProvenanceDigest());
    }

    static UUID stableId(
            long tenantId,
            UUID subjectId,
            String commandType,
            UUID idempotencyKey) {
        return UUID.nameUUIDFromBytes((tenantId + "|" + subjectId + "|" + commandType + "|"
                + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

}
