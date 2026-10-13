package com.dwp.services.people.hris.performance.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotPage;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotProjection;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQuery;
import com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotQueryPort;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings("auxiliaryclass")
class PerformanceWorkforceProjectionServiceTest {

    @Test
    void materializesGoldenPopulationThroughTheExactOwnerContract() {
        WorkforceSnapshotQueryPort port = mock(WorkforceSnapshotQueryPort.class);
        when(port.query(any(WorkforceSnapshotQuery.class))).thenReturn(
                PerformanceWorkforceTestSupport.page(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.OWNER_REVISION,
                        List.of(
                                PerformanceWorkforceTestSupport.MEMBER_ONE,
                                PerformanceWorkforceTestSupport.MEMBER_TWO),
                        null));
        InMemoryPerformanceWorkforceProjectionStore store =
                new InMemoryPerformanceWorkforceProjectionStore();
        PerformanceWorkforceProjectionService service =
                new PerformanceWorkforceProjectionService(() -> port, store);

        PerformanceWorkforceProjection.Head head = service.refresh(
                PerformanceWorkforceTestSupport.TENANT,
                PerformanceWorkforceTestSupport.AS_OF);
        PerformanceWorkforceProjection.Preview preview = service.preview(
                PerformanceWorkforceTestSupport.TENANT,
                new PerformanceWorkforceProjection.PopulationRule(
                        true, Set.of(PerformanceWorkforceTestSupport.ORGANIZATION)));

        ArgumentCaptor<WorkforceSnapshotQuery> query =
                ArgumentCaptor.forClass(WorkforceSnapshotQuery.class);
        verify(port).query(query.capture());
        assertThat(query.getValue()).satisfies(value -> {
            assertThat(value.purposeCode()).isEqualTo(WorkforceSnapshotQuery.PURPOSE_CODE);
            assertThat(value.projection())
                    .isEqualTo(WorkforceSnapshotProjection.PERFORMANCE_V1);
            assertThat(value.asOf()).isEqualTo(PerformanceWorkforceTestSupport.AS_OF);
            assertThat(value.limit()).isEqualTo(WorkforceSnapshotQuery.MAX_LIMIT);
            assertThat(value.cursorToken()).isNull();
        });
        assertThat(head.state()).isEqualTo(PerformanceWorkforceProjection.State.READY);
        assertThat(head.localRevision()).isEqualTo(1L);
        assertThat(head.snapshot().ownerRevision())
                .isEqualTo(PerformanceWorkforceTestSupport.OWNER_REVISION);
        assertThat(head.snapshot().purposeCode())
                .isEqualTo(WorkforceSnapshotQuery.PURPOSE_CODE);
        assertThat(head.snapshot().projection())
                .isEqualTo(WorkforceSnapshotProjection.PERFORMANCE_V1);
        assertThat(head.snapshot().members()).containsExactly(
                member(PerformanceWorkforceTestSupport.MEMBER_ONE),
                member(PerformanceWorkforceTestSupport.MEMBER_TWO));
        assertThat(PerformanceWorkforceProjection.Member.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly(
                        "workerPublicId",
                        "assignmentPublicId",
                        "status",
                        "organizationPublicId",
                        "managerAssignmentPublicId",
                        "jobProfilePublicId",
                        "gradePublicId");
        assertThat(preview.participantCount()).isEqualTo(2);
        assertThat(preview.reviewerAssignmentCount()).isEqualTo(2);
        assertThat(preview.contentHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void rejectsOwnerPageFromAnotherTenant() {
        WorkforceSnapshotQueryPort port = mock(WorkforceSnapshotQueryPort.class);
        when(port.query(any(WorkforceSnapshotQuery.class))).thenReturn(
                PerformanceWorkforceTestSupport.page(
                        PerformanceWorkforceTestSupport.TENANT + 1,
                        PerformanceWorkforceTestSupport.OWNER_REVISION,
                        List.of(PerformanceWorkforceTestSupport.MEMBER_ONE),
                        null));
        PerformanceWorkforceProjectionService service = service(port);

        assertProjectionError(
                () -> service.refresh(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.AS_OF),
                PerformanceWorkforceProjectionException.Code.OWNER_CONTRACT_MISMATCH);
        assertThatThrownBy(() -> service.current(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPurposeAndFieldDriftFromTheSealedProjection() {
        WorkforceSnapshotPage driftedPage = mock(WorkforceSnapshotPage.class);
        when(driftedPage.tenantId()).thenReturn(PerformanceWorkforceTestSupport.TENANT);
        when(driftedPage.callerModule()).thenReturn("HRIS-PER");
        when(driftedPage.purposeCode()).thenReturn("UNSEALED_PURPOSE");
        when(driftedPage.projection()).thenReturn(WorkforceSnapshotProjection.PERFORMANCE_V1);
        when(driftedPage.asOf()).thenReturn(PerformanceWorkforceTestSupport.AS_OF);
        WorkforceSnapshotQueryPort port = mock(WorkforceSnapshotQueryPort.class);
        when(port.query(any(WorkforceSnapshotQuery.class))).thenReturn(driftedPage);

        assertProjectionError(
                () -> service(port).refresh(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.AS_OF),
                PerformanceWorkforceProjectionException.Code.OWNER_CONTRACT_MISMATCH);
        assertThatThrownBy(() -> new PerformanceWorkforceProjection.Member(
                PerformanceWorkforceTestSupport.MEMBER_ONE.workerPublicId(),
                PerformanceWorkforceTestSupport.MEMBER_ONE.assignmentPublicId(),
                "SUSPENDED",
                PerformanceWorkforceTestSupport.ORGANIZATION,
                null,
                null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside WorkforceSnapshotV1");
    }

    @Test
    void rejectsOwnerRevisionChangeAcrossPages() {
        WorkforceSnapshotQueryPort port = mock(WorkforceSnapshotQueryPort.class);
        when(port.query(any(WorkforceSnapshotQuery.class))).thenReturn(
                PerformanceWorkforceTestSupport.page(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.OWNER_REVISION,
                        List.of(PerformanceWorkforceTestSupport.MEMBER_ONE),
                        "next-page"),
                PerformanceWorkforceTestSupport.page(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.OWNER_REVISION + 1,
                        List.of(PerformanceWorkforceTestSupport.MEMBER_TWO),
                        null));

        assertProjectionError(
                () -> service(port).refresh(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.AS_OF),
                PerformanceWorkforceProjectionException.Code.OWNER_REVISION_CHANGED);
    }

    @Test
    void rejectsRepeatedOwnerCursor() {
        WorkforceSnapshotQueryPort port = mock(WorkforceSnapshotQueryPort.class);
        when(port.query(any(WorkforceSnapshotQuery.class))).thenReturn(
                PerformanceWorkforceTestSupport.page(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.OWNER_REVISION,
                        List.of(PerformanceWorkforceTestSupport.MEMBER_ONE),
                        "repeated-cursor"),
                PerformanceWorkforceTestSupport.page(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.OWNER_REVISION,
                        List.of(PerformanceWorkforceTestSupport.MEMBER_TWO),
                        "repeated-cursor"));

        assertProjectionError(
                () -> service(port).refresh(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.AS_OF),
                PerformanceWorkforceProjectionException.Code.OWNER_CURSOR_REPEATED);
    }

    @Test
    void freezeGuardRejectsProjectionRevisionAndPreviewHashMismatch() {
        WorkforceSnapshotQueryPort port = mock(WorkforceSnapshotQueryPort.class);
        when(port.query(any(WorkforceSnapshotQuery.class))).thenReturn(
                PerformanceWorkforceTestSupport.page(
                        PerformanceWorkforceTestSupport.TENANT,
                        PerformanceWorkforceTestSupport.OWNER_REVISION,
                        List.of(
                                PerformanceWorkforceTestSupport.MEMBER_ONE,
                                PerformanceWorkforceTestSupport.MEMBER_TWO),
                        null));
        PerformanceWorkforceProjectionService service = service(port);
        PerformanceWorkforceProjection.Head head = service.refresh(
                PerformanceWorkforceTestSupport.TENANT,
                PerformanceWorkforceTestSupport.AS_OF);
        PerformanceWorkforceProjection.PopulationRule rule =
                new PerformanceWorkforceProjection.PopulationRule(
                        true, Set.of(PerformanceWorkforceTestSupport.ORGANIZATION));
        PerformanceWorkforceProjection.Preview preview = service.preview(
                PerformanceWorkforceTestSupport.TENANT, rule);

        assertProjectionError(
                () -> service.requirePreview(
                        PerformanceWorkforceTestSupport.TENANT,
                        rule,
                        head.localRevision() + 1,
                        preview.contentHash()),
                PerformanceWorkforceProjectionException.Code.STALE_WORKFORCE_SNAPSHOT);
        assertProjectionError(
                () -> service.requirePreview(
                        PerformanceWorkforceTestSupport.TENANT,
                        rule,
                        head.localRevision(),
                        "0".repeat(64)),
                PerformanceWorkforceProjectionException.Code.PREVIEW_HASH_MISMATCH);
        assertThat(service.requirePreview(
                PerformanceWorkforceTestSupport.TENANT,
                rule,
                head.localRevision(),
                preview.contentHash())).isEqualTo(preview);
    }

    @Test
    void canonicalizesAsOfToPostgresMicrosecondPrecisionBeforeHashing() {
        Instant requested = Instant.parse("2030-01-01T00:00:00.123456789Z");
        Instant canonical = Instant.parse("2030-01-01T00:00:00.123456Z");
        WorkforceSnapshotPage page = WorkforceSnapshotPage.verified(
                PerformanceWorkforceTestSupport.TENANT,
                "HRIS-PER",
                WorkforceSnapshotQuery.PURPOSE_CODE,
                WorkforceSnapshotProjection.PERFORMANCE_V1,
                canonical,
                PerformanceWorkforceTestSupport.OWNER_REVISION,
                List.of(PerformanceWorkforceTestSupport.MEMBER_ONE),
                null);
        WorkforceSnapshotQueryPort port = mock(WorkforceSnapshotQueryPort.class);
        when(port.query(any(WorkforceSnapshotQuery.class))).thenReturn(page);
        PerformanceWorkforceProjectionService service = service(port);

        PerformanceWorkforceProjection.Head head = service.refresh(
                PerformanceWorkforceTestSupport.TENANT, requested);

        ArgumentCaptor<WorkforceSnapshotQuery> query =
                ArgumentCaptor.forClass(WorkforceSnapshotQuery.class);
        verify(port).query(query.capture());
        assertThat(query.getValue().asOf()).isEqualTo(canonical);
        assertThat(head.snapshot().asOf()).isEqualTo(canonical);
        assertThat(head.snapshot().contentHash()).isEqualTo(
                PerformanceWorkforceProjection.snapshotContentHash(
                        PerformanceWorkforceTestSupport.TENANT,
                        requested,
                        PerformanceWorkforceTestSupport.OWNER_REVISION,
                        WorkforceSnapshotQuery.PURPOSE_CODE,
                        WorkforceSnapshotProjection.PERFORMANCE_V1,
                        head.snapshot().members()));
    }

    private PerformanceWorkforceProjectionService service(WorkforceSnapshotQueryPort port) {
        return new PerformanceWorkforceProjectionService(
                () -> port, new InMemoryPerformanceWorkforceProjectionStore());
    }

    private PerformanceWorkforceProjection.Member member(
            com.dwp.services.people.hris.contracts.workforce.v1.WorkforceSnapshotV1 value) {
        return new PerformanceWorkforceProjection.Member(
                value.workerPublicId(),
                value.assignmentPublicId(),
                value.status(),
                value.organizationPublicId(),
                value.managerAssignmentPublicId(),
                value.jobProfilePublicId(),
                value.gradePublicId());
    }

    private void assertProjectionError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            PerformanceWorkforceProjectionException.Code code) {
        assertThatThrownBy(action)
                .isInstanceOf(PerformanceWorkforceProjectionException.class)
                .extracting(error -> ((PerformanceWorkforceProjectionException) error).code())
                .isEqualTo(code);
    }
}
