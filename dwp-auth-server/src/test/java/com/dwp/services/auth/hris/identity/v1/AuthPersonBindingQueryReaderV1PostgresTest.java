package com.dwp.services.auth.hris.identity.v1;

import com.dwp.platform.contracts.hris.identity.v1.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import static com.dwp.services.auth.hris.identity.v1.AuthPersonBindingQueryReaderV1Test.*;
import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;
import static org.assertj.core.api.Assertions.*;

/** Actual native Auth reads on disposable PostgreSQL. Current verifier and People remain MOCK ONLY. */
@Testcontainers
class AuthPersonBindingQueryReaderV1PostgresTest {
    private static final String RUNTIME = "auth_identity_read_test";
    private static final String PASSWORD = UUID.randomUUID().toString();
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));
    private static JdbcTemplate owner;
    private static PGSimpleDataSource runtime;
    private AuthPersonBindingQueryReaderV1 reader;

    @BeforeAll
    static void migrateOwnedDatabaseWithUnchangedNativeMigrations() throws SQLException {
        PGSimpleDataSource ownerSource = source(POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerSource);
        var flyway = Flyway.configure().dataSource(ownerSource)
                .locations("filesystem:src/main/resources/db/migration").validateOnMigrate(true).outOfOrder(false).load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("216");
        assertThat(flyway.info().applied()).hasSize(118);
        try (Connection connection = ownerSource.getConnection()) {
            try (var value = connection.prepareStatement("SELECT set_config('dwp.test_password', ?, false)")) {
                value.setString(1, PASSWORD);
                value.execute();
            }
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        DO $role$ BEGIN
                            EXECUTE format('CREATE ROLE auth_identity_read_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L',
                                           current_setting('dwp.test_password'));
                        END $role$;
                        """);
            }
        }
        String catalog = POSTGRES.getDatabaseName();
        assertThat(catalog).matches("[a-zA-Z0-9_]+");
        owner.execute("REVOKE CREATE, TEMPORARY ON DATABASE " + catalog + " FROM PUBLIC");
        owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE " + catalog + " TO " + RUNTIME);
        owner.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME);
        owner.execute("GRANT SELECT (tenant_id,user_id,public_id,person_public_id,identity_plane,status,version,access_revision) ON public.com_users TO " + RUNTIME);
        runtime = source(RUNTIME, PASSWORD);
        owner.update("INSERT INTO com_tenants(tenant_id,code,name) VALUES (?,?,?)", TENANT, "native-identity-test", "Owned test tenant");
        owner.update("INSERT INTO com_users(user_id,tenant_id,display_name,public_id,person_public_id) VALUES (?,?,?,?,?)",
                USER, TENANT, "Native read fixture", PRINCIPAL, PERSON);
    }

    @BeforeEach
    void resetOnlyOwnedSyntheticPrincipalThroughOwner() {
        owner.update("UPDATE com_users SET status='ACTIVE',person_public_id=?,version=0,access_revision=0 WHERE tenant_id=? AND user_id=?",
                PERSON, TENANT, USER);
        reader = new AuthPersonBindingQueryReaderV1(runtime, CLOCK, Duration.ofSeconds(10));
    }

    @Test
    void readsCurrentNativeBindingAndZeroNativeVersionsWithNoCrossOwnerDatabase() {
        var selected = resolve(reader, authority(0, 0), CLOCK).selected();
        assertThat(selected.binding().personPublicId()).isEqualTo(PERSON);
        assertThat(selected.binding().principalPublicId()).isEqualTo(PRINCIPAL);
        assertThat(selected.binding().userRowVersion()).isZero();
        assertThat(selected.binding().accessRevision()).isZero();
        assertThat(owner.queryForObject("SELECT to_regclass('public.ppl_persons') IS NULL", Boolean.class)).isTrue();
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"INVITED", "SUSPENDED", "INACTIVE"})
    void nativeStatusRevocationIsReadFreshAndRejected(String status) {
        resolve(reader, authority(0, 0), CLOCK);
        owner.update("UPDATE com_users SET status=? WHERE tenant_id=? AND user_id=?", status, TENANT, USER);
        reject(() -> resolve(reader, authority(0, 0), CLOCK), AUTH_BINDING_REVOKED);
    }

    @Test
    void nativeRowVersionAndAccessRevisionAreNotCached() {
        resolve(reader, authority(0, 0), CLOCK);
        owner.update("UPDATE com_users SET version=version+1,access_revision=access_revision+1 WHERE tenant_id=? AND user_id=?", TENANT, USER);
        reject(() -> resolve(reader, authority(0, 0), CLOCK), AUTH_BINDING_STALE);
        var refreshed = resolve(reader, authority(1, 1), CLOCK).selected().binding();
        assertThat(refreshed.userRowVersion()).isEqualTo(1);
        assertThat(refreshed.accessRevision()).isEqualTo(1);
    }

    @Test
    void nullNativePersonCannotUsePublicPrincipalAsFallback() {
        owner.update("UPDATE com_users SET person_public_id=NULL WHERE tenant_id=? AND user_id=?", TENANT, USER);
        reject(() -> resolve(reader, authority(0, 0), CLOCK), AUTH_BINDING_INVALID);
    }

    @Test
    void zeroNativePublicPrincipalUuidCannotMintAuthority() {
        owner.update("UPDATE com_users SET public_id=? WHERE tenant_id=? AND user_id=?", new UUID(0, 0), TENANT, USER);
        try {
            reject(() -> resolve(reader, authority(0, 0), CLOCK), AUTH_BINDING_INVALID);
        } finally {
            owner.update("UPDATE com_users SET public_id=? WHERE tenant_id=? AND user_id=?", PRINCIPAL, TENANT, USER);
        }
    }

    @Test
    void foreignTenantAndRelinkedPersonCannotUseActorSuppliedIdentity() {
        var current = authority(0, 0);
        var foreign = new SelfContextAuthorityV1(TENANT + 1, USER, PRINCIPAL, PERSON, 0, 0,
                current.audience(), current.purpose(), true, true, 0, NOW, NOW.plusSeconds(10), current.effectivePolicy());
        reject(() -> resolve(reader, foreign, CLOCK), AUTH_BINDING_INVALID);
        owner.update("UPDATE com_users SET person_public_id=? WHERE tenant_id=? AND user_id=?", UUID.randomUUID(), TENANT, USER);
        reject(() -> resolve(reader, current, CLOCK), AUTH_BINDING_INVALID);
    }

    @Test
    void actualQueryAfterCurrentAuthorityExpiryIsRejected() {
        var expiredAtRead = new AuthPersonBindingQueryReaderV1(runtime,
                java.time.Clock.fixed(NOW.plusSeconds(10), java.time.ZoneOffset.UTC), Duration.ofSeconds(10));
        reject(() -> resolve(expiredAtRead, authority(0, 0), CLOCK), AUTH_BINDING_STALE);
    }

    @Test
    void nativeSelectPrivilegeLossIsGenericOwnerUnavailable() {
        owner.execute("REVOKE SELECT (status) ON public.com_users FROM " + RUNTIME);
        try {
            assertThat(catchThrowable(() -> resolve(reader, authority(0, 0), CLOCK)))
                    .isInstanceOfSatisfying(SelfContextContractExceptionV1.class, error -> {
                        assertThat(error.code()).isEqualTo(OWNER_UNAVAILABLE);
                        assertThat(error).hasMessage("Self-context contract rejected: OWNER_UNAVAILABLE").hasNoCause();
                    });
        } finally {
            owner.execute("GRANT SELECT (status) ON public.com_users TO " + RUNTIME);
        }
    }

    @Test
    void runtimeHasOnlyExactSelectColumnsAndCannotWriteDdlTempHistoryOrGrant() throws SQLException {
        JdbcTemplate query = new JdbcTemplate(runtime);
        assertThat(query.queryForObject("SELECT current_user=session_user AND current_user=?", Boolean.class, RUNTIME)).isTrue();
        assertThat(query.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        assertThat(query.queryForObject("SELECT has_column_privilege(current_user,'public.com_users','display_name','SELECT')", Boolean.class)).isFalse();
        assertDenied("UPDATE public.com_users SET status='INACTIVE' WHERE user_id=" + USER);
        assertDenied("DELETE FROM public.com_users WHERE user_id=" + USER);
        assertDenied("INSERT INTO public.com_users(tenant_id,display_name) VALUES (" + TENANT + ",'not allowed')");
        assertDenied("CREATE TABLE public.identity_runtime_sentinel(id BIGINT)");
        assertDenied("CREATE TEMPORARY TABLE identity_runtime_sentinel(id BIGINT)");
        assertDenied("SELECT * FROM public.flyway_schema_history");
        assertDenied("SET ROLE " + POSTGRES.getUsername());
    }

    private static void assertDenied(String sql) throws SQLException {
        try (Connection connection = runtime.getConnection(); var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql)).isInstanceOfSatisfying(SQLException.class,
                    error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }

    private static PGSimpleDataSource source(String user, String password) {
        PGSimpleDataSource value = new PGSimpleDataSource();
        value.setURL(POSTGRES.getJdbcUrl());
        value.setUser(user);
        value.setPassword(password);
        value.setConnectTimeout(5);
        value.setSocketTimeout(10);
        return value;
    }
}
