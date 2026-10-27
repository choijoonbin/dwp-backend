package com.dwp.services.space.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.space.integration.SpaceEntitlementPort;
import com.dwp.services.space.operations.SpaceOperationsService;
import com.dwp.services.space.security.SpaceRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * Real module migrations in a disposable PostgreSQL container, following the Meeting test pattern.
 * No application datasource or local database credentials are consulted. Each ordinary test also
 * rolls back its fixture data and successful commands; container disposal removes everything else.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SpaceMembershipPostgresFixture {

    static final long TENANT = 91001L;
    static final long OTHER_TENANT = 91002L;
    static final String GROUP_REF = "space-validity-test-group";
    static final UUID PERSON_ID = UUID.fromString("19daae20-f2d5-4d87-b3bd-2086b65bf44c");

    JdbcTemplate jdbc;
    SpaceQueryRepository queries;
    SpaceCommandRepository commands;
    ObjectMapper mapper;
    TransactionTemplate transaction;

    abstract PostgreSQLContainer<?> postgres();

    @BeforeAll
    void migrateDisposableDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres().getJdbcUrl());
        dataSource.setUser(postgres().getUsername());
        dataSource.setPassword(postgres().getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        mapper = new ObjectMapper().findAndRegisterModules();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        queries = new SpaceQueryRepository(named, mapper);
        commands = new SpaceCommandRepository(named, mapper);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    void clearRequestContext() {
        SpaceRequestContext.clear();
    }

    void inTransaction(Runnable test) {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            try {
                SpaceRequestContext.set(subject(TENANT));
                test.run();
            } finally {
                SpaceRequestContext.clear();
            }
        });
    }

    SpaceRequestContext.Subject subject(long tenantId) {
        return new SpaceRequestContext.Subject(91003L, tenantId, PERSON_ID, "Membership test actor",
                Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.SPACES:VIEW", "ACTION.SPACE_MEMBERSHIP:CREATE",
                        "ACTION.SPACE_MEMBERSHIP:MANAGE"),
                Set.of(GROUP_REF));
    }

    SpaceService service() {
        return new SpaceService(queries, commands, mock(SpaceTemplateCommandRepository.class),
                mock(SpaceOwnerRecoveryRepository.class), mock(AuditOutboxRecorder.class),
                mock(SpaceOperationsService.class), mock(SpaceEntitlementPort.class));
    }

    UUID space(long tenantId, String visibility) {
        jdbc.update("INSERT INTO spc_tenants (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", tenantId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO spc_spaces (space_id, tenant_id, space_key, name_ko, name_en,
                    purpose_type, visibility, data_classification)
                VALUES (?, ?, ?, 'Membership regression', 'Membership regression',
                    'PROJECT', ?, 'INTERNAL')
                """, id, tenantId, key(id), visibility);
        return id;
    }

    String key(UUID spaceId) {
        return "membership-" + spaceId;
    }

    void activity(long tenantId, UUID spaceId, int hoursAgo) {
        jdbc.update("""
                INSERT INTO spc_activity_events (activity_id, tenant_id, space_id, activity_type,
                    actor_type, object_type, object_ref, title_ko, title_en, occurred_at)
                VALUES (?, ?, ?, 'TEST_SIGNAL', 'SYSTEM', 'SPACE', ?, 'Test signal', 'Test signal',
                    CURRENT_TIMESTAMP - (? * INTERVAL '1 hour'))
                """, UUID.randomUUID(), tenantId, spaceId, spaceId.toString(), hoursAgo);
    }

    UUID member(long tenantId, UUID spaceId, String principalType, String principalRef,
                String role, MembershipWindow window) {
        UUID id = UUID.randomUUID();
        // The SQL fragments come exclusively from this fixture's enum. All fixture data is bound.
        jdbc.update("""
                INSERT INTO spc_memberships (membership_id, tenant_id, space_id, principal_type,
                    principal_ref, member_role, membership_source, lifecycle_state, valid_from, valid_until)
                VALUES (?, ?, ?, ?, ?, ?, 'DIRECT', ?, %s, %s)
                """.formatted(window.fromSql, window.untilSql),
                id, tenantId, spaceId, principalType, principalRef, role, window.state);
        return id;
    }

    enum Principal {
        USER_ID("USER"), PERSON_ID("USER"), GROUP("GROUP");

        final String type;

        Principal(String type) {
            this.type = type;
        }

        String ref(SpaceRequestContext.Subject subject) {
            return switch (this) {
                case USER_ID -> Long.toString(subject.userId());
                case PERSON_ID -> subject.personPublicId().toString();
                case GROUP -> GROUP_REF;
            };
        }
    }

    enum MembershipWindow {
        VALID("ACTIVE", "CURRENT_TIMESTAMP - INTERVAL '1 day'",
                "CURRENT_TIMESTAMP + INTERVAL '1 day'", true),
        INDEFINITE("ACTIVE", "CURRENT_TIMESTAMP - INTERVAL '1 day'", "NULL", true),
        STARTS_NOW("ACTIVE", "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP + INTERVAL '1 day'", true),
        ENDS_NOW("ACTIVE", "CURRENT_TIMESTAMP - INTERVAL '1 day'", "CURRENT_TIMESTAMP", false),
        EXPIRED_WINDOW("ACTIVE", "CURRENT_TIMESTAMP - INTERVAL '2 days'",
                "CURRENT_TIMESTAMP - INTERVAL '1 day'", false),
        FUTURE("ACTIVE", "CURRENT_TIMESTAMP + INTERVAL '1 day'", "NULL", false),
        REVOKED("REVOKED", "CURRENT_TIMESTAMP - INTERVAL '1 day'", "NULL", false),
        EXPIRED_STATE("EXPIRED", "CURRENT_TIMESTAMP - INTERVAL '1 day'", "NULL", false),
        PENDING("PENDING", "CURRENT_TIMESTAMP - INTERVAL '1 day'", "NULL", false);

        final String state;
        final String fromSql;
        final String untilSql;
        final boolean effective;

        MembershipWindow(String state, String fromSql, String untilSql, boolean effective) {
            this.state = state;
            this.fromSql = fromSql;
            this.untilSql = untilSql;
            this.effective = effective;
        }
    }
}
