package com.dwp.services.people.hr.performance.populationfreeze;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PopulationFreezeCanonicalizerTest {

    private final PopulationFreezeCanonicalizer canonicalizer =
            new PopulationFreezeCanonicalizer(new ObjectMapper().findAndRegisterModules());

    @Test
    void preservesTheExistingPreviewCanonicalMemberHash() {
        var later = member(
                "00000000-0000-4000-8000-000000000002",
                "00000000-0000-4000-8000-000000000020",
                "00000000-0000-4000-8000-000000000200");
        var earlier = member(
                "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000010",
                null);
        String expected = PopulationFreezeCanonicalizer.sha256("""
                00000000-0000-4000-8000-000000000001|00000000-0000-4000-8000-000000000010|ACTIVE|00000000-0000-4000-8000-000000000100||||INCLUDED
                00000000-0000-4000-8000-000000000002|00000000-0000-4000-8000-000000000020|ACTIVE|00000000-0000-4000-8000-000000000100|00000000-0000-4000-8000-000000000200|||INCLUDED
                """);

        assertThat(canonicalizer.previewContentHash(List.of(later, earlier)))
                .isEqualTo(expected);
    }

    private PopulationFreezeRepository.PreviewMember member(
            String participant,
            String assignment,
            String reviewer) {
        return new PopulationFreezeRepository.PreviewMember(
                UUID.fromString(participant), UUID.fromString(assignment), "ACTIVE",
                UUID.fromString("00000000-0000-4000-8000-000000000100"),
                reviewer == null ? null : UUID.fromString(reviewer),
                null, null, "INCLUDED");
    }
}
