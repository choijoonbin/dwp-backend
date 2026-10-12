package com.dwp.services.time.clockingestion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.clockingestion.ClockIngestionModels.CaptureMode;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEventType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockSourceType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.DstResolution;
import com.dwp.services.time.clockingestion.ClockIngestionModels.InstantAuthority;
import com.dwp.services.time.clockingestion.ClockIngestionModels.RecordClockCommand;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesProvider;
import java.util.List;
import java.util.Objects;

/** Rejects civil-time or source evidence that cannot reproduce the supplied instant exactly. */
public final class ClockEvidenceValidator {

    private static final String DIGEST_PATTERN = "^[0-9a-f]{64}$";

    public void validate(RecordClockCommand command) {
        if (command == null
                || command.sourcePublicId() == null
                || command.workerPublicId() == null
                || command.peopleAssignmentPublicId() == null
                || command.eventType() == null
                || command.occurredAt() == null
                || command.sourceLocalDateTime() == null
                || command.dstResolution() == null
                || command.instantAuthority() == null
                || command.sourceType() == null
                || command.captureMode() == null
                || command.trustLevel() == null) {
            invalid("Clock evidence is incomplete.");
        }
        if (command.eventType() == ClockEventType.VOID) {
            invalid("VOID is owner-created and cannot be ingested as a normal clock event.");
        }
        if (command.sourceSequence() <= 0 || command.peopleAssignmentRevision() < 0) {
            invalid("Clock source and assignment revisions are invalid.");
        }
        requireText(command.sourceEventKey(), 200, "source event key");
        requireText(command.sourceTimeZone(), 80, "IANA time zone");
        requireText(command.tzdbVersion(), 40, "tzdb version");
        requireText(command.timeZoneRuleVersion(), 80, "time-zone rule version");
        if (command.sourceUtcOffsetMinutes() < -840 || command.sourceUtcOffsetMinutes() > 840) {
            invalid("Clock UTC offset is outside the supported range.");
        }
        requireCaptureEvidence(command);

        ZoneId zone;
        ZoneOffset suppliedOffset;
        try {
            zone = ZoneId.of(command.sourceTimeZone());
            suppliedOffset = ZoneOffset.ofTotalSeconds(command.sourceUtcOffsetMinutes() * 60);
        } catch (DateTimeException invalidZone) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Clock time-zone evidence is invalid.",
                    invalidZone);
        }
        String currentTzdb = expectedTzdbVersion(zone);
        if (!currentTzdb.equals(command.tzdbVersion())
                || !expectedTimeZoneRuleVersion(zone, currentTzdb)
                        .equals(command.timeZoneRuleVersion())) {
            invalid("Clock rule-version evidence is unavailable or stale.");
        }

        Instant resolved = resolve(command, zone, suppliedOffset);
        if (!resolved.equals(command.occurredAt())) {
            invalid("Clock instant and civil-time evidence are inconsistent.");
        }
        String expectedProvenance = provenanceDigest(command);
        if (command.sourceProvenanceDigest() == null
                || !command.sourceProvenanceDigest().matches(DIGEST_PATTERN)
                || !MessageDigestSupport.constantTimeEquals(
                        expectedProvenance, command.sourceProvenanceDigest())) {
            invalid("Clock source provenance digest does not match the supplied evidence.");
        }
    }

    public static String provenanceDigest(RecordClockCommand command) {
        Objects.requireNonNull(command, "command must not be null");
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
                command.trustLevel());
    }

    public static String expectedTzdbVersion(String zoneId) {
        try {
            return expectedTzdbVersion(ZoneId.of(zoneId));
        } catch (DateTimeException invalidZone) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid IANA time zone.");
        }
    }

    public static String expectedTimeZoneRuleVersion(String zoneId, String tzdbVersion) {
        try {
            return expectedTimeZoneRuleVersion(ZoneId.of(zoneId), tzdbVersion);
        } catch (DateTimeException invalidZone) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid IANA time zone.");
        }
    }

    private static Instant resolve(
            RecordClockCommand command, ZoneId zone, ZoneOffset suppliedOffset) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> validOffsets = rules.getValidOffsets(command.sourceLocalDateTime());
        if (validOffsets.size() == 1) {
            if (command.dstResolution() != DstResolution.EXACT_OFFSET_SUPPLIED
                    || !validOffsets.getFirst().equals(suppliedOffset)) {
                invalid("Clock offset is not valid for the supplied local time.");
            }
            return command.sourceLocalDateTime().toInstant(suppliedOffset);
        }
        if (validOffsets.size() == 2) {
            DstResolution expected = suppliedOffset.equals(validOffsets.getFirst())
                    ? DstResolution.EARLIER_OFFSET_SELECTED
                    : suppliedOffset.equals(validOffsets.getLast())
                            ? DstResolution.LATER_OFFSET_SELECTED : null;
            if (expected == null || command.dstResolution() != expected) {
                invalid("DST overlap selection does not match the supplied offset.");
            }
            return command.sourceLocalDateTime().toInstant(suppliedOffset);
        }

        ZoneOffsetTransition transition = rules.getTransition(command.sourceLocalDateTime());
        if (transition == null
                || !transition.isGap()
                || command.dstResolution() != DstResolution.GAP_SHIFT_FORWARD
                || command.instantAuthority() != InstantAuthority.SERVER_RESOLVED
                || !transition.getOffsetAfter().equals(suppliedOffset)) {
            invalid("DST gap evidence requires the approved shift-forward resolution.");
        }
        return command.sourceLocalDateTime()
                .plus(transition.getDuration())
                .toInstant(transition.getOffsetAfter());
    }

    private static String expectedTzdbVersion(ZoneId zone) {
        var versions = ZoneRulesProvider.getVersions(zone.getId());
        if (versions.isEmpty()) {
            invalid("No time-zone rules are installed for the supplied zone.");
        }
        return versions.lastKey();
    }

    private static String expectedTimeZoneRuleVersion(ZoneId zone, String tzdbVersion) {
        return zone.getId() + "@" + tzdbVersion;
    }

    private static void requireCaptureEvidence(RecordClockCommand command) {
        boolean deviceSource = command.sourceType() == ClockSourceType.MOBILE
                || command.sourceType() == ClockSourceType.KIOSK
                || command.sourceType() == ClockSourceType.DEVICE;
        if (deviceSource != (command.devicePublicId() != null)) {
            invalid("Device clock sources require one exact device identifier.");
        }
        if (command.captureMode() == CaptureMode.OFFLINE
                && command.offlineBatchPublicId() == null) {
            invalid("Offline clock evidence requires an immutable batch identifier.");
        }
        if (command.captureMode() == CaptureMode.ONLINE
                && command.offlineBatchPublicId() != null) {
            invalid("Online clock evidence cannot carry an offline batch identifier.");
        }
    }

    private static void requireText(String value, int maximum, String label) {
        if (value == null
                || value.isBlank()
                || value.length() > maximum
                || !value.equals(value.strip())
                || value.chars().anyMatch(character -> character < 0x20 || character == 0x7f)) {
            invalid("Invalid " + label + ".");
        }
    }

    private static void invalid(String message) {
        throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static final class MessageDigestSupport {
        private static boolean constantTimeEquals(String expected, String actual) {
            return java.security.MessageDigest.isEqual(
                    expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
    }
}
