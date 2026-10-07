package com.dwp.services.people.hr;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrTalentRepositoryTest {

    @Test
    void progressUpdateCannotChangeLifecycleAndRequiresTheCurrentEditableStatusAndVersion() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        HrTalentRepository repository = new HrTalentRepository(jdbc);
        UUID goalId = UUID.randomUUID();

        boolean updated = repository.updateGoal(
                3L, 41L, goalId,
                new HrDtos.UpdateGoalRequest(65, "ACTIVE", 7L), 17L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), arguments.capture());
        String normalized = sql.getValue().replaceAll("\\s+", " ").trim().toLowerCase();
        assertThat(updated).isTrue();
        assertThat(normalized)
                .contains("set progress_percent = ?, version = version + 1")
                .doesNotContain("set progress_percent = ?, status = ?")
                .contains("version = ? and status = ?")
                .contains("status in ('active', 'at_risk')");
        assertThat(arguments.getValue())
                .containsExactly(65, 17L, 3L, 41L, goalId, 7L, "ACTIVE");
    }
}
