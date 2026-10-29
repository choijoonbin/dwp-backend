package com.dwp.services.messaging.retention;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagingRetentionRepositoryTest {

    @Test
    void draftBacklogCannotConsumeTheScheduledPayloadRedactionBudget() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OffsetDateTime cutoff = OffsetDateTime.parse("2026-09-01T00:00:00Z");
        when(jdbc.update(anyString(), eq(71L), eq(cutoff), eq(100)))
                .thenReturn(100, 7);
        MessagingRetentionRepository repository = new MessagingRetentionRepository(jdbc);

        MessagingRetentionRepository.ContinuityPurgeResult result =
                repository.purgeContinuityContent(71, cutoff, 100);

        assertThat(result.deletedDrafts()).isEqualTo(100);
        assertThat(result.redactedSchedules()).isEqualTo(7);
        verify(jdbc, times(2)).update(anyString(), eq(71L), eq(cutoff), eq(100));
    }
}
