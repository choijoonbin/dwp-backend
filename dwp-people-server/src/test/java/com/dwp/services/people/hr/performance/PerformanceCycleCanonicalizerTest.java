package com.dwp.services.people.hr.performance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerformanceCycleCanonicalizerTest {

    private final PerformanceCycleCanonicalizer canonicalizer =
            new PerformanceCycleCanonicalizer(new ObjectMapper().findAndRegisterModules());
    private final Instant start = Instant.parse("2027-01-01T00:00:00Z");
    private final Instant end = Instant.parse("2027-12-31T00:00:00Z");

    @Test
    void acceptsOpenEndedCycleAndProducesOrderIndependentConfigHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("weight", 50);
        first.put("mode", "SELF");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("mode", "SELF");
        second.put("weight", 50);
        var stageOne = stage(1, start, start.plusSeconds(3_600), first);
        var stageTwo = stage(1, start, start.plusSeconds(3_600), second);

        List<PerformanceCycleDtos.StageInput> validated = canonicalizer.validatedStages(
                start, null, List.of(stageOne));
        String hashOne = canonicalizer.contentHash(
                "Annual", start, null, "UTC", UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"), null, validated);
        String hashTwo = canonicalizer.contentHash(
                "Annual", start, null, "UTC", UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"), null,
                List.of(stageTwo));

        assertThat(hashOne).hasSize(64).isEqualTo(hashTwo);
    }

    @Test
    void rejectsNonContiguousAndOutOfPeriodStages() {
        assertThatThrownBy(() -> canonicalizer.validatedStages(
                start, end, List.of(stage(2, start, start.plusSeconds(10), Map.of()))))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        assertThatThrownBy(() -> canonicalizer.validatedStages(
                start, end, List.of(stage(1, start.minusSeconds(1), end, Map.of()))))
                .isInstanceOf(BaseException.class);
    }

    private PerformanceCycleDtos.StageInput stage(
            int sequence,
            Instant opensAt,
            Instant closesAt,
            Map<String, Object> config) {
        return new PerformanceCycleDtos.StageInput(
                "SELF", "SELF_REVIEW", sequence, opensAt, closesAt, true, config);
    }
}
