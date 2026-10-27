package com.dwp.services.platform.hrisconfiguration.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.integration.JdbcIntegrationRepositoryData.*;
import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Internal jdbc integration repository support operations shared by the owner facade. */
final class JdbcIntegrationRepositorySupport {
    private final JdbcTemplate jdbc;

    JdbcIntegrationRepositorySupport(
            JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }


    public OffsetDateTime databaseNow() {
        return jdbc.queryForObject(
                "SELECT statement_timestamp()", OffsetDateTime.class);
    }

    public void bindTenantPrincipal(
            long tenantId, UUID subjectPrincipalPublicId) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                String.class, Long.toString(tenantId));
        jdbc.queryForObject("SELECT set_config('dwp.principal_public_id', ?, true)",
                String.class, subjectPrincipalPublicId.toString());
    }
}
