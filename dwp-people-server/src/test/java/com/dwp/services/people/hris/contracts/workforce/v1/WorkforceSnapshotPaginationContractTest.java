package com.dwp.services.people.hris.contracts.workforce.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WorkforceSnapshotPaginationContractTest {

    private static final Instant AS_OF = Instant.parse("2026-09-11T00:00:00Z");
    private static final String EXPECTED_ONE_ROW_DIGEST =
            "d125f21d8807901fff8cc544a51276a1de703da7b967b1f1f3d9c2f4db92f577";

    @Test
    void canonicalOrderAndUniqueAssignmentKeysAreMandatory() {
        WorkforceSnapshotV1 first = snapshot("11", "21", "ACTIVE");
        WorkforceSnapshotV1 second = snapshot("12", "22", "ACTIVE");

        WorkforceSnapshotPage page = page(List.of(first, second), null);
        assertThat(page.continuationPosition())
                .isEqualTo(WorkforceSnapshotPage.canonicalPosition(second));
        assertThat(WorkforceSnapshotPage.ORDERING_VERSION)
                .isEqualTo("assignmentPublicId-asc-workerPublicId-asc.v1");

        assertThatThrownBy(() -> page(List.of(second, first), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(WorkforceSnapshotPage.ORDERING_VERSION);
        assertThatThrownBy(() -> page(List.of(first, first), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worker and assignment key");
        WorkforceSnapshotV1 sameAssignment = snapshot("13", "21", "ACTIVE");
        assertThatThrownBy(() -> page(List.of(first, sameAssignment), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assignmentPublicId");
    }

    @Test
    void requestLimitRevisionAndContinuationPositionBindEveryPage() {
        WorkforceSnapshotV1 first = snapshot("11", "21", "ACTIVE");
        WorkforceSnapshotV1 second = snapshot("12", "22", "ACTIVE");
        WorkforceSnapshotPage twoRows = page(List.of(first, second), null);
        assertThatThrownBy(() -> twoRows.verifyFor(request(1, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requested limit");

        String firstPosition = WorkforceSnapshotPage.canonicalPosition(first);
        page(List.of(second), null).verifyFor(request(1, 7L, firstPosition));
        assertThatThrownBy(() -> page(List.of(first), null)
                .verifyFor(request(1, 7L, firstPosition)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authenticated cursor position");
        assertThatThrownBy(() -> page(List.of(second), null)
                .verifyFor(request(1, 8L, firstPosition)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner revision");
    }

    @Test
    void cursorAndStatusCodesFailClosedOnNonCanonicalValues() {
        assertThatThrownBy(() -> page(List.of(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> page(List.of(), "opaque"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last emitted canonical snapshot key");
        assertThatThrownBy(() -> request(1, 7L, "not-a-canonical-position"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical assignment/worker key");
        for (String status : List.of(
                " ", "active", "A", "A".repeat(65), "LEAVE", "SUSPENDED", "UNKNOWN")) {
            assertThatThrownBy(() -> snapshot("11", "21", status))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("canonical");
        }
    }

    @Test
    void orderingVersionIsSealedIntoTheAuthenticatedPageDigest() {
        assertThat(page(List.of(snapshot("11", "21", "ACTIVE")), null).payloadDigest())
                .isEqualTo(EXPECTED_ONE_ROW_DIGEST);
    }

    private static WorkforceSnapshotPage page(
            List<WorkforceSnapshotV1> values,
            String nextCursor) {
        return WorkforceSnapshotPage.verified(
                1,
                VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                AS_OF,
                7,
                values,
                nextCursor);
    }

    private static VerifiedWorkforceSnapshotRequest request(
            int limit,
            Long cursorOwnerRevision,
            String cursorPosition) {
        return new VerifiedWorkforceSnapshotRequest(
                1,
                11,
                13,
                VerifiedWorkforceSnapshotRequest.PERFORMANCE_CALLER,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                AS_OF,
                limit,
                cursorOwnerRevision,
                cursorPosition,
                cursorPosition == null ? null : "a".repeat(64));
    }

    private static WorkforceSnapshotV1 snapshot(
            String workerSuffix,
            String assignmentSuffix,
            String status) {
        return new WorkforceSnapshotV1(
                uuid(workerSuffix),
                uuid(assignmentSuffix),
                status,
                uuid("31"),
                null,
                null,
                null);
    }

    private static UUID uuid(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-0000000000" + suffix);
    }
}
