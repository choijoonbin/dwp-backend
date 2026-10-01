package com.dwp.services.people.hris.identity.v1;

import com.dwp.platform.contracts.hris.identity.v1.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.people.hris.identity.v1.NativeSelfPersonQueryReaderV1Test.*;
import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;
import static org.assertj.core.api.Assertions.*;

/** Author isolated native People reads. Authority/Auth are explicit MOCK ONLY; signed transport/current PEP/wiring OPEN. */
@Testcontainers
class NativeSelfPersonQueryReaderV1PostgresTest {
    private static final String RUNTIME="people_self_person_read_test";
    private static final String PASSWORD=UUID.randomUUID().toString();
    @Container
    private static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE","postgres:16-alpine"));
    private static JdbcTemplate owner;
    private static PGSimpleDataSource runtime;
    private UUID person;
    private long personId;
    private NativeSelfPersonQueryReaderV1 reader;

    @BeforeAll
    static void migrateActualOwnerSourceAndGrantOnlyFourPersonColumns() throws SQLException {
        var ownerSource=source(POSTGRES.getUsername(),POSTGRES.getPassword());
        owner=new JdbcTemplate(ownerSource);
        var flyway=Flyway.configure().dataSource(ownerSource).locations("filesystem:src/main/resources/db/migration")
                .validateOnMigrate(true).outOfOrder(false).load();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("51");
        assertThat(flyway.info().applied()).hasSize(51);
        try(Connection connection=ownerSource.getConnection()) {
            try(var value=connection.prepareStatement("SELECT set_config('dwp.test_password', ?, false)")) {
                value.setString(1,PASSWORD); value.execute();
            }
            try(var statement=connection.createStatement()) {
                statement.execute("""
                        DO $role$ BEGIN
                            EXECUTE format('CREATE ROLE people_self_person_read_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L',
                                           current_setting('dwp.test_password'));
                        END $role$;
                        """);
            }
        }
        String catalog=POSTGRES.getDatabaseName();
        assertThat(catalog).matches("[a-zA-Z0-9_]+");
        owner.execute("REVOKE CREATE, TEMPORARY ON DATABASE "+catalog+" FROM PUBLIC");
        owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE "+catalog+" TO "+RUNTIME);
        owner.execute("GRANT USAGE ON SCHEMA public TO "+RUNTIME);
        owner.execute("GRANT SELECT (tenant_id,public_id,version,lifecycle_state) ON public.ppl_persons TO "+RUNTIME);
        runtime=source(RUNTIME,PASSWORD);
    }

    @BeforeEach
    void ownedPersonFixtureNeedsNoWorkerOrLocationAndNeverDeletesEvidence() {
        person=UUID.randomUUID();
        personId=owner.queryForObject("INSERT INTO ppl_persons(tenant_id,public_id,person_key,display_name,time_zone) VALUES (?,?,?,?,?) RETURNING person_id",
                Long.class,TENANT,person,"self-profile-"+person,"Native confidential profile","secret-not-a-work-zone");
        reader=new NativeSelfPersonQueryReaderV1(runtime,CLOCK,Duration.ofSeconds(5));
    }

    @Test
    void personWithoutWorkerEmploymentLocationOrTimDependencyReadsOwnMetadata() {
        assertThat(owner.queryForObject("SELECT count(*) FROM ppl_workers WHERE tenant_id=? AND person_id=?",Integer.class,TENANT,personId)).isZero();
        var result=resolve(reader,CLOCK,person,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE));
        assertThat(result.person().personPublicId()).isEqualTo(person);
        assertThat(result.person().personVersion()).isZero();
        assertThat(result.person().personState()).isEqualTo(NativeSelfContextSetV1.PersonState.ACTIVE);
        assertThat(owner.queryForObject("SELECT to_regclass('public.com_users') IS NULL",Boolean.class)).isTrue();
        assertThat(owner.queryForObject("SELECT to_regclass('public.tim_hris_schedules') IS NULL",Boolean.class)).isTrue();
    }

    @Test
    void locationLessNativeAssignmentDoesNotBlockCommonSelfPersonProfile() {
        long worker=owner.queryForObject("INSERT INTO ppl_workers(tenant_id,person_id,worker_number,worker_type) VALUES (?,?,?,'EMPLOYEE') RETURNING worker_id",
                Long.class,TENANT,personId,person+":worker");
        long employer=owner.queryForObject("INSERT INTO ppl_legal_employers(tenant_id,employer_key,legal_name) VALUES (?,?,?) RETURNING legal_employer_id",
                Long.class,TENANT,person+":employer","Native employer");
        long relationship=owner.queryForObject("INSERT INTO ppl_work_relationships(tenant_id,relationship_key,worker_id,legal_employer_id,relationship_type,start_date) VALUES (?,?,?,?,'EMPLOYEE',DATE '2020-01-01') RETURNING work_relationship_id",
                Long.class,TENANT,person+":relationship",worker,employer);
        long assignment=owner.queryForObject("INSERT INTO ppl_assignments(tenant_id,assignment_key,work_relationship_id,effective_start_date) VALUES (?,?,?,DATE '2020-01-01') RETURNING assignment_id",
                Long.class,TENANT,person+":assignment",relationship);
        assertThat(owner.queryForObject("SELECT location_id IS NULL FROM ppl_assignments WHERE assignment_id=?",Boolean.class,assignment)).isTrue();
        assertThat(resolve(reader,CLOCK,person,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)).person().personPublicId()).isEqualTo(person);
        assertDenied("SELECT * FROM public.ppl_assignments");
        assertDenied("SELECT * FROM public.ppl_locations");
    }

    @Test
    void realAdvancingSystemClockCanIssueFreshNativePersonProofWithoutCachedInitialTimestamp() {
        Clock live=Clock.systemUTC();
        Instant started=live.instant();
        var liveReader=new NativeSelfPersonQueryReaderV1(runtime,live,Duration.ofSeconds(5));
        var result=resolve(liveReader,live,person,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE));
        assertThat(result.person().capturedAt()).isAfterOrEqualTo(started);
        assertThat(result.verifiedAt()).isAfterOrEqualTo(result.person().capturedAt());
        assertThat(result.person().expiresAt()).isAfter(result.verifiedAt());
        assertThat(result.person().personPublicId()).isEqualTo(person);
    }

    @Test
    void freshNativePersonRevisionAndStateAreNotCachedOrSynthesized() {
        assertThat(resolve(reader,CLOCK,person,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)).person().personVersion()).isZero();
        owner.update("UPDATE ppl_persons SET version=version+1 WHERE tenant_id=? AND person_id=?",TENANT,personId);
        assertThat(resolve(reader,CLOCK,person,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)).person().personVersion()).isEqualTo(1);
        owner.update("UPDATE ppl_persons SET lifecycle_state='INACTIVE',version=version+1 WHERE tenant_id=? AND person_id=?",TENANT,personId);
        reject(() -> resolve(reader,CLOCK,person,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)),SELF_SCOPE_UNRESOLVED);
        var former=resolve(reader,CLOCK,person,TENANT,Set.of(NativeSelfContextSetV1.PersonState.INACTIVE));
        assertThat(former.person().personVersion()).isEqualTo(2);
        assertThat(former.person().personState()).isEqualTo(NativeSelfContextSetV1.PersonState.INACTIVE);
    }

    @Test
    void missingForeignTenantAndPrincipalUuidCannotBecomePersonByEquality() {
        reject(() -> resolve(reader,CLOCK,UUID.randomUUID(),TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)),SELF_SCOPE_UNRESOLVED);
        reject(() -> resolve(reader,CLOCK,person,TENANT+1,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)),SELF_SCOPE_UNRESOLVED);
        reject(() -> resolve(reader,CLOCK,PRINCIPAL,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE)),SELF_SCOPE_UNRESOLVED);
    }

    @Test
    void mergedPersonNotRelinkedByCallerUuidEmailOrDisplayName() {
        owner.update("UPDATE ppl_persons SET lifecycle_state='MERGED' WHERE tenant_id=? AND person_id=?",TENANT,personId);
        assertThat(catchThrowable(() -> resolve(reader,CLOCK,person,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE))))
                .hasMessage("Self-context contract rejected: OWNER_RESPONSE_INVALID").hasNoCause();
    }

    @Test
    void revokingOnlyPersonColumnSelectFailsClosedWithoutSecretSqlCause() {
        owner.execute("REVOKE SELECT(version) ON public.ppl_persons FROM "+RUNTIME);
        try {
            assertThat(catchThrowable(() -> resolve(reader,CLOCK,person,TENANT,Set.of(NativeSelfContextSetV1.PersonState.ACTIVE))))
                    .hasMessage("Self-context contract rejected: OWNER_UNAVAILABLE").hasNoCause();
        } finally {
            owner.execute("GRANT SELECT(version) ON public.ppl_persons TO "+RUNTIME);
        }
    }

    @Test
    void runtimePrincipalCannotReadPiiParentsAuthHistoryOrExecuteDdlDmlTempAndSetRole() throws SQLException {
        var query=new JdbcTemplate(runtime);
        assertThat(query.queryForObject("SELECT current_user=session_user AND current_user=?",Boolean.class,RUNTIME)).isTrue();
        assertThat(query.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user",Boolean.class)).isFalse();
        assertDenied("SELECT display_name FROM public.ppl_persons");
        assertDenied("SELECT time_zone FROM public.ppl_persons");
        assertDenied("SELECT person_id FROM public.ppl_persons");
        assertDenied("SELECT * FROM public.ppl_workers");
        assertDenied("SELECT * FROM public.ppl_assignments");
        assertDenied("SELECT * FROM public.flyway_schema_history");
        assertDenied("UPDATE public.ppl_persons SET lifecycle_state='INACTIVE' WHERE public_id='"+person+"'");
        assertDenied("DELETE FROM public.ppl_persons WHERE public_id='"+person+"'");
        assertDenied("INSERT INTO public.ppl_persons(tenant_id,person_key,display_name) VALUES ("+TENANT+",'denied','denied')");
        assertDenied("CREATE TABLE public.self_person_runtime_sentinel(id BIGINT)");
        assertDenied("CREATE TEMPORARY TABLE self_person_runtime_sentinel(id BIGINT)");
        assertDenied("SET ROLE "+POSTGRES.getUsername());
    }

    private static void assertDenied(String sql) {
        try(Connection connection=runtime.getConnection();var statement=connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql)).isInstanceOfSatisfying(SQLException.class,error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        } catch(SQLException ownerTestSetupFailure) { throw new AssertionError("owned runtime connection failed",ownerTestSetupFailure); }
    }
    private static PGSimpleDataSource source(String user,String password) {
        var value=new PGSimpleDataSource(); value.setURL(POSTGRES.getJdbcUrl()); value.setUser(user); value.setPassword(password);
        value.setConnectTimeout(5); value.setSocketTimeout(10); return value;
    }
}
