package com.dwp.services.time.workregime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcWorkRegimeAssignmentBoundaryTest {

    private static final LocalDate FROM = LocalDate.parse("2026-09-14");
    private static final LocalDate TO = LocalDate.parse("2026-09-21");
    private static final UUID ASSIGNMENT_ID =
            UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID WORKER_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_WORKER_ID =
            UUID.fromString("30000000-0000-0000-0000-000000000002");

    @Test
    void halfOpenIntersectionIncludesPartialAndOpenEndedPeriodsButExcludesAdjacency() {
        assertThat(JdbcWorkRegimeSimulationAssignmentSupport.intersects(
                FROM.plusDays(2), FROM.plusDays(4), FROM, TO)).isTrue();
        assertThat(JdbcWorkRegimeSimulationAssignmentSupport.intersects(
                FROM.plusDays(2), null, FROM, TO)).isTrue();
        assertThat(JdbcWorkRegimeSimulationAssignmentSupport.intersects(
                FROM.minusDays(2), FROM, FROM, TO)).isFalse();
        assertThat(JdbcWorkRegimeSimulationAssignmentSupport.intersects(
                TO, TO.plusDays(1), FROM, TO)).isFalse();
        assertThat(JdbcWorkRegimeSimulationAssignmentSupport.covers(
                FROM, TO, FROM, TO)).isTrue();
        assertThat(JdbcWorkRegimeSimulationAssignmentSupport.contains(
                FROM, TO, FROM)).isTrue();
        assertThat(JdbcWorkRegimeSimulationAssignmentSupport.contains(
                FROM, TO, TO)).isFalse();
    }

    @Test
    void currentBaselineMustBelongToTheTargetWorker() {
        assertThatCode(() -> JdbcWorkRegimeReadSupport.requireSameWorker(
                WORKER_ID, WORKER_ID, ASSIGNMENT_ID)).doesNotThrowAnyException();
        assertThatThrownBy(() -> JdbcWorkRegimeReadSupport.requireSameWorker(
                WORKER_ID, OTHER_WORKER_ID, ASSIGNMENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different workers")
                .hasMessageContaining(ASSIGNMENT_ID.toString());
    }
}
