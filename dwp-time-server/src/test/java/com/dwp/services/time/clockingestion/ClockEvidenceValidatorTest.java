package com.dwp.services.time.clockingestion;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.clockingestion.ClockIngestionModels.CaptureMode;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEventType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockSourceType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.DstResolution;
import com.dwp.services.time.clockingestion.ClockIngestionModels.InstantAuthority;
import com.dwp.services.time.clockingestion.ClockIngestionModels.RecordClockCommand;
import com.dwp.services.time.clockingestion.ClockIngestionModels.TrustLevel;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClockEvidenceValidatorTest {

    private static final UUID SOURCE = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DEVICE = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID WORKER = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID ASSIGNMENT = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID BATCH = UUID.fromString("10000000-0000-4000-8000-000000000005");

    private final ClockEvidenceValidator validator = new ClockEvidenceValidator();

    @Test
    void acceptsReproducibleOnlineEvidenceAndRejectsDigestMutation() {
        RecordClockCommand valid = command(
                "UTC",
                LocalDateTime.parse("2026-10-12T01:30:00"),
                Instant.parse("2026-10-12T01:30:00Z"),
                0,
                DstResolution.EXACT_OFFSET_SUPPLIED,
                InstantAuthority.SOURCE_INSTANT,
                CaptureMode.ONLINE,
                null);
        assertThatCode(() -> validator.validate(valid)).doesNotThrowAnyException();

        RecordClockCommand tampered = copy(valid, "f".repeat(64));
        assertThatThrownBy(() -> validator.validate(tampered))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void bindsDstOverlapToTheExplicitEarlierOffset() {
        RecordClockCommand valid = command(
                "America/New_York",
                LocalDateTime.parse("2026-11-01T01:30:00"),
                Instant.parse("2026-11-01T05:30:00Z"),
                -240,
                DstResolution.EARLIER_OFFSET_SELECTED,
                InstantAuthority.LOCAL_WITH_OFFSET,
                CaptureMode.ONLINE,
                null);
        assertThatCode(() -> validator.validate(valid)).doesNotThrowAnyException();

        RecordClockCommand wrongResolution = replaceResolution(
                valid, DstResolution.LATER_OFFSET_SELECTED);
        assertThatThrownBy(() -> validator.validate(wrongResolution))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("overlap");
    }

    @Test
    void acceptsOnlyExplicitServerResolvedGapShiftAndOfflineBatchEvidence() {
        RecordClockCommand valid = command(
                "America/New_York",
                LocalDateTime.parse("2026-03-08T02:30:00"),
                Instant.parse("2026-03-08T07:30:00Z"),
                -240,
                DstResolution.GAP_SHIFT_FORWARD,
                InstantAuthority.SERVER_RESOLVED,
                CaptureMode.OFFLINE,
                BATCH);
        assertThatCode(() -> validator.validate(valid)).doesNotThrowAnyException();

        RecordClockCommand missingBatch = command(
                "America/New_York",
                LocalDateTime.parse("2026-03-08T02:30:00"),
                Instant.parse("2026-03-08T07:30:00Z"),
                -240,
                DstResolution.GAP_SHIFT_FORWARD,
                InstantAuthority.SERVER_RESOLVED,
                CaptureMode.OFFLINE,
                null);
        assertThatThrownBy(() -> validator.validate(missingBatch))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Offline");
    }

    @Test
    void rejectsNormalEndpointVoidAndStaleTzdbEvidence() {
        RecordClockCommand base = command(
                "UTC",
                LocalDateTime.parse("2026-10-12T01:30:00"),
                Instant.parse("2026-10-12T01:30:00Z"),
                0,
                DstResolution.EXACT_OFFSET_SUPPLIED,
                InstantAuthority.SOURCE_INSTANT,
                CaptureMode.ONLINE,
                null);
        RecordClockCommand voidAlias = new RecordClockCommand(
                base.sourcePublicId(), base.sourceEventKey(), base.sourceSequence(),
                base.sourceType(), base.devicePublicId(), base.captureMode(),
                base.offlineBatchPublicId(), base.workerPublicId(),
                base.peopleAssignmentPublicId(), base.peopleAssignmentRevision(),
                ClockEventType.VOID, base.occurredAt(), base.sourceLocalDateTime(),
                base.sourceTimeZone(), base.sourceUtcOffsetMinutes(), base.dstResolution(),
                base.instantAuthority(), base.tzdbVersion(), base.timeZoneRuleVersion(),
                base.sourceProvenanceDigest(), base.trustLevel());
        assertThatThrownBy(() -> validator.validate(voidAlias))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("owner-created");

        RecordClockCommand stale = new RecordClockCommand(
                base.sourcePublicId(), base.sourceEventKey(), base.sourceSequence(),
                base.sourceType(), base.devicePublicId(), base.captureMode(),
                base.offlineBatchPublicId(), base.workerPublicId(),
                base.peopleAssignmentPublicId(), base.peopleAssignmentRevision(),
                base.eventType(), base.occurredAt(), base.sourceLocalDateTime(),
                base.sourceTimeZone(), base.sourceUtcOffsetMinutes(), base.dstResolution(),
                base.instantAuthority(), "stale", base.timeZoneRuleVersion(),
                base.sourceProvenanceDigest(), base.trustLevel());
        assertThatThrownBy(() -> validator.validate(stale))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("rule-version");
    }

    @Test
    void validatesAgainstTheExactSuppliedInstalledTzdbVersion() {
        RecordClockCommand current = command(
                "UTC",
                LocalDateTime.parse("2026-10-12T01:30:00"),
                Instant.parse("2026-10-12T01:30:00Z"),
                0,
                DstResolution.EXACT_OFFSET_SUPPLIED,
                InstantAuthority.SOURCE_INSTANT,
                CaptureMode.ONLINE,
                null);
        RecordClockCommand historicUnsealed = new RecordClockCommand(
                current.sourcePublicId(), current.sourceEventKey(), current.sourceSequence(),
                current.sourceType(), current.devicePublicId(), current.captureMode(),
                current.offlineBatchPublicId(), current.workerPublicId(),
                current.peopleAssignmentPublicId(), current.peopleAssignmentRevision(),
                current.eventType(), current.occurredAt(), current.sourceLocalDateTime(),
                current.sourceTimeZone(), current.sourceUtcOffsetMinutes(),
                current.dstResolution(), current.instantAuthority(), "2020a", "UTC@2020a",
                "0".repeat(64), current.trustLevel());
        RecordClockCommand historic = copy(
                historicUnsealed, ClockEvidenceValidator.provenanceDigest(historicUnsealed));
        ClockEvidenceValidator exactVersionValidator = new ClockEvidenceValidator(
                (zone, version) -> version.equals("2020a") ? zone.getRules() : null);

        assertThatCode(() -> exactVersionValidator.validate(historic))
                .doesNotThrowAnyException();

        RecordClockCommand unsupportedUnsealed = new RecordClockCommand(
                historic.sourcePublicId(), historic.sourceEventKey(), historic.sourceSequence(),
                historic.sourceType(), historic.devicePublicId(), historic.captureMode(),
                historic.offlineBatchPublicId(), historic.workerPublicId(),
                historic.peopleAssignmentPublicId(), historic.peopleAssignmentRevision(),
                historic.eventType(), historic.occurredAt(), historic.sourceLocalDateTime(),
                historic.sourceTimeZone(), historic.sourceUtcOffsetMinutes(),
                historic.dstResolution(), historic.instantAuthority(), "2019z", "UTC@2019z",
                "0".repeat(64), historic.trustLevel());
        RecordClockCommand unsupported = copy(
                unsupportedUnsealed, ClockEvidenceValidator.provenanceDigest(unsupportedUnsealed));
        assertThatThrownBy(() -> exactVersionValidator.validate(unsupported))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("rule-version");
    }

    private static RecordClockCommand command(
            String zone,
            LocalDateTime local,
            Instant occurredAt,
            int offset,
            DstResolution resolution,
            InstantAuthority instantAuthority,
            CaptureMode captureMode,
            UUID offlineBatchId) {
        String tzdb = ClockEvidenceValidator.expectedTzdbVersion(zone);
        RecordClockCommand unsealed = new RecordClockCommand(
                SOURCE,
                "device-event-0001",
                1,
                ClockSourceType.DEVICE,
                DEVICE,
                captureMode,
                offlineBatchId,
                WORKER,
                ASSIGNMENT,
                7,
                ClockEventType.IN,
                occurredAt,
                local,
                zone,
                offset,
                resolution,
                instantAuthority,
                tzdb,
                ClockEvidenceValidator.expectedTimeZoneRuleVersion(zone, tzdb),
                "0".repeat(64),
                TrustLevel.VERIFIED);
        return copy(unsealed, ClockEvidenceValidator.provenanceDigest(unsealed));
    }

    private static RecordClockCommand replaceResolution(
            RecordClockCommand source, DstResolution resolution) {
        RecordClockCommand unsealed = new RecordClockCommand(
                source.sourcePublicId(), source.sourceEventKey(), source.sourceSequence(),
                source.sourceType(), source.devicePublicId(), source.captureMode(),
                source.offlineBatchPublicId(), source.workerPublicId(),
                source.peopleAssignmentPublicId(), source.peopleAssignmentRevision(),
                source.eventType(), source.occurredAt(), source.sourceLocalDateTime(),
                source.sourceTimeZone(), source.sourceUtcOffsetMinutes(), resolution,
                source.instantAuthority(), source.tzdbVersion(), source.timeZoneRuleVersion(),
                "0".repeat(64), source.trustLevel());
        return copy(unsealed, ClockEvidenceValidator.provenanceDigest(unsealed));
    }

    private static RecordClockCommand copy(RecordClockCommand source, String digest) {
        return new RecordClockCommand(
                source.sourcePublicId(), source.sourceEventKey(), source.sourceSequence(),
                source.sourceType(), source.devicePublicId(), source.captureMode(),
                source.offlineBatchPublicId(), source.workerPublicId(),
                source.peopleAssignmentPublicId(), source.peopleAssignmentRevision(),
                source.eventType(), source.occurredAt(), source.sourceLocalDateTime(),
                source.sourceTimeZone(), source.sourceUtcOffsetMinutes(), source.dstResolution(),
                source.instantAuthority(), source.tzdbVersion(), source.timeZoneRuleVersion(),
                digest, source.trustLevel());
    }
}
