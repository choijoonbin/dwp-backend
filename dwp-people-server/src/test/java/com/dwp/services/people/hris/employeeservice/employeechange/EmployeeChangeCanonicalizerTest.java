package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmployeeChangeCanonicalizerTest {

    private final EmployeeChangeCanonicalizer canonicalizer =
            new EmployeeChangeCanonicalizer(new ObjectMapper().findAndRegisterModules());

    @Test
    void canonicalizesPatchOrderAndFreezesDigest() {
        EmployeeChangeCanonicalizer.CanonicalPatch first = canonicalizer.canonicalPatch(
                request(List.of(
                        new EmployeeChangeDtos.PatchOperation(
                                "replace", "/timeZone", "Asia/Seoul"),
                        new EmployeeChangeDtos.PatchOperation(
                                "replace", "/displayName", "Kim Minsoo"))));
        EmployeeChangeCanonicalizer.CanonicalPatch second = canonicalizer.canonicalPatch(
                request(List.of(
                        new EmployeeChangeDtos.PatchOperation(
                                "replace", "/displayName", "Kim Minsoo"),
                        new EmployeeChangeDtos.PatchOperation(
                                "replace", "/timeZone", "Asia/Seoul"))));

        assertThat(first.operations())
                .extracting(EmployeeChangeDtos.PatchOperation::path)
                .containsExactly("/displayName", "/timeZone");
        assertThat(first.fieldGroups())
                .containsExactly("PERSON_NAME", "PERSON_PROFILE");
        assertThat(first.digest()).isEqualTo(second.digest()).matches("[0-9a-f]{64}");
        assertThat(first.bytes()).containsExactly(second.bytes());
    }

    @Test
    void rejectsUnknownDuplicateAndInvalidPatchOperations() {
        assertInvalid(request(List.of(new EmployeeChangeDtos.PatchOperation(
                "replace", "/bankAccount", "secret"))));
        assertInvalid(request(List.of(
                new EmployeeChangeDtos.PatchOperation(
                        "replace", "/displayName", "Kim Minsoo"),
                new EmployeeChangeDtos.PatchOperation(
                        "replace", "/displayName", "Kim M."))));
        assertInvalid(request(List.of(new EmployeeChangeDtos.PatchOperation(
                "remove", "/displayName", null))));
        assertInvalid(request(List.of(new EmployeeChangeDtos.PatchOperation(
                "replace", "/timeZone", "Invalid/Zone"))));
    }

    @Test
    void thawRejectsTamperedOrMismatchedImmutableEvidence() {
        EmployeeChangeCanonicalizer.CanonicalPatch patch = canonicalizer.canonicalPatch(
                request(List.of(new EmployeeChangeDtos.PatchOperation(
                        "replace", "/displayName", "Kim Minsoo"))));

        EmployeeChangeCanonicalizer.CanonicalPatch thawed = canonicalizer.thaw(
                patch.bytes(), patch.digest(), patch.targetAggregateType(),
                patch.changeType(), patch.schemaKey());
        assertThat(thawed.digest()).isEqualTo(patch.digest());

        byte[] tampered = patch.bytes();
        tampered[tampered.length - 2] ^= 1;
        assertThatThrownBy(() -> canonicalizer.thaw(
                tampered, patch.digest(), patch.targetAggregateType(),
                patch.changeType(), patch.schemaKey()))
                .isInstanceOfAny(BaseException.class, IllegalStateException.class);
        assertThatThrownBy(() -> canonicalizer.thaw(
                patch.bytes(), "0".repeat(64), patch.targetAggregateType(),
                patch.changeType(), patch.schemaKey()))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    private EmployeeChangeDtos.CreateRequest request(
            List<EmployeeChangeDtos.PatchOperation> operations) {
        return new EmployeeChangeDtos.CreateRequest(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                "PERSON",
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                "PROFILE_UPDATE",
                LocalDate.of(2026, 10, 12),
                "employee-change.person.v1",
                1,
                operations,
                List.of());
    }

    private void assertInvalid(EmployeeChangeDtos.CreateRequest request) {
        assertThatThrownBy(() -> canonicalizer.canonicalPatch(request))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }
}
