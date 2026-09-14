package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.field;
import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.group;
import static com.dwp.services.approval.domain.ApprovalFormSchemaV2CompilerTest.schema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovalFormUserReferencesTest {

    private static final String PERSON_A = "12345678-1234-1234-1234-123456789abc";
    private static final String PERSON_B = "87654321-4321-4321-4321-cba987654321";
    private final ApprovalFormSchemaV2Compiler compiler = new ApprovalFormSchemaV2Compiler();
    private final ApprovalFormUserReferences references = new ApprovalFormUserReferences();

    @Test
    void collectsRootAndRowCanonicalPeopleAndDeduplicatesWithoutChangingPayload() {
        var compiled = compiler.compile(schema(field("reviewer", "USER"), field("summary", "TEXT"),
                group("lines", List.of(field("owner", "USER"), field("description", "TEXT")))));
        var prepared = references.prepare(compiled, Map.of("reviewer", PERSON_A, "summary", "Keep text",
                "lines", List.of(Map.of("owner", PERSON_A), Map.of("owner", PERSON_B))), true);
        assertThat(prepared.references()).containsExactly(
                new ApprovalFormUserReferences.Reference("reviewer", UUID.fromString(PERSON_A)),
                new ApprovalFormUserReferences.Reference("lines[0].owner", UUID.fromString(PERSON_A)),
                new ApprovalFormUserReferences.Reference("lines[1].owner", UUID.fromString(PERSON_B)));
        assertThat(prepared.distinctPersonIds()).containsExactly(UUID.fromString(PERSON_A), UUID.fromString(PERSON_B));
        assertThat(prepared.evaluation().payload()).containsEntry("summary", "Keep text").containsEntry("reviewer", PERSON_A);
        assertThat(prepared.evaluation().schemaSha256()).isEqualTo(compiled.sha256());
        assertThatThrownBy(() -> prepared.references().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> prepared.distinctPersonIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void hiddenStaleRootAndRowPeopleNeverReachContextualResolution() {
        var reviewer = field("reviewer", "USER");
        reviewer.put("visibleWhen", Map.of("op", "EQ", "field", "mode", "value", "REVIEW"));
        var owner = field("owner", "USER");
        owner.put("visibleWhen", Map.of("op", "EQ", "field", "category", "value", "STAFFED"));
        var compiled = compiler.compile(schema(reviewer, field("mode", "TEXT"),
                group("lines", List.of(owner, field("category", "TEXT")))));
        var prepared = references.prepare(compiled, Map.of("mode", "OTHER", "reviewer", "stale display name",
                "lines", List.of(Map.of("category", "FREE", "owner", "not a person ID"))), true);
        assertThat(prepared.references()).isEmpty();
        assertThat(prepared.evaluation().payload()).doesNotContainKey("reviewer");
        assertThat((Map<?, ?>) ((List<?>) prepared.evaluation().payload().get("lines")).getFirst())
                .isEqualTo(Map.of("category", "FREE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alice", "1-1-1-1-1", "12345678-1234-1234-1234-123456789ABC",
            " 12345678-1234-1234-1234-123456789abc", "12345678-1234-1234-1234-123456789abc ",
            "12345678123412341234123456789abc", "http://localhost/users/1"})
    void rejectsNoncanonicalVisiblePeopleWithoutRelaxingPureCompilerTextCompatibility(String value) {
        var compiled = compiler.compile(schema(field("reviewer", "USER")));
        assertThat(new ApprovalFormSchemaV2Evaluator().evaluate(compiled, Map.of("reviewer", value), false).payload())
                .containsEntry("reviewer", value);
        assertThatThrownBy(() -> references.prepare(compiled, Map.of("reviewer", value), false))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void partialDraftOmitsMissingPeopleButSubmitStillRequiresVisibleRequiredPeople() {
        var reviewer = field("reviewer", "USER");
        reviewer.put("required", true);
        var compiled = compiler.compile(schema(reviewer));
        assertThat(references.prepare(compiled, Map.of(), false).references()).isEmpty();
        assertThatThrownBy(() -> references.prepare(compiled, Map.of(), true)).isInstanceOf(BaseException.class);
        assertThat(references.prepare(compiled, Map.of("reviewer", PERSON_A), true).references()).hasSize(1);
    }

    @Test
    void candidatePathMustResolveToExactRootOrDirectGroupUserField() {
        var compiled = compiler.compile(schema(field("reviewer", "USER"), field("summary", "TEXT"),
                group("lines", List.of(field("owner", "USER"), field("amount", "NUMBER")))));
        assertThat(references.requireUserField(compiled, null, "reviewer")).isEqualTo("reviewer");
        assertThat(references.requireUserField(compiled, "lines", "owner")).isEqualTo("lines.owner");
        for (String invalid : List.of("summary", "lines.owner", "lines[0].owner", "missing", "")) {
            assertThatThrownBy(() -> references.requireUserField(compiled, null, invalid)).isInstanceOf(BaseException.class);
        }
        assertThatThrownBy(() -> references.requireUserField(compiled, "lines", "amount")).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> references.requireUserField(compiled, "reviewer", "owner")).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> references.requireUserField(compiled, "", "owner")).isInstanceOf(BaseException.class);
    }

    @Test
    void retainsSchemaRowBoundsAndRejectsUnknownInputsBeforeExtractingAnyPeople() {
        var lines = group("lines", List.of(field("owner", "USER")));
        lines.put("maxRows", 1);
        var compiled = compiler.compile(schema(lines));
        assertThatThrownBy(() -> references.prepare(compiled,
                Map.of("lines", List.of(Map.of("owner", PERSON_A), Map.of("owner", PERSON_B))), false))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> references.prepare(compiled,
                Map.of("lines", List.of(Map.of("owner", PERSON_A, "sourceUrl", "https://example.invalid"))), false))
                .isInstanceOf(BaseException.class);
    }
}
