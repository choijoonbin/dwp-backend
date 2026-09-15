package com.dwp.services.provider.widgetregistry;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.provider.security.ProviderOperatorService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ProviderWidgetRegistryAuthorityPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void resolvesWidgetPermissionsAndOnlyActiveDatabaseOwnerScopes() {
        PGSimpleDataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ProviderOperatorService operators = new ProviderOperatorService(jdbc);

        var actor = operators.activeOperator(1L, 900001L).orElseThrow();

        assertThat(actor.permissions()).contains(
                "WIDGET_CATALOG_READ",
                "WIDGET_DEFINITION_WRITE",
                "WIDGET_DEFINITION_REVIEW",
                "WIDGET_DEFINITION_RELEASE",
                "WIDGET_DEFINITION_REVOKE");
        assertThat(actor.ownerProductKeys()).containsExactlyInAnyOrder(
                "core.activity", "core.calendar", "core.work", "core.workspace");

        jdbc.update("""
                UPDATE prv_operator_widget_owner_scopes
                   SET lifecycle_state = 'REVOKED'
                 WHERE provider_operator_id = ?
                   AND owner_product_key = 'core.work'
                """, actor.operatorId());

        assertThat(operators.activeOperator(1L, 900001L).orElseThrow().ownerProductKeys())
                .containsExactlyInAnyOrder("core.activity", "core.calendar", "core.workspace")
                .doesNotContain("core.work");
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
