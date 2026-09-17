package com.dwp.services.platform.mail;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MailWritingAssetDefaultScopeMigrationPostgresTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void v290UpgradeSeparatesPersonalAccountAndOrganizationSignatureDefaults() {
        String schema = "mail_writing_asset_default_scope";
        Flyway throughV289 = flyway(schema, "289");
        throughV289.clean();
        throughV289.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        Fixture fixture = fixture(jdbc);

        insertLegacyPersonalDefault(jdbc, fixture, UUID.randomUUID());
        flyway(schema, "290").migrate();

        insertOrganizationDefault(jdbc, fixture, UUID.randomUUID());
        insertAccountDefault(jdbc, fixture, UUID.randomUUID());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM mail_signatures
                 WHERE tenant_id = ? AND owner_user_id = ? AND default_for_new
                """, Integer.class, fixture.tenantId(), fixture.userId())).isEqualTo(3);

        assertThatThrownBy(() -> insertPersonalDefault(jdbc, fixture, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAccountDefault(jdbc, fixture, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        String personalDefinition = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname = current_schema()
                   AND indexname = 'uk_mail_signature_personal_default'
                """, String.class);
        String accountDefinition = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname = current_schema()
                   AND indexname = 'uk_mail_signature_account_default'
                """, String.class);
        assertThat(personalDefinition).contains("signature_scope", "'PERSONAL'");
        assertThat(accountDefinition).contains("signature_scope", "'ACCOUNT'");
    }

    private void insertLegacyPersonalDefault(JdbcTemplate jdbc, Fixture fixture, UUID signatureId) {
        jdbc.update("""
                INSERT INTO mail_signatures (
                    signature_id, tenant_id, owner_user_id, signature_scope,
                    display_name, body_content, body_format, default_for_new,
                    default_for_reply, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, 'PERSONAL', 'Personal default', 'Regards', 'TEXT',
                        TRUE, TRUE, 'ACTIVE', ?, ?)
                """, signatureId, fixture.tenantId(), fixture.userId(),
                fixture.userId(), fixture.userId());
    }

    private void insertPersonalDefault(JdbcTemplate jdbc, Fixture fixture, UUID signatureId) {
        jdbc.update("""
                INSERT INTO mail_signatures (
                    signature_id, tenant_id, owner_user_id, signature_scope,
                    display_name, body_content, body_format, default_for_new,
                    default_for_reply, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, 'PERSONAL', 'Second personal default', 'Regards', 'TEXT',
                        TRUE, FALSE, 'ACTIVE', ?, ?)
                """, signatureId, fixture.tenantId(), fixture.userId(),
                fixture.userId(), fixture.userId());
    }

    private void insertAccountDefault(JdbcTemplate jdbc, Fixture fixture, UUID signatureId) {
        jdbc.update("""
                INSERT INTO mail_signatures (
                    signature_id, tenant_id, owner_user_id, account_id, signature_scope,
                    display_name, body_content, body_format, default_for_new,
                    default_for_reply, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'ACCOUNT', 'Account default', 'Regards', 'TEXT',
                        TRUE, FALSE, 'ACTIVE', ?, ?)
                """, signatureId, fixture.tenantId(), fixture.userId(), fixture.accountId(),
                fixture.userId(), fixture.userId());
    }

    private void insertOrganizationDefault(JdbcTemplate jdbc, Fixture fixture, UUID signatureId) {
        jdbc.update("""
                INSERT INTO mail_signatures (
                    signature_id, tenant_id, owner_user_id, signature_scope,
                    display_name, body_content, body_format, default_for_new,
                    default_for_reply, mandatory_content, lifecycle_state,
                    publication_key, publication_version, publication_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'ORGANIZATION', 'Organization default', 'Regards', 'TEXT',
                        TRUE, TRUE, 'Required footer', 'ACTIVE', ?, 1, 'DRAFT', ?, ?)
                """, signatureId, fixture.tenantId(), fixture.userId(), UUID.randomUUID(),
                fixture.userId(), fixture.userId());
    }

    private Fixture fixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT tenant_id, owner_user_id, account_id
                  FROM mail_accounts
                 WHERE account_kind = 'PERSONAL' AND owner_user_id IS NOT NULL
                 ORDER BY tenant_id, owner_user_id, account_id
                 LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Mail fixture is missing.");
            return new Fixture(
                    result.getLong("tenant_id"),
                    result.getLong("owner_user_id"),
                    result.getObject("account_id", UUID.class));
        });
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource source = dataSource();
        source.setCurrentSchema(schema);
        return source;
    }

    private record Fixture(long tenantId, long userId, UUID accountId) {
    }
}
