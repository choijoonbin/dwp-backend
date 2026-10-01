package com.dwp.services.people.hris.identity.v1;

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
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.people.hris.identity.v1.NativeSelfContextQueryReaderV1Test.*;
import static com.dwp.platform.contracts.hris.identity.v1.SelfContextContractExceptionV1.Code.*;
import static org.assertj.core.api.Assertions.*;

/** Native People-only DB reads, not production wiring, signed Auth authority, lifecycle ordering or Gateway EE. */
@Testcontainers
class NativeSelfContextQueryReaderV1PostgresTest {
    private static final String RUNTIME = "people_identity_read_test";
    private static final String PASSWORD = UUID.randomUUID().toString();
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));
    private static JdbcTemplate owner;
    private static PGSimpleDataSource runtime;
    private static List<Map<String, Object>> originalEmployers, originalParents, originalParentConstraints, originalHistory;
    private UUID person;
    private long personId, employerId;
    private NativeSelfContextQueryReaderV1 reader;

    @BeforeAll
    static void migrateOwnedDatabaseWithoutFakeReaderColumns() throws SQLException {
        PGSimpleDataSource ownerSource = source(POSTGRES.getUsername(), POSTGRES.getPassword());
        owner = new JdbcTemplate(ownerSource);
        var original = Flyway.configure().dataSource(ownerSource)
                .locations("filesystem:src/main/resources/db/migration").target("48")
                .validateOnMigrate(true).outOfOrder(false).load();
        original.migrate();
        assertThat(original.info().current().getVersion().getVersion()).isEqualTo("48");
        originalEmployers = owner.queryForList("SELECT legal_employer_id,tenant_id,employer_key,legal_name,lifecycle_state,version FROM ppl_legal_employers ORDER BY legal_employer_id");
        originalParents = owner.queryForList("SELECT work_relationship_id,tenant_id,worker_id,legal_employer_id FROM ppl_work_relationships ORDER BY work_relationship_id");
        originalParentConstraints = owner.queryForList("SELECT conname,pg_get_constraintdef(oid) definition FROM pg_constraint WHERE contype='f' AND confrelid='public.ppl_legal_employers'::regclass ORDER BY conname");
        originalHistory = owner.queryForList("SELECT version,checksum,installed_by,success FROM flyway_schema_history ORDER BY installed_rank");
        var flyway = Flyway.configure().dataSource(ownerSource)
                .locations("filesystem:src/main/resources/db/migration").validateOnMigrate(true).outOfOrder(false).load();
        flyway.migrate();
        // V49 reserves the owner identifier, V50 aligns the independent role-code
        // constraint, and V51 only hardens audit evidence; none may synthesize identity.
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("51");
        assertThat(flyway.info().applied()).hasSize(51);
        assertThat(owner.queryForList("SELECT legal_employer_id,tenant_id,employer_key,legal_name,lifecycle_state,version FROM ppl_legal_employers ORDER BY legal_employer_id")).isEqualTo(originalEmployers);
        assertThat(owner.queryForList("SELECT work_relationship_id,tenant_id,worker_id,legal_employer_id FROM ppl_work_relationships ORDER BY work_relationship_id")).isEqualTo(originalParents);
        assertThat(owner.queryForList("SELECT conname,pg_get_constraintdef(oid) definition FROM pg_constraint WHERE contype='f' AND confrelid='public.ppl_legal_employers'::regclass ORDER BY conname")).isEqualTo(originalParentConstraints);
        assertThat(owner.queryForList("SELECT version,checksum,installed_by,success FROM flyway_schema_history WHERE version::integer<=48 ORDER BY installed_rank")).isEqualTo(originalHistory);
        assertThat(owner.queryForObject("SELECT count(*) FROM ppl_legal_employers WHERE public_id IS NULL", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*)=count(DISTINCT public_id) FROM ppl_legal_employers", Boolean.class)).isTrue();
        try (Connection connection = ownerSource.getConnection()) {
            try (var value = connection.prepareStatement("SELECT set_config('dwp.test_password', ?, false)")) {
                value.setString(1, PASSWORD);
                value.execute();
            }
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        DO $role$ BEGIN
                            EXECUTE format('CREATE ROLE people_identity_read_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L',
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
        grantSelect("ppl_persons", "person_id,tenant_id,public_id,version,lifecycle_state");
        grantSelect("ppl_workers", "worker_id,tenant_id,person_id,public_id,version,worker_status");
        grantSelect("ppl_work_relationships", "work_relationship_id,tenant_id,worker_id,legal_employer_id,public_id,version,start_date,end_date");
        grantSelect("ppl_legal_employers", "legal_employer_id,tenant_id,public_id");
        grantSelect("ppl_assignments", "assignment_id,tenant_id,work_relationship_id,location_id,public_id,assignment_key,version,assignment_status,effective_start_date,effective_end_date,effective_sequence");
        grantSelect("ppl_locations", "location_id,tenant_id,time_zone");
        runtime = source(RUNTIME, PASSWORD);
    }

    @Test
    void forward49KeepsNativeKeysParentsAndAllocatesUniqueNonNullOpaqueEmployerIds() {
        assertThat(originalEmployers).isNotEmpty();
        UUID actual = owner.queryForObject("SELECT public_id FROM ppl_legal_employers WHERE legal_employer_id=?", UUID.class, employerId);
        assertThat(actual).isNotNull().isNotEqualTo(new UUID(0, 0));
        assertThat(owner.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='ppl_legal_employers' AND column_name='public_id'", String.class)).isEqualTo("NO");
        assertThat(owner.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema='public' AND table_name='ppl_legal_employers' AND column_name='public_id'", String.class)).contains("gen_random_uuid()");
        assertOwnerConstraint(() -> owner.update("INSERT INTO ppl_legal_employers(tenant_id,employer_key,legal_name,public_id) VALUES (?,?,?,?)",
                TENANT + 1, person + ":duplicate", "Duplicate UUID must fail globally", actual), "23505");
        assertOwnerConstraint(() -> owner.update("INSERT INTO ppl_legal_employers(tenant_id,employer_key,legal_name,public_id) VALUES (?,?,?,NULL)",
                TENANT, person + ":null-public-id", "Null UUID must fail"), "23502");
        assertThat(owner.queryForObject("SELECT count(*) FROM ppl_legal_employers WHERE public_id=?", Integer.class, actual)).isEqualTo(1);
        long foreignEmployer = owner.queryForObject("INSERT INTO ppl_legal_employers(tenant_id,employer_key,legal_name) VALUES (?,?,?) RETURNING legal_employer_id",
                Long.class, TENANT + 1, person + ":foreign", "Foreign employer");
        long workerId = worker("foreign-parent");
        assertOwnerConstraint(() -> owner.update("INSERT INTO ppl_work_relationships(tenant_id,relationship_key,worker_id,legal_employer_id,relationship_type,start_date) VALUES (?,?,?,?,'EMPLOYEE',DATE '2020-01-01')",
                TENANT, person + ":foreign-parent", workerId, foreignEmployer), "23503");
        assertThat(owner.queryForObject("SELECT count(*) FROM ppl_work_relationships WHERE tenant_id=? AND relationship_key=?", Integer.class, TENANT, person + ":foreign-parent")).isZero();
    }

    @BeforeEach
    void createIndependentOwnedFixtureWithoutDeletingEvidence() {
        person = UUID.randomUUID();
        personId = owner.queryForObject("INSERT INTO ppl_persons(tenant_id,public_id,person_key,display_name,time_zone) VALUES (?,?,?,?,?) RETURNING person_id",
                Long.class, TENANT, person, "native-" + person, "Native Person", "Pacific/Auckland");
        employerId = owner.queryForObject("INSERT INTO ppl_legal_employers(tenant_id,employer_key,legal_name) VALUES (?,?,?) RETURNING legal_employer_id",
                Long.class, TENANT, "employer-" + person, "Native employer");
        reader = new NativeSelfContextQueryReaderV1(runtime);
    }

    @Test
    void allNativeWorkersRelationshipsAndAssignmentsAreReturnedNotPrimaryLimitOne() {
        var first = employment("one", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        var second = employment("two", "America/New_York", LocalDate.of(2020, 1, 1), null);
        AtomicReference<NativeSelfContextSetV1> captured = new AtomicReference<>();
        var resolution = resolve(capturing(captured), NOW, null, person, TENANT);
        assertThat(resolution.kind()).isEqualTo(SelfContextResolutionV1.Kind.SELECTION_REQUIRED);
        assertThat(resolution.candidates()).containsExactlyInAnyOrder(first.selector(), second.selector());
        assertThat(captured.get().contexts()).hasSize(2);
        assertThat(captured.get().complete()).isTrue();
        var selected = resolve(reader, NOW, second.selector(), person, TENANT).selected();
        assertThat(selected.context().worker().personPublicId()).isEqualTo(person);
        assertThat(selected.context().relationship().workerPublicId()).isEqualTo(second.workerPublicId());
        assertThat(selected.context().assignment().workRelationshipPublicId()).isEqualTo(second.relationshipPublicId());
        assertThat(selected.context().relationship().legalEmployerPublicId()).isEqualTo(owner.queryForObject(
                "SELECT public_id FROM ppl_legal_employers WHERE tenant_id=? AND legal_employer_id=?", UUID.class, TENANT, employerId));
        assertThat(selected.personVersion()).isZero();
        assertThat(selected.context().worker().version()).isZero();
        assertThat(selected.context().relationship().version()).isZero();
        assertThat(selected.context().assignment().version()).isZero();
        assertThat(owner.queryForObject("SELECT to_regclass('public.com_users') IS NULL", Boolean.class)).isTrue();
    }

    @Test
    void nativeInclusiveDatesUseExplicitEastWorkZoneNotPersonDisplayOrUtc() {
        LocalDate date = LocalDate.of(2026, 9, 14);
        employment("east-boundary", "Asia/Seoul", date, date);
        var boundary = Instant.parse("2026-09-13T15:00:00Z");
        assertThat(resolve(reader, boundary, null, person, TENANT).kind()).isEqualTo(SelfContextResolutionV1.Kind.SELECTED);
        reject(() -> resolve(reader, boundary.minusNanos(1), null, person, TENANT), SELF_SCOPE_UNRESOLVED);
    }

    @Test
    void nativeInclusiveLastDayInWestZoneEndsOnlyAtNextLocalMidnight() {
        LocalDate date = LocalDate.of(2026, 9, 12);
        employment("west-boundary", "America/New_York", date, date);
        Instant nextDay = Instant.parse("2026-09-13T04:00:00Z");
        assertThat(resolve(reader, nextDay.minusSeconds(1), null, person, TENANT).kind()).isEqualTo(SelfContextResolutionV1.Kind.SELECTED);
        reject(() -> resolve(reader, nextDay, null, person, TENANT), SELF_SCOPE_UNRESOLVED);
    }

    @Test
    void sameDayNativeMaximumSequenceWinsRatherThanPublicUuidSortOrPrimaryFlag() {
        var original = employment("correction", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        var correction = assignment(original.relationshipId(), original.locationId(), original.logicalKey(), LocalDate.of(2020, 1, 1), null, 2);
        var selected = resolve(reader, NOW, null, person, TENANT).selected().context().assignment();
        assertThat(selected.publicId()).isEqualTo(correction.publicId());
        assertThat(selected.publicId()).isNotEqualTo(original.assignmentPublicId());
    }

    @Test
    void correctionThatClosesSameDaySliceCannotFallbackToSupersededOpenSlice() {
        var original = employment("closed-correction", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        assignment(original.relationshipId(), original.locationId(), original.logicalKey(), LocalDate.of(2020, 1, 1), LocalDate.of(2026, 9, 10), 2);
        reject(() -> resolve(reader, NOW, null, person, TENANT), SELF_SCOPE_UNRESOLVED);
    }

    @Test
    void overlappingDifferentDaySlicesWithSameNativeKeyDenyEntireSet() {
        var original = employment("overlap", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        assignment(original.relationshipId(), original.locationId(), original.logicalKey(), LocalDate.of(2026, 9, 1), null, 1);
        reject(() -> resolve(reader, NOW, null, person, TENANT), OWNER_RESPONSE_INVALID);
    }

    @Test
    void freshNativeReadsObserveNewSecondAssignmentInsteadOfCachedSingleSelfScope() {
        var first = employment("fresh-one", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        assertThat(resolve(reader, NOW, null, person, TENANT).kind()).isEqualTo(SelfContextResolutionV1.Kind.SELECTED);
        employment("fresh-two", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        assertThat(resolve(reader, NOW, null, person, TENANT).kind()).isEqualTo(SelfContextResolutionV1.Kind.SELECTION_REQUIRED);
        assertThat(resolve(reader, NOW, first.selector(), person, TENANT).selected().context().assignment().publicId()).isEqualTo(first.assignmentPublicId());
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(booleans = {true, false})
    void missingLocationOrNullNativeZoneHasNoPersonProfileZoneFallback(boolean missingLocation) {
        var context = employment("no-zone", null, LocalDate.of(2020, 1, 1), null);
        if (missingLocation) owner.update("UPDATE ppl_assignments SET location_id=NULL WHERE tenant_id=? AND assignment_id=?", TENANT, context.assignmentId());
        reject(() -> resolve(reader, NOW, null, person, TENANT), OWNER_RESPONSE_INVALID);
    }

    @Test
    void invalidNativeBusinessZoneSqlErrorIsRedactedWithoutFallback() {
        employment("invalid-zone", "secret-invalid-native-zone", LocalDate.of(2020, 1, 1), null);
        assertThat(catchThrowable(() -> resolve(reader, NOW, null, person, TENANT)))
                .isInstanceOfSatisfying(SelfContextContractExceptionV1.class, error -> assertThat(error.code()).isEqualTo(OWNER_UNAVAILABLE))
                .hasMessage("Self-context contract rejected: OWNER_UNAVAILABLE").hasNoCause();
    }

    @Test
    void missingForeignOrMergedPersonNeverCreatesSyntheticContext() {
        reject(() -> resolve(reader, NOW, null, UUID.randomUUID(), TENANT), SELF_SCOPE_UNRESOLVED);
        reject(() -> resolve(reader, NOW, null, person, TENANT + 1), SELF_SCOPE_UNRESOLVED);
        employment("merged", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        owner.update("UPDATE ppl_persons SET lifecycle_state='MERGED' WHERE person_id=?", personId);
        reject(() -> resolve(reader, NOW, null, person, TENANT), OWNER_RESPONSE_INVALID);
    }

    @Test
    void nativePersonWithoutWorkerAndWorkerWithoutAssignmentAreExplicitUnresolvedScopes() {
        reject(() -> resolve(reader, NOW, null, person, TENANT), SELF_SCOPE_UNRESOLVED);
        worker("without-assignment");
        reject(() -> resolve(reader, NOW, null, person, TENANT), SELF_SCOPE_UNRESOLVED);
    }

    @Test
    void nativePersonNegativeVersionIsNotTrusted() {
        employment("person-version", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        owner.update("UPDATE ppl_persons SET version=-1 WHERE tenant_id=? AND person_id=?", TENANT, personId);
        reject(() -> resolve(reader, NOW, null, person, TENANT), OWNER_RESPONSE_INVALID);
    }

    @Test
    void nativeSelectPrivilegeLossReturnsOnlyGenericTypedError() {
        employment("privilege-loss", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        owner.execute("REVOKE SELECT (worker_status) ON public.ppl_workers FROM " + RUNTIME);
        try {
            assertThat(catchThrowable(() -> resolve(reader, NOW, null, person, TENANT)))
                    .isInstanceOfSatisfying(SelfContextContractExceptionV1.class, error -> assertThat(error.code()).isEqualTo(OWNER_UNAVAILABLE))
                    .hasMessage("Self-context contract rejected: OWNER_UNAVAILABLE").hasNoCause();
        } finally {
            owner.execute("GRANT SELECT (worker_status) ON public.ppl_workers TO " + RUNTIME);
        }
    }

    @Test
    void completeSetAt100IsUsableBut101IsDeniedNotSilentlyTruncated() {
        var base = employment("limit-0", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        for (int index = 1; index < 100; index++) {
            assignment(base.relationshipId(), base.locationId(), person + ":limit:" + index, LocalDate.of(2020, 1, 1), null, 1);
        }
        assertThat(resolve(reader, NOW, null, person, TENANT).candidates()).hasSize(100);
        assignment(base.relationshipId(), base.locationId(), person + ":limit:100", LocalDate.of(2020, 1, 1), null, 1);
        AtomicReference<NativeSelfContextSetV1> captured = new AtomicReference<>();
        reject(() -> resolve(capturing(captured), NOW, base.selector(), person, TENANT), OWNER_RESPONSE_INVALID);
        assertThat(captured.get().complete()).isFalse();
        assertThat(captured.get().contexts()).hasSize(101);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"ppl_workers", "ppl_work_relationships", "ppl_assignments", "ppl_legal_employers"})
    void nativeZeroUuidOnEachEmploymentParentCannotMintAuthority(String table) {
        var context = employment("zero-" + table, "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        UUID id = switch (table) {
            case "ppl_workers" -> context.workerPublicId();
            case "ppl_work_relationships" -> context.relationshipPublicId();
            case "ppl_assignments" -> context.assignmentPublicId();
            default -> owner.queryForObject("SELECT public_id FROM ppl_legal_employers WHERE legal_employer_id=?", UUID.class, employerId);
        };
        owner.update("UPDATE " + table + " SET public_id=? WHERE tenant_id=? AND public_id=?", new UUID(0, 0), TENANT, id);
        try {
            reject(() -> resolve(reader, NOW, null, person, TENANT), OWNER_RESPONSE_INVALID);
        } finally {
            owner.update("UPDATE " + table + " SET public_id=? WHERE tenant_id=? AND public_id=?", id, TENANT, new UUID(0, 0));
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = {"ppl_workers", "ppl_work_relationships", "ppl_assignments"})
    void nativeNegativeVersionOfEachEmploymentParentIsRejected(String table) {
        var context = employment("negative-" + table, "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        UUID id = switch (table) {
            case "ppl_workers" -> context.workerPublicId();
            case "ppl_work_relationships" -> context.relationshipPublicId();
            default -> context.assignmentPublicId();
        };
        owner.update("UPDATE " + table + " SET version=-1 WHERE tenant_id=? AND public_id=?", TENANT, id);
        reject(() -> resolve(reader, NOW, null, person, TENANT), OWNER_RESPONSE_INVALID);
    }

    @Test
    void runtimeOnlySelectCannotWriteNativeRowsDdlTempHistoryOrBecomeOwner() throws SQLException {
        employment("acl", "Asia/Seoul", LocalDate.of(2020, 1, 1), null);
        resolve(reader, NOW, null, person, TENANT);
        JdbcTemplate query = new JdbcTemplate(runtime);
        assertThat(query.queryForObject("SELECT current_user=session_user AND current_user=?", Boolean.class, RUNTIME)).isTrue();
        assertThat(query.queryForObject("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        assertThat(query.queryForObject("SELECT has_column_privilege(current_user,'public.ppl_persons','time_zone','SELECT')", Boolean.class)).isFalse();
        assertDenied("UPDATE public.ppl_persons SET lifecycle_state='MERGED' WHERE person_id=" + personId);
        assertDenied("DELETE FROM public.ppl_persons WHERE person_id=" + personId);
        assertDenied("INSERT INTO public.ppl_persons(tenant_id,person_key,display_name) VALUES (" + TENANT + ",'forbidden','forbidden')");
        assertDenied("CREATE TABLE public.identity_runtime_sentinel(id BIGINT)");
        assertDenied("CREATE TEMPORARY TABLE identity_runtime_sentinel(id BIGINT)");
        assertDenied("SELECT * FROM public.flyway_schema_history");
        assertDenied("SET ROLE " + POSTGRES.getUsername());
    }

    private SelfContextOwnerPortsV1.NativeContextProvider capturing(AtomicReference<NativeSelfContextSetV1> target) {
        return request -> { var value = reader.loadComplete(request); target.set(value); return value; };
    }

    private Employment employment(String suffix, String zone, LocalDate start, LocalDate end) {
        long workerId = worker(suffix);
        long relationshipId = owner.queryForObject("INSERT INTO ppl_work_relationships(tenant_id,relationship_key,worker_id,legal_employer_id,relationship_type,start_date,end_date) VALUES (?,?,?,?,'EMPLOYEE',?,?) RETURNING work_relationship_id",
                Long.class, TENANT, person + ":relationship:" + suffix, workerId, employerId, start, end);
        long locationId = owner.queryForObject("INSERT INTO ppl_locations(tenant_id,location_key,name,time_zone) VALUES (?,?,?,?) RETURNING location_id",
                Long.class, TENANT, person + ":location:" + suffix, "Native location", zone);
        String key = person + ":assignment:" + suffix;
        var assignment = assignment(relationshipId, locationId, key, start, end, 1);
        return new Employment(workerId, owner.queryForObject("SELECT public_id FROM ppl_workers WHERE worker_id=?", UUID.class, workerId),
                relationshipId, owner.queryForObject("SELECT public_id FROM ppl_work_relationships WHERE work_relationship_id=?", UUID.class, relationshipId),
                assignment.id(), assignment.publicId(), locationId, key);
    }

    private long worker(String suffix) {
        return owner.queryForObject("INSERT INTO ppl_workers(tenant_id,person_id,worker_number,worker_type) VALUES (?,?,?,'EMPLOYEE') RETURNING worker_id",
                Long.class, TENANT, personId, person + ":worker:" + suffix);
    }

    private AssignmentRow assignment(long relationship, long location, String key, LocalDate start, LocalDate end, int sequence) {
        return owner.queryForObject("INSERT INTO ppl_assignments(tenant_id,assignment_key,work_relationship_id,location_id,effective_start_date,effective_end_date,effective_sequence) VALUES (?,?,?,?,?,?,?) RETURNING assignment_id,public_id",
                (rows, index) -> new AssignmentRow(rows.getLong(1), rows.getObject(2, UUID.class)), TENANT, key, relationship, location, start, end, sequence);
    }

    private static void grantSelect(String table, String columns) {
        owner.execute("GRANT SELECT (" + columns + ") ON public." + table + " TO " + RUNTIME);
    }

    private static void assertOwnerConstraint(Runnable mutation, String sqlState) {
        Throwable error = catchThrowable(mutation::run);
        assertThat(error).isNotNull();
        while (error.getCause() != null) error = error.getCause();
        assertThat(error).isInstanceOfSatisfying(SQLException.class,
                nativeFailure -> assertThat(nativeFailure.getSQLState()).isEqualTo(sqlState));
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

    record AssignmentRow(long id, UUID publicId) { }
    record Employment(long workerId, UUID workerPublicId, long relationshipId, UUID relationshipPublicId,
                      long assignmentId, UUID assignmentPublicId, long locationId, String logicalKey) {
        SelfContextSelectorV1 selector() { return new SelfContextSelectorV1(workerPublicId, relationshipPublicId, assignmentPublicId); }
    }
}
