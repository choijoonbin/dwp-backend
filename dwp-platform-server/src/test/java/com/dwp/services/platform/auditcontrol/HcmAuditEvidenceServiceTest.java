package com.dwp.services.platform.auditcontrol;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HcmAuditEvidenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T06:00:00Z");
    private static final HcmAuditEvidenceCursorCodec.AccessContext ACCESS =
            new HcmAuditEvidenceCursorCodec.AccessContext(
                    7L,
                    "route.hcm.audit.evidence.page",
                    "hcm.management",
                    "hcm-scope-" + "b".repeat(40),
                    UUID.fromString("10000000-0000-4000-8000-000000000001"),
                    17,
                    29);

    private final HcmAuditEvidenceRepository repository =
            mock(HcmAuditEvidenceRepository.class);
    private final HcmAuditEvidenceCursorCodec cursorCodec =
            mock(HcmAuditEvidenceCursorCodec.class);
    private final HcmAuditEvidenceService service = new HcmAuditEvidenceService(
            repository,
            cursorCodec,
            "dwp-people-server,dwp-time-server",
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void usesSizePlusOneAndIssuesCursorFromLastVisibleEvidence() {
        HcmAuditEvidenceDtos.Evidence first = evidence(1, NOW.minusSeconds(10));
        HcmAuditEvidenceDtos.Evidence tenth = evidence(10, NOW.minusSeconds(100));
        HcmAuditEvidenceDtos.Evidence overflow = evidence(11, NOW.minusSeconds(110));
        List<HcmAuditEvidenceDtos.Evidence> rows = new java.util.ArrayList<>();
        rows.add(first);
        for (int index = 2; index < 10; index++) {
            rows.add(evidence(index, NOW.minusSeconds(index * 10L)));
        }
        rows.add(tenth);
        rows.add(overflow);
        when(repository.list(any(), eq(null), eq(11))).thenReturn(rows);
        when(cursorCodec.encode(any(), any(), eq(tenth.occurredAt()), eq(tenth.eventId())))
                .thenReturn("signed-next");

        HcmAuditEvidenceDtos.Page result = service.page(
                ACCESS, AuditWindow.D7, "ALL", "ALL", "ALL",
                null, null, 10, null);

        assertThat(result.items()).hasSize(10).doesNotContain(overflow);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.snapshotAt()).isEqualTo(NOW);
        assertThat(result.nextCursor()).isEqualTo("signed-next");
        verify(repository).list(any(), eq(null), eq(11));
    }

    @Test
    void continuationReusesSignedSnapshotBeforeRepositoryAccess() {
        Instant frozen = NOW.minusSeconds(120);
        HcmAuditEvidenceCursorCodec.CursorPosition position =
                new HcmAuditEvidenceCursorCodec.CursorPosition(
                        frozen, frozen.minusSeconds(1), UUID.randomUUID());
        when(cursorCodec.decode(eq("cursor"), eq(ACCESS), any())).thenReturn(position);
        when(repository.list(any(), eq(position), anyInt())).thenReturn(List.of());

        HcmAuditEvidenceDtos.Page result = service.page(
                ACCESS, AuditWindow.D7, "ALL", "ALL", "ALL",
                null, null, 50, "cursor");

        ArgumentCaptor<HcmAuditEvidenceCriteria> criteria =
                ArgumentCaptor.forClass(HcmAuditEvidenceCriteria.class);
        verify(repository).list(criteria.capture(), eq(position), eq(51));
        assertThat(criteria.getValue().snapshotAt()).isEqualTo(frozen);
        assertThat(result.snapshotAt()).isEqualTo(frozen);
    }

    @Test
    void missingOrForeignEvidenceUsesTheSameNotFoundOutcome() {
        UUID eventId = UUID.randomUUID();
        when(repository.findById(7L, eventId, NOW)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(ACCESS, eventId))
                .isInstanceOf(BaseException.class)
                .extracting(value -> ((BaseException) value).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    private HcmAuditEvidenceDtos.Evidence evidence(int seed, Instant occurredAt) {
        return new HcmAuditEvidenceDtos.Evidence(
                new UUID(0, seed),
                occurredAt,
                "ADMIN_CHANGE",
                "hris.policy.changed",
                "SUCCESS",
                "HIGH",
                "USER",
                "dwp-people-server",
                "hris.people",
                "POLICY",
                seed,
                "hcm.configuration.policy.update",
                "HRIS_CONFIGURATION",
                "psr-" + "a".repeat(64),
                seed,
                17,
                null,
                null,
                null,
                null,
                "c".repeat(64),
                "INTERNAL",
                "STANDARD",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "d".repeat(64));
    }
}
