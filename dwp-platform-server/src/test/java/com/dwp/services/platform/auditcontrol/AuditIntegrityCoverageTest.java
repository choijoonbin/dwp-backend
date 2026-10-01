package com.dwp.services.platform.auditcontrol;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditIntegrityCoverageTest {

    @Test
    void marksTheIntegrityLedgerPartialAtTheNinetyCheckpointBoundary() {
        AuditControlRepository repository = mock(AuditControlRepository.class);
        AuditIntegrityService service = new AuditIntegrityService(repository, "test-integrity-secret");
        List<AuditControlDtos.IntegrityCheckpoint> rows = IntStream.range(0, 91)
                .mapToObj(index -> checkpoint(index))
                .toList();
        when(repository.integrity(1L, 91)).thenReturn(rows);

        AuditControlDtos.IntegrityCheckpointPage result = service.list(1L);

        assertThat(result.items()).hasSize(90);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.coverageState()).isEqualTo("TRUNCATED_AT_LIMIT");
        verify(repository).integrity(1L, 91);
    }

    private AuditControlDtos.IntegrityCheckpoint checkpoint(int index) {
        Instant observedAt = Instant.parse("2026-09-29T00:00:00Z").minusSeconds(index * 86_400L);
        return new AuditControlDtos.IntegrityCheckpoint(
                UUID.randomUUID(),
                LocalDate.ofInstant(observedAt, java.time.ZoneOffset.UTC),
                index,
                observedAt,
                observedAt,
                "root",
                "checkpoint",
                "HMAC_SHA256",
                "VERIFIED",
                observedAt,
                observedAt);
    }
}
