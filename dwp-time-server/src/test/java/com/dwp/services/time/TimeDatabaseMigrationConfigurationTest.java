package com.dwp.services.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.core.database.RuntimeRoutineExecutionGuard;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class TimeDatabaseMigrationConfigurationTest {

    @Test
    void runtimeRoutineAllowlistContainsOnlyTheTim003AssignmentValidator() {
        assertThat(TimeDatabaseMigrationConfiguration.runtimeRoutines())
                .containsExactly(new RuntimeRoutineExecutionGuard.Routine(
                        "public",
                        "tim003_assignment_snapshots_structurally_valid",
                        "jsonb,uuid"));
    }

    @Test
    void ownerTriggerBoundaryPreservesLegacyDefinersAndAdmitsTheTim003Invoker()
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement query = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString(1)).thenReturn("public", "public");
        when(resultSet.getString(2)).thenReturn("public", "public");
        when(resultSet.getString(3))
                .thenReturn(
                        "tim_guard_command_receipt_transition",
                        "tim003_seal_employment_projection_update");
        when(resultSet.getString(4)).thenReturn("", "");
        when(resultSet.getString(5))
                .thenReturn("dwp_time_migration", "dwp_time_migration");
        when(resultSet.getBoolean(6)).thenReturn(true, false);
        when(resultSet.getString(7)).thenReturn(
                "search_path=pg_catalog, public, pg_temp",
                "search_path=pg_catalog");
        when(resultSet.getBoolean(8)).thenReturn(false, false);

        assertThatCode(() -> TimeDatabaseMigrationConfiguration
                .verifyOwnerTriggerExecutionBoundary(
                        dataSource, "dwp_time_migration"))
                .doesNotThrowAnyException();

        verify(query).setString(1, "public");
        verify(connection).rollback();
    }

    @Test
    void publisherPolicyAdmitsOnlyTheFourExactOutboxRelaySurfaces() {
        var policy = TimeDatabaseMigrationConfiguration.publisherPolicy(
                "dwp_time_projection_publisher", true);

        assertThat(policy.allowedAclPrivileges().stream()
                .filter(privilege -> privilege.objectIdentity()
                        .contains("outbox_events"))
                .map(privilege -> privilege.objectClass() + ":"
                        + privilege.objectIdentity() + ":" + privilege.privilege()))
                .containsExactlyInAnyOrder(
                        "RELATION:public.tme_clock_outbox_events:SELECT",
                        "COLUMN:public.tme_clock_outbox_events.publish_attempts:UPDATE",
                        "COLUMN:public.tme_clock_outbox_events.published_at:UPDATE",
                        "RELATION:public.tme_interpretation_outbox_events:SELECT",
                        "COLUMN:public.tme_interpretation_outbox_events.publish_attempts:UPDATE",
                        "COLUMN:public.tme_interpretation_outbox_events.published_at:UPDATE",
                        "RELATION:public.tme_timecard_outbox_events:SELECT",
                        "COLUMN:public.tme_timecard_outbox_events.publish_attempts:UPDATE",
                        "COLUMN:public.tme_timecard_outbox_events.published_at:UPDATE",
                        "RELATION:public.tme_close_outbox_events:SELECT",
                        "COLUMN:public.tme_close_outbox_events.publish_attempts:UPDATE",
                        "COLUMN:public.tme_close_outbox_events.published_at:UPDATE");
        assertThat(policy.requiredAclPrivileges())
                .isEqualTo(policy.allowedAclPrivileges());
    }
}
