package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkplaceReleaseWindowRepositoryTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void repositoryContractExposesCoverageOwnershipAndCancellationOperations() {
        String source = java.util.Arrays.stream(
                        WorkplaceReleaseWindowRepository.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .sorted()
                .reduce("", (left, right) -> left + '|' + right);

        assertThat(source)
                .contains("coveringWindowForBooking")
                .contains("lockWindowForUpdate")
                .contains("ownedWindow")
                .contains("cancel");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bookingCoverageLocksTheAuthorizingWindow() {
        WorkplaceReleaseWindowRepository repository =
                new WorkplaceReleaseWindowRepository(jdbc);
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(1);
        OffsetDateTime endsAt = startsAt.plusHours(2);

        repository.coveringWindowForBooking(1L, resourceId, startsAt, endsAt);

        verify(jdbc).query(
                argThat(sql -> sql.contains("release_status = 'ACTIVE'")
                        && sql.contains("starts_at <= ?")
                        && sql.contains("ends_at >= ?")
                        && sql.contains("FOR SHARE")),
                any(org.springframework.jdbc.core.RowMapper.class),
                eq(1L), eq(resourceId), eq(startsAt), eq(endsAt));
    }
}
