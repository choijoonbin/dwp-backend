package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceMediaCleanupRepositoryTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void reconciliationUsesSkipLockedAndDurablyQueuesBothOrphanStates() {
        OffsetDateTime stagedBefore = OffsetDateTime.now().minusHours(2);
        when(jdbc.queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                eq(Integer.class), eq(stagedBefore), eq(100))).thenReturn(2);
        WorkplaceMediaCleanupRepository repository =
                new WorkplaceMediaCleanupRepository(jdbc);

        assertThat(repository.reconcile(100, stagedBefore)).isEqualTo(2);

        verify(jdbc).queryForObject(
                argThat(sql -> sql.contains("FOR UPDATE SKIP LOCKED")
                        && sql.contains("FLOOR_PLAN_STAGED_ORPHAN")
                        && sql.contains("FLOOR_PLAN_UNREFERENCED")
                        && sql.contains("ON CONFLICT DO NOTHING")),
                eq(Integer.class), eq(stagedBefore), eq(100));
    }
}
