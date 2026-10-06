package com.dwp.services.people.hr.assignment;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssignmentProposalCanonicalizerTest {

    private final AssignmentProposalCanonicalizer canonicalizer =
            new AssignmentProposalCanonicalizer(
                    new ObjectMapper().findAndRegisterModules());

    @Test
    void canonicalizesOnlyTheClosedAssignmentChangeVocabulary() {
        UUID organizationId = UUID.randomUUID();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("workerHours", "40.00");
        input.put("organizationId", organizationId.toString());
        input.put("jobProfileKey", "eng.senior");
        input.put("businessTitle", "  Senior Engineer  ");

        Map<String, Object> result = canonicalizer.changes(input);

        assertThat(result).containsEntry("organizationId", organizationId.toString())
                .containsEntry("jobProfileKey", "ENG.SENIOR")
                .containsEntry("businessTitle", "Senior Engineer");
        assertThat((BigDecimal) result.get("workerHours"))
                .isEqualByComparingTo("40");
        assertThat(canonicalizer.sha256(result))
                .isEqualTo(canonicalizer.sha256(new LinkedHashMap<>(result)));
    }

    @Test
    void rejectsUnknownNestedOrAmbiguousFieldsBeforePersistence() {
        assertThatThrownBy(() -> canonicalizer.changes(
                Map.of("salary", Map.of("amount", 1000))))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("Unsupported assignment change field");
        assertThatThrownBy(() -> canonicalizer.changes(
                Map.of("organizationId", "not-a-uuid")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("canonical UUID");
    }
}
