package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.repository.AppAdminPresetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Fresh migrated database, fixture identities and transaction helpers for preset suites. */
@Testcontainers(disabledWithoutDocker = true)
abstract class AppAdminPresetPostgresFixture {
    static final OffsetDateTime VALID_TO =
            OffsetDateTime.parse("2030-12-01T00:00:00Z");
    static final OffsetDateTime REVIEW_DUE =
            OffsetDateTime.parse("2030-06-01T00:00:00Z");
    static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static PGSimpleDataSource dataSource;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactionManager;
    static TransactionTemplate transactions;
    static ObjectMapper objectMapper;

    IdentityAuditService audit;
    AppAdminPresetService service;
    AppAdminPresetRequestService requestService;
    Fixture fixture;

    @BeforeAll
    static void migrateCleanDatabase() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    @BeforeEach
    void setUp() {
        audit = mock(IdentityAuditService.class);
        doAnswer(invocation -> {
            String attempted = objectMapper.writeValueAsString(invocation.getArgument(7));
            TransactionTemplate independent = new TransactionTemplate(transactionManager);
            independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(ignored -> jdbc.update("""
                    INSERT INTO sys_identity_audit_events (
                        audit_event_id, tenant_id, actor_id, actor_type, action,
                        target_type, target_id, correlation_id, outcome, reason,
                        after_snapshot)
                    VALUES (?, ?, ?, 'USER', ?, ?, ?, ?, 'DENIED', ?, ?)
                    """, UUID.randomUUID(), invocation.getArgument(0),
                    invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4),
                    invocation.getArgument(5), invocation.getArgument(6), attempted));
            return null;
        }).when(audit).denied(
                anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any());
        AppAdminPresetRepository repository =
                new AppAdminPresetRepository(jdbc, objectMapper);
        ScopedAdminDutyAssignmentService duties =
                new ScopedAdminDutyAssignmentService(jdbc);
        AppAdminPresetOutboxPublisher events = publisher();
        requestService = new AppAdminPresetRequestService(
                jdbc, repository, duties, audit, events);
        service = new AppAdminPresetService(
                jdbc, repository, duties, audit, events, requestService,
                java.time.Clock.systemUTC());
        fixture = fixture();
    }

    AppGovernanceDtos.Assignment dashboardAssignment(
            AppGovernanceService service, Long actorId, UUID assignmentId) {
        return service.dashboard(fixture.tenantId(), actorId).assignments().stream()
                .filter(assignment -> assignment.assignmentId().equals(assignmentId))
                .findFirst()
                .orElseThrow();
    }

    AppAdminPresetOutboxPublisher publisher() {
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        DomainEventOutboxRepository outbox = new DomainEventOutboxRepository(
                new NamedParameterJdbcTemplate(dataSource), objectMapper);
        return new AppAdminPresetOutboxPublisher(
                new DomainEventRecorder(outbox, contracts, objectMapper),
                contracts, objectMapper);
    }

    Fixture fixture() {
        int number = SEQUENCE.incrementAndGet();
        Long tenantId = jdbc.queryForObject("""
                INSERT INTO com_tenants (code, name, status)
                VALUES (?, ?, 'ACTIVE') RETURNING tenant_id
                """, Long.class, "preset-test-" + number, "Preset test " + number);
        Long requester = user(tenantId, "requester");
        Long approver = user(tenantId, "approver");
        Long fulfiller = user(tenantId, "fulfiller");
        Long reviewer = user(tenantId, "reviewer");
        Long catalogAdmin = user(tenantId, "catalog-admin");
        Long tenantAdmin = user(tenantId, "tenant-admin");
        Long member = user(tenantId, "member");
        Long subject = user(tenantId, "subject");
        grantRole(tenantId, catalogAdmin, "APP_CATALOG_ADMIN");
        grantTenantAdmin(tenantId, tenantAdmin);
        UUID resourceSet = approvalsResourceSet(tenantId);
        grantResponsibility(
                tenantId, requester, "APP_OWNER", resourceSet, catalogAdmin);
        grantResponsibility(
                tenantId, requester, "APP_ACCESS_APPROVER", resourceSet, catalogAdmin);
        grantResponsibility(
                tenantId, approver, "APP_ACCESS_APPROVER", resourceSet, catalogAdmin);
        grantResponsibility(
                tenantId, fulfiller, "APP_ACCESS_MANAGER", resourceSet, catalogAdmin);
        grantResponsibility(
                tenantId, reviewer, "APP_ACCESS_REVIEWER", resourceSet, catalogAdmin);
        return new Fixture(
                tenantId, requester, approver, fulfiller, reviewer, catalogAdmin,
                tenantAdmin, member, subject, resourceSet);
    }

    Long user(Long tenantId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, ?, ?, 'ACTIVE') RETURNING user_id
                """, Long.class, tenantId, name,
                name + '-' + tenantId + "@preset.test");
    }

    void grantTenantAdmin(Long tenantId, Long userId) {
        grantRole(tenantId, userId, "TENANT_ADMIN");
    }
    void grantRole(Long tenantId, Long userId, String roleCode) {
        Long roleId = jdbc.queryForObject("""
                INSERT INTO com_roles (
                    tenant_id, code, name, description, status, role_type,
                    privileged, assignable_to_groups, builtin_role_code)
                SELECT ?, role_code, display_name, description, 'ACTIVE', 'SYSTEM',
                       privileged, assignable_to_groups, role_code
                  FROM sys_builtin_role_catalog WHERE role_code = ?
                ON CONFLICT (tenant_id, code) DO UPDATE SET status = 'ACTIVE'
                RETURNING role_id
                """, Long.class, tenantId, roleCode);
        AppAdminPresetCatalogAuthorityFixture.materialize(jdbc, tenantId, roleId, roleCode);
        jdbc.update("""
                INSERT INTO com_role_members (tenant_id, role_id, user_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, tenantId, roleId, userId);
    }

    UUID approvalsResourceSet(Long tenantId) {
        jdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (?, 'APP', 'APP.APPROVALS', 'Approvals', TRUE)
                """, tenantId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_resource_sets (
                    resource_set_id, tenant_id, resource_set_key, name,
                    resource_type, lifecycle_state)
                VALUES (?, ?, 'RS_APPROVALS', 'Approvals', 'APP', 'ACTIVE')
                """, id, tenantId);
        jdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    resource_set_member_id, tenant_id, resource_set_id,
                    resource_type, resource_key, lifecycle_state)
                VALUES (?, ?, ?, 'APP', 'APP.APPROVALS', 'ACTIVE')
                """, UUID.randomUUID(), tenantId, id);
        return id;
    }

    UUID additionalApprovalsResourceSet(Long tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_resource_sets (
                    resource_set_id, tenant_id, resource_set_key, name,
                    resource_type, lifecycle_state)
                VALUES (?, ?, ?, 'Approvals secondary', 'APP', 'ACTIVE')
                """, id, tenantId,
                "RS_APPROVALS_" + id.toString().substring(0, 8).toUpperCase());
        jdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    resource_set_member_id, tenant_id, resource_set_id,
                    resource_type, resource_key, lifecycle_state)
                VALUES (?, ?, ?, 'APP', 'APP.APPROVALS', 'ACTIVE')
                """, UUID.randomUUID(), tenantId, id);
        return id;
    }

    UUID productResourceSet(
            Long tenantId, String resourceKey, String setKey, String name) {
        jdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (?, 'APP', ?, ?, TRUE)
                ON CONFLICT (tenant_id, type, key) DO NOTHING
                """, tenantId, resourceKey, name);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_resource_sets (
                    resource_set_id, tenant_id, resource_set_key, name,
                    resource_type, lifecycle_state)
                VALUES (?, ?, ?, ?, 'APP', 'ACTIVE')
                """, id, tenantId, setKey, name);
        jdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    resource_set_member_id, tenant_id, resource_set_id,
                    resource_type, resource_key, lifecycle_state)
                VALUES (?, ?, ?, 'APP', ?, 'ACTIVE')
                """, UUID.randomUUID(), tenantId, id, resourceKey);
        return id;
    }

    UUID grantResponsibility(
            Long tenantId,
            Long userId,
            String responsibility,
            UUID resourceSetId,
            Long approvedBy) {
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_role_assignments (
                    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                    responsibility_code, resource_set_id, assignment_source,
                    lifecycle_state, valid_from, valid_to, review_due_at,
                    justification, approved_by, approved_at,
                    decision_reason, created_by, updated_by)
                VALUES (?, ?, 'USER', ?, ?, ?, 'PROVISIONING', 'ACTIVE',
                        CURRENT_TIMESTAMP, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                        'Controlled test bootstrap responsibility.', ?, ?)
                """, assignmentId, tenantId, userId.toString(), responsibility,
                resourceSetId, VALID_TO, REVIEW_DUE,
                "Controlled test bootstrap for exact scoped governance responsibility.",
                approvedBy, approvedBy, approvedBy);
        return assignmentId;
    }

    Long group(Long tenantId, String key) {
        return jdbc.queryForObject("""
                INSERT INTO com_groups (
                    tenant_id, group_key, display_name, source_type, status)
                VALUES (?, ?, ?, 'LOCAL', 'ACTIVE') RETURNING group_id
                """, Long.class, tenantId,
                key + '-' + UUID.randomUUID(), key);
    }

    void addGroupMember(Long tenantId, Long groupId, Long userId) {
        jdbc.update("""
                INSERT INTO com_group_members (
                    tenant_id, group_id, user_id, source_type)
                VALUES (?, ?, ?, 'LOCAL')
                """, tenantId, groupId, userId);
    }

    void grantGroupResponsibility(
            Long tenantId,
            Long groupId,
            String responsibility,
            UUID resourceSetId,
            Long approvedBy) {
        jdbc.update("""
                INSERT INTO com_admin_role_assignments (
                    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                    responsibility_code, resource_set_id, assignment_source,
                    lifecycle_state, valid_from, valid_to, review_due_at,
                    justification, approved_by, approved_at,
                    decision_reason, created_by, updated_by)
                VALUES (?, ?, 'GROUP', ?, ?, ?, 'PROVISIONING', 'ACTIVE',
                        CURRENT_TIMESTAMP, ?, ?, ?, ?, CURRENT_TIMESTAMP,
                        'Controlled test bootstrap responsibility.', ?, ?)
                """, UUID.randomUUID(), tenantId, groupId.toString(), responsibility,
                resourceSetId, VALID_TO, REVIEW_DUE,
                "Controlled test bootstrap for a group-scoped governance responsibility.",
                approvedBy, approvedBy, approvedBy);
    }

    UUID responsibilityAssignment(
            Long tenantId, Long userId, String responsibility, UUID resourceSetId) {
        return jdbc.queryForObject("""
                SELECT admin_role_assignment_id
                  FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND principal_type = 'USER'
                   AND principal_ref = ? AND responsibility_code = ?
                   AND resource_set_id = ? AND lifecycle_state = 'ACTIVE'
                """, UUID.class, tenantId, userId.toString(),
                responsibility, resourceSetId);
    }

    AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest governedRequest(
            Long subjectId, String presetCode) {
        return new AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest(
                "USER", subjectId.toString(), presetCode, fixture.resourceSetId(),
                VALID_TO, REVIEW_DUE,
                "Assign the minimum product-specific duties for a time-bound need.");
    }

    AppGovernanceDtos.AppAdminPresetDecisionRequest decision(
            String decision, long version) {
        return new AppGovernanceDtos.AppAdminPresetDecisionRequest(
                decision, "Independent governed preset decision with reviewed evidence.", version);
    }

    AppGovernanceDtos.CreateAssignmentRequest genericRequest(
            Long subjectId, String responsibilityCode) {
        return genericRequest(subjectId, responsibilityCode, fixture.resourceSetId());
    }

    AppGovernanceDtos.CreateAssignmentRequest genericRequest(
            Long subjectId, String responsibilityCode, UUID resourceSetId) {
        return new AppGovernanceDtos.CreateAssignmentRequest(
                "USER", subjectId.toString(), responsibilityCode,
                resourceSetId, VALID_TO,
                "Create an independently governed control-plane responsibility.");
    }

    AppGovernanceDtos.CreateAssignmentRequest groupRequest(
            Long groupId, String responsibilityCode, UUID resourceSetId) {
        return new AppGovernanceDtos.CreateAssignmentRequest(
                "GROUP", groupId.toString(), responsibilityCode,
                resourceSetId, VALID_TO,
                "Create an independently governed group control-plane responsibility.");
    }

    AppGovernanceDtos.AssignmentDecisionRequest genericDecision(
            String decision, long version) {
        return new AppGovernanceDtos.AssignmentDecisionRequest(
                decision, "Independent exact-scope control-plane decision evidence.", version);
    }

    AppGovernanceDtos.RevokeAssignmentRequest genericRevoke(long version) {
        return new AppGovernanceDtos.RevokeAssignmentRequest(
                "Exact-scope access manager revoked the legacy responsibility.", version);
    }

    UUID pendingResponsibility(Long subjectId, String responsibility, Long requester) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_role_assignments (
                    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                    responsibility_code, resource_set_id, assignment_source,
                    lifecycle_state, valid_to, review_due_at, justification,
                    created_by, updated_by)
                VALUES (?, ?, 'USER', ?, ?, ?, 'MANUAL', 'PENDING_APPROVAL',
                        ?, ?, ?, ?, ?)
                """, id, fixture.tenantId(), subjectId.toString(), responsibility,
                fixture.resourceSetId(), VALID_TO, REVIEW_DUE,
                "Legacy specialist row retained only to prove decision bypass closure.",
                requester, requester);
        return id;
    }

    AppGovernanceDtos.ActivateAppAdminPresetRequest activation(long version) {
        return new AppGovernanceDtos.ActivateAppAdminPresetRequest(
                "Independent access manager activated the exact approved package.", version);
    }

    void assertBundleStates(UUID aggregateId, String state, int dutyCount) {
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM com_admin_app_preset_assignments
                 WHERE app_preset_assignment_id = ?
                """, String.class, aggregateId)).isEqualTo(state);
        assertThat(jdbc.queryForObject("""
                SELECT responsibility.lifecycle_state
                  FROM com_admin_app_preset_assignments aggregate
                  JOIN com_admin_role_assignments responsibility
                    ON responsibility.admin_role_assignment_id =
                       aggregate.responsibility_assignment_id
                 WHERE aggregate.app_preset_assignment_id = ?
                """, String.class, aggregateId)).isEqualTo(state);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_scoped_duty_assignments
                 WHERE app_preset_assignment_id = ? AND lifecycle_state = ?
                """, Integer.class, aggregateId, state)).isEqualTo(dutyCount);
    }

    List<String> effectiveDuties(Long userId) {
        return jdbc.query("""
                SELECT DISTINCT duty_code FROM auth_effective_scoped_duties
                 WHERE tenant_id = ? AND user_id = ? ORDER BY duty_code
                """, (result, ignored) -> result.getString(1), fixture.tenantId(), userId);
    }

    long accessRevision(Long userId) {
        Long value = jdbc.queryForObject("""
                SELECT access_revision FROM com_users
                 WHERE tenant_id = ? AND user_id = ?
                """, Long.class, fixture.tenantId(), userId);
        return value == null ? -1 : value;
    }

    void assertOutbox(UUID aggregateId, List<String> types, List<Long> sequences) {
        assertThat(jdbc.query("""
                SELECT event_type, aggregate_sequence
                  FROM sys_domain_event_outbox
                 WHERE tenant_id = ? AND aggregate_type = 'APP_ADMIN_PRESET_ASSIGNMENT'
                   AND aggregate_id = ? ORDER BY aggregate_sequence
                """, (result, ignored) -> new Event(
                        result.getString("event_type"),
                        result.getLong("aggregate_sequence")),
                fixture.tenantId(), aggregateId.toString()))
                .extracting(Event::type, Event::sequence)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, types.size())
                        .mapToObj(index -> org.assertj.core.groups.Tuple.tuple(
                                types.get(index), sequences.get(index))).toList());
    }

    int aggregateCount(Long subjectId, String presetCode) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_app_preset_assignments
                 WHERE tenant_id = ? AND principal_type = 'USER'
                   AND principal_ref = ? AND preset_code = ?
                """, Integer.class, fixture.tenantId(), subjectId.toString(), presetCode);
    }

    int openResponsibilityCount(Long subjectId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND principal_type = 'USER' AND principal_ref = ?
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                """, Integer.class, fixture.tenantId(), subjectId.toString());
    }

    int openDutyCount(Long subjectId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_scoped_duty_assignments
                 WHERE tenant_id = ? AND principal_type = 'USER' AND principal_ref = ?
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                """, Integer.class, fixture.tenantId(), subjectId.toString());
    }

    UUID review(
            Long userId, String dutyCode, String reasonCode, UUID resourceSetId) {
        return jdbc.queryForObject("""
                INSERT INTO com_admin_scoped_duty_reviews (
                    tenant_id, user_id, source_role_code, duty_code,
                    reason_code, evidence)
                VALUES (?, ?, 'AUDITOR', ?, ?,
                        jsonb_build_object('resourceSetId', CAST(? AS text)))
                RETURNING scoped_duty_review_id
                """, UUID.class, fixture.tenantId(), userId, dutyCode,
                reasonCode, resourceSetId);
    }

    <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactions.execute(ignored -> work.get());
    }

    DecisionAttempt decideConcurrently(
            AppGovernanceService generic,
            Long actorId,
            AppGovernanceDtos.Assignment pending,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent decision gate did not open.");
            }
            AppGovernanceDtos.Assignment active = inTransaction(() -> {
                jdbc.queryForObject(
                        "SELECT set_config('application_name', ?, true)", String.class,
                        "core006-first-approver-" + actorId);
                return generic.decideAssignment(
                            fixture.tenantId(), actorId,
                            "concurrent-first-approver-" + actorId,
                            pending.assignmentId(),
                            genericDecision("APPROVED", pending.version()));
            });
            return new DecisionAttempt(active, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent decision was interrupted.", exception);
        } catch (BaseException exception) {
            return new DecisionAttempt(null, exception.getErrorCode());
        }
    }

    AssignmentRequestAttempt requestConcurrently(
            AppGovernanceService generic,
            AppGovernanceDtos.CreateAssignmentRequest request,
            String correlationId,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent request gate did not open.");
            }
            AppGovernanceDtos.Assignment assignment = inTransaction(() ->
                    generic.requestAssignment(
                            fixture.tenantId(), fixture.catalogAdmin(),
                            correlationId, request));
            return new AssignmentRequestAttempt(assignment, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent request was interrupted.", exception);
        } catch (BaseException exception) {
            return new AssignmentRequestAttempt(null, exception.getErrorCode());
        }
    }

    AssignmentRequestAttempt requestAsActorConcurrently(
            AppGovernanceService generic,
            Long actorId,
            AppGovernanceDtos.CreateAssignmentRequest request,
            String correlationId,
            String applicationName) {
        try {
            AppGovernanceDtos.Assignment assignment = inTransaction(() -> {
                jdbc.queryForObject(
                        "SELECT set_config('application_name', ?, true)",
                        String.class,
                        applicationName);
                return generic.requestAssignment(
                        fixture.tenantId(), actorId, correlationId, request);
            });
            return new AssignmentRequestAttempt(assignment, null);
        } catch (BaseException exception) {
            return new AssignmentRequestAttempt(null, exception.getErrorCode());
        }
    }

    void lockResourceSetBoundary(
            Connection connection, Long tenantId, UUID resourceSetId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT resource_set_id FROM com_admin_resource_sets
                 WHERE tenant_id = ? AND resource_set_id = ?
                 FOR UPDATE
                """)) {
            statement.setLong(1, tenantId);
            statement.setObject(2, resourceSetId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    void lockAssignmentAuthorityBoundary(Connection connection, Long tenantId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, "dwp-app-responsibility-sod:" + tenantId);
            statement.executeQuery();
        }
    }

    int awaitBlockedFirstApproverDecisions() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int blocked = 0;
        do {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE application_name LIKE 'core006-first-approver-%'
                       AND state = 'active'
                       AND wait_event_type = 'Lock'
                    """, Integer.class);
            blocked = count == null ? 0 : count;
            if (blocked == 2) return blocked;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return blocked;
    }

    int awaitBlockedRequest(String applicationName) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int blocked = 0;
        do {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE application_name = ?
                       AND state = 'active'
                       AND wait_event_type = 'Lock'
                    """, Integer.class, applicationName);
            blocked = count == null ? 0 : count;
            if (blocked == 1) return blocked;
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        return blocked;
    }

    record Fixture(
            Long tenantId,
            Long requester,
            Long approver,
            Long fulfiller,
            Long reviewer,
            Long catalogAdmin,
            Long tenantAdmin,
            Long member,
            Long subject,
            UUID resourceSetId) {
    }

    record Event(String type, long sequence) {
    }

    record DenialEvent(
            UUID auditEventId,
            String action,
            String outcome,
            String correlationId,
            String reason,
            String afterSnapshot) {
    }

    record DecisionAttempt(
            AppGovernanceDtos.Assignment assignment,
            ErrorCode errorCode) {

        boolean active() {
            return assignment != null && "ACTIVE".equals(assignment.lifecycleState());
        }
    }

    record AssignmentRequestAttempt(
            AppGovernanceDtos.Assignment assignment,
            ErrorCode errorCode) {

        boolean created() {
            return assignment != null;
        }
    }
}
