package com.dwp.services.time.clockingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.time.clockingestion.ClockIngestionModels.CaptureMode;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEventType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockSourceType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockStepUpHeaders;
import com.dwp.services.time.clockingestion.ClockIngestionModels.DstResolution;
import com.dwp.services.time.clockingestion.ClockIngestionModels.InstantAuthority;
import com.dwp.services.time.clockingestion.ClockIngestionModels.RecordClockCommand;
import com.dwp.services.time.clockingestion.ClockIngestionModels.TrustLevel;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VoidClockCommand;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClockIngestionCommandValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void recordCommandDeclaresRequiredBoundedTransportContract() {
        RecordClockCommand invalid = new RecordClockCommand(
                null,
                " ",
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                -1,
                null,
                null,
                null,
                " ",
                841,
                null,
                null,
                " ",
                " ",
                "NOT-A-DIGEST",
                null);

        assertThat(validator.validate(invalid))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(
                        "sourcePublicId",
                        "sourceEventKey",
                        "sourceSequence",
                        "sourceType",
                        "captureMode",
                        "workerPublicId",
                        "peopleAssignmentPublicId",
                        "peopleAssignmentRevision",
                        "eventType",
                        "occurredAt",
                        "sourceLocalDateTime",
                        "sourceTimeZone",
                        "sourceUtcOffsetMinutes",
                        "dstResolution",
                        "instantAuthority",
                        "tzdbVersion",
                        "timeZoneRuleVersion",
                        "sourceProvenanceDigest",
                        "trustLevel");

        assertThat(validator.validate(validRecord())).isEmpty();
    }

    @Test
    void voidBodyAndV36HeadersDeclareClosedRequiredContract() {
        assertThat(validator.validate(new VoidClockCommand("operator correction")))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("reasonCode");
        assertThat(validator.validate(new VoidClockCommand("DEVICE_DUPLICATE"))).isEmpty();

        ClockStepUpHeaders invalid = new ClockStepUpHeaders(
                " ", "revision-1", -1L);
        assertThat(validator.validate(invalid))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("challenge", "decisionRevision", "expectedObjectVersion");
        assertThat(validator.validate(new ClockStepUpHeaders(
                "signed.challenge.token", "psr-" + "a".repeat(64), 7L))).isEmpty();
    }

    private static RecordClockCommand validRecord() {
        return new RecordClockCommand(
                UUID.fromString("73000000-0000-4000-8000-000000000001"),
                "source-event-1",
                1,
                ClockSourceType.API,
                null,
                CaptureMode.ONLINE,
                null,
                UUID.fromString("73000000-0000-4000-8000-000000000002"),
                UUID.fromString("73000000-0000-4000-8000-000000000003"),
                0,
                ClockEventType.IN,
                Instant.parse("2026-10-12T01:30:00Z"),
                LocalDateTime.parse("2026-10-12T01:30:00"),
                "UTC",
                0,
                DstResolution.EXACT_OFFSET_SUPPLIED,
                InstantAuthority.SOURCE_INSTANT,
                "2026a",
                "UTC@2026a",
                "a".repeat(64),
                TrustLevel.VERIFIED);
    }
}
