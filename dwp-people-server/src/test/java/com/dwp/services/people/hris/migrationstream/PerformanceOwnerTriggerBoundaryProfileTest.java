package com.dwp.services.people.hris.migrationstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class PerformanceOwnerTriggerBoundaryProfileTest {

    @Test
    void bootstrapAllowsLegacyAndPerformanceFixedSearchPathProfiles() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        PreparedStatement query = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getString(1)).thenReturn(
                "public", "hris_performance", "hris_performance");
        when(resultSet.getString(2)).thenReturn(
                "public", "hris_performance", "hris_performance");
        when(resultSet.getString(3))
                .thenReturn(
                        "sys_people_audit_to_outbox",
                        "prf_capture_review_operation_trigger",
                        "prf_guard_review_operation_history");
        when(resultSet.getString(4)).thenReturn("", "", "");
        when(resultSet.getString(5)).thenReturn(
                "dwp_people_migration", "dwp_people_migration", "dwp_people_migration");
        when(resultSet.getBoolean(6)).thenReturn(true, true, false);
        when(resultSet.getString(7)).thenReturn(
                "search_path=pg_catalog, public, pg_temp",
                "search_path=pg_catalog",
                "search_path=pg_catalog");
        when(resultSet.getBoolean(8)).thenReturn(false, false, false);

        assertThat(PerformanceMigrationStreamBootstrap.OWNER_TRIGGER_BOUNDARY_SCHEMAS)
                .containsExactly("public", "hris_performance");
        assertThat(PerformanceMigrationStreamBootstrap.OWNER_TRIGGER_SEARCH_PATH_SETTINGS)
                .containsExactlyInAnyOrder(
                        "search_path=pg_catalog, public, pg_temp",
                        "search_path=pg_catalog");
        assertThatCode(() -> PerformanceMigrationStreamBootstrap
                .verifyOwnerTriggerExecutionBoundary(dataSource, "dwp_people_migration"))
                .doesNotThrowAnyException();

        verify(query).setString(1, "public");
        verify(query).setString(2, "hris_performance");
        verify(connection).rollback();
    }
}
