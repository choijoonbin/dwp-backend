package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ApprovalRecoveryAuditorDtos;
import com.dwp.services.auth.repository.ApprovalRecoveryAuditorRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Clean PostgreSQL proof for scoped-duty lifecycle, SoD, and recovery authority. */
@Testcontainers(disabledWithoutDocker = true)
class ScopedAdminDutyPostgresIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime VALID_FROM = OffsetDateTime.parse(
            "2026-08-23T00:00:00Z");
    private static final OffsetDateTime VALID_TO = OffsetDateTime.parse(
            "2027-02-01T00:00:00Z");
    private static final OffsetDateTime REVIEW_DUE = OffsetDateTime.parse(
            "2027-01-01T00:00:00Z");
    private static final AtomicInteger FIXTURE_SEQUENCE = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private ScopedAdminDutyAssignmentService assignments;
    private Fixture fixture;

    @BeforeAll
    static void migrateCleanDatabase() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @BeforeEach
    void setUp() {
        assignments = new ScopedAdminDutyAssignmentService(jdbc, CLOCK);
        fixture = fixture();
    }

    @Test
    void cleanMigrationKeepsLegacyConflictsAndQuarantinesUnscopedAuditors() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM sys_role_conflict_policies
                 WHERE lifecycle_state = 'ACTIVE'
                   AND reason_code IN (
                       'APPROVAL_AUDIT_INDEPENDENCE',
                       'APPROVAL_POLICY_OPERATION_SEPARATION')
                """, Integer.class)).isEqualTo(4);

        Integer legacyAuditors = jdbc.queryForObject("""
                SELECT count(DISTINCT member.user_id)
                  FROM com_role_members member
                  JOIN com_roles role
                    ON role.tenant_id = member.tenant_id
                   AND role.role_id = member.role_id
                   AND role.status = 'ACTIVE'
                 WHERE role.code = 'AUDITOR'
                """, Integer.class);
        assertThat(legacyAuditors).isPositive();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM com_admin_scoped_duty_assignments
                 WHERE duty_code = 'APPROVAL_OPERATIONS_AUDIT'
                   AND assignment_source = 'MIGRATION'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT review.user_id)
                  FROM com_admin_scoped_duty_reviews review
                 WHERE review.source_role_code = 'AUDITOR'
                   AND review.duty_code = 'APPROVAL_OPERATIONS_AUDIT'
                   AND review.reason_code = 'EXPLICIT_AUDIT_SCOPE_REQUIRED'
                   AND review.lifecycle_state = 'OPEN'
                """, Integer.class)).isEqualTo(legacyAuditors);
    }

    @Test
    void bindsDirectAndGroupEvidenceToTheSameEffectiveUserAndSet() {
        Long dutyGroup = group(fixture.tenantId(), "duty-group");
        member(fixture.tenantId(), dutyGroup, fixture.subjectOne());
        UUID directResponsibility = responsibility(
                fixture.tenantId(), "USER", fixture.subjectOne().toString(),
                fixture.approvalsSet(), fixture.approver());

        var grouped = request(new ScopedAdminDutyAssignmentService.Request(
                fixture.tenantId(), "GROUP", dutyGroup.toString(),
                "APPROVAL_DESIGN_DRAFT", fixture.approvalsSet(), directResponsibility,
                "GROUP", VALID_FROM, VALID_TO, REVIEW_DUE,
                "Group duty intersected with direct responsibility.", fixture.requester()));
        approve(grouped.assignmentId(), grouped.version());

        Long responsibilityGroup = group(fixture.tenantId(), "responsibility-group");
        member(fixture.tenantId(), responsibilityGroup, fixture.subjectTwo());
        UUID groupResponsibility = responsibility(
                fixture.tenantId(), "GROUP", responsibilityGroup.toString(),
                fixture.approvalsSet(), fixture.approver());
        var direct = request(new ScopedAdminDutyAssignmentService.Request(
                fixture.tenantId(), "USER", fixture.subjectTwo().toString(),
                "APPROVAL_SIGNATURE_READ", fixture.approvalsSet(), groupResponsibility,
                "MANUAL", VALID_FROM, VALID_TO, REVIEW_DUE,
                "Direct duty intersected with group responsibility.", fixture.requester()));
        approve(direct.assignmentId(), direct.version());

        ScopedAdminDutyEvidenceService evidence = new ScopedAdminDutyEvidenceService(jdbc);
        assertThat(evidence.effectiveDuties(fixture.tenantId(), fixture.subjectOne()))
                .extracting(ScopedAdminDutyEvidenceService.EffectiveDuty::dutyCode)
                .containsExactly("APPROVAL_DESIGN_DRAFT");
        assertThat(evidence.effectiveDuties(fixture.tenantId(), fixture.subjectTwo()))
                .extracting(ScopedAdminDutyEvidenceService.EffectiveDuty::dutyCode)
                .containsExactly("APPROVAL_SIGNATURE_READ");
        assertThat(evidence.resourceRoles(fixture.tenantId(), fixture.subjectOne()))
                .allMatch(role -> role.resourceSetKey().equals("RS_APPROVALS")
                        && role.responsibilityCode().startsWith("SCOPED_")
                        && role.resourceKey().equals("ADMIN.APPROVAL_DESIGN"));
    }

    @Test
    void rejectsSameAndPartialOverlapButAllowsDisjointChildScopes() {
        UUID setA = scopedSet(fixture.tenantId(), "RS_SCOPE_A", "ADMIN.SCOPE_A");
        UUID setB = scopedSet(fixture.tenantId(), "RS_SCOPE_B", "ADMIN.SCOPE_B");
        UUID setPartial = scopedSet(
                fixture.tenantId(), "RS_SCOPE_PARTIAL", "ADMIN.SCOPE_A");

        request(rawDuty("APPROVAL_DESIGN_DRAFT", setA));
        assertSod(() -> request(rawDuty("APPROVAL_DESIGN_PUBLISH", setA)));

        request(rawDuty("APPROVAL_DESIGN_PUBLISH", setB));
        assertThat(openAssignments(setA, setB)).isEqualTo(2);

        assertSod(() -> request(rawDuty("APPROVAL_DESIGN_PUBLISH", setPartial)));
    }

    @Test
    void disjointCustomSetsMaterializeIndependentExactAuthorities() {
        UUID setA = scopedSet(fixture.tenantId(), "RS_DESIGN_A", "ADMIN.WORKFLOW_A");
        UUID setB = scopedSet(fixture.tenantId(), "RS_DESIGN_B", "ADMIN.WORKFLOW_B");
        UUID configA = responsibility(
                fixture.tenantId(), "USER", fixture.subjectOne().toString(),
                setA, fixture.approver());
        UUID configB = responsibility(
                fixture.tenantId(), "USER", fixture.subjectOne().toString(),
                setB, fixture.approver());

        var draft = request(new ScopedAdminDutyAssignmentService.Request(
                fixture.tenantId(), "USER", fixture.subjectOne().toString(),
                "APPROVAL_DESIGN_DRAFT", setA, configA, "MANUAL",
                VALID_FROM, VALID_TO, REVIEW_DUE,
                "Disjoint draft scope exact authority evidence.", fixture.requester()));
        var publish = request(new ScopedAdminDutyAssignmentService.Request(
                fixture.tenantId(), "USER", fixture.subjectOne().toString(),
                "APPROVAL_DESIGN_PUBLISH", setB, configB, "MANUAL",
                VALID_FROM, VALID_TO, REVIEW_DUE,
                "Disjoint publish scope exact authority evidence.", fixture.requester()));
        approve(draft.assignmentId(), draft.version());
        approve(publish.assignmentId(), publish.version());

        ScopedAdminDutyEvidenceService evidence = new ScopedAdminDutyEvidenceService(jdbc);
        assertThat(evidence.effectiveDuties(fixture.tenantId(), fixture.subjectOne()))
                .extracting(
                        ScopedAdminDutyEvidenceService.EffectiveDuty::dutyCode,
                        ScopedAdminDutyEvidenceService.EffectiveDuty::resourceSetKey)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                "APPROVAL_DESIGN_DRAFT", "RS_DESIGN_A"),
                        org.assertj.core.groups.Tuple.tuple(
                                "APPROVAL_DESIGN_PUBLISH", "RS_DESIGN_B"));
        assertThat(evidence.resourceRoles(fixture.tenantId(), fixture.subjectOne()))
                .extracting(com.dwp.services.auth.dto.AppGovernanceDtos.ResourceRole::resourceSetKey)
                .contains("RS_DESIGN_A", "RS_DESIGN_B");
        assertThat(evidence.capabilityPermissions(fixture.tenantId(), fixture.subjectOne()))
                .extracting(
                        com.dwp.services.auth.dto.PermissionDTO::getResourceKey,
                        com.dwp.services.auth.dto.PermissionDTO::getPermissionCode)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "ADMIN.APPROVAL_DESIGN", "UPDATE"),
                        org.assertj.core.groups.Tuple.tuple(
                                "ADMIN.APPROVAL_DESIGN", "PUBLISH"));
    }

    @Test
    void membershipMutationsCannotCreateAHiddenGroupConflict() {
        Long groupId = group(fixture.tenantId(), "latent-conflict-group");
        request(new ScopedAdminDutyAssignmentService.Request(
                fixture.tenantId(), "GROUP", groupId.toString(),
                "APPROVAL_DESIGN_DRAFT", fixture.approvalsSet(), null,
                "GROUP", VALID_FROM, VALID_TO, REVIEW_DUE,
                "Latent group duty for membership conflict test.", fixture.requester()));
        request(rawDuty("APPROVAL_DESIGN_PUBLISH", fixture.approvalsSet()));

        assertSod(() -> inTransaction(() -> {
            member(fixture.tenantId(), groupId, fixture.subjectOne());
            return null;
        }));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_group_members
                 WHERE tenant_id = ? AND group_id = ? AND user_id = ?
                """, Integer.class, fixture.tenantId(), groupId, fixture.subjectOne()))
                .isZero();
    }

    @Test
    void concurrentActivationIsOneWinnerAndVersionedLoser() throws Exception {
        UUID config = responsibility(
                fixture.tenantId(), "USER", fixture.subjectOne().toString(),
                fixture.approvalsSet(), fixture.approver());
        var pending = request(new ScopedAdminDutyAssignmentService.Request(
                fixture.tenantId(), "USER", fixture.subjectOne().toString(),
                "APPROVAL_OPERATIONS_EXECUTE", fixture.approvalsSet(), config,
                "MANUAL", VALID_FROM, VALID_TO, REVIEW_DUE,
                "Concurrent activation must have exactly one winner.", fixture.requester()));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> activate(start, pending.assignmentId()));
            Future<Object> second = executor.submit(() -> activate(start, pending.assignmentId()));
            start.countDown();
            List<Object> results = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));

            assertThat(results.stream()
                    .filter(ScopedAdminDutyAssignmentService.Assignment.class::isInstance))
                    .hasSize(1);
            assertThat(results.stream().filter(BaseException.class::isInstance)
                    .map(BaseException.class::cast)
                    .map(BaseException::getErrorCode))
                    .containsExactly(ErrorCode.OBJECT_VERSION_CONFLICT);
        }
        assertThat(assignments.find(fixture.tenantId(), pending.assignmentId()).version())
                .isEqualTo(1);
    }

    @Test
    void expiryAndRevokeImmediatelyRemoveAuthorityAndRecoveryUsesScopedAuditAlone() {
        var audit = request(new ScopedAdminDutyAssignmentService.Request(
                fixture.tenantId(), "USER", fixture.subjectOne().toString(),
                "APPROVAL_OPERATIONS_AUDIT", fixture.approvalsSet(), null,
                "MANUAL", VALID_FROM, VALID_TO, REVIEW_DUE,
                "Explicit recovery audit scope without global role.", fixture.requester()));
        var activeAudit = approve(audit.assignmentId(), audit.version());

        ScopedAdminDutyEvidenceService evidence = new ScopedAdminDutyEvidenceService(jdbc);
        ApprovalRecoveryAuditorService recovery = new ApprovalRecoveryAuditorService(
                new ApprovalRecoveryAuditorRepository(
                        new NamedParameterJdbcTemplate(dataSource)), evidence);
        ApprovalRecoveryAuditorDtos.ResolveResponse selected = recovery.resolve(
                new ApprovalRecoveryAuditorDtos.ResolveRequest(
                        fixture.tenantId(), "outbox-scoped-audit", fixture.subjectTwo(),
                        "RS_APPROVALS"));
        assertThat(selected.selectedUserId()).isEqualTo(fixture.subjectOne());
        assertThat(selected.assignmentRevision()).startsWith("recovery-v2-");

        revoke(activeAudit.assignmentId(), activeAudit.version());
        assertThat(evidence.effectiveDuties(fixture.tenantId(), fixture.subjectOne())).isEmpty();
        assertThatThrownBy(() -> recovery.resolve(new ApprovalRecoveryAuditorDtos.ResolveRequest(
                fixture.tenantId(), "outbox-after-revoke", fixture.subjectTwo(),
                "RS_APPROVALS")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        assertThatThrownBy(() -> request(new ScopedAdminDutyAssignmentService.Request(
                fixture.tenantId(), "USER", fixture.subjectOne().toString(),
                "APPROVAL_OPERATIONS_AUDIT", fixture.approvalsSet(), null,
                "MANUAL", OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-02-01T00:00:00Z"), REVIEW_DUE,
                "Expired evidence can never become effective authority.", fixture.requester())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    private Object activate(CountDownLatch start, UUID assignmentId) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
            return inTransaction(() -> assignments.approve(
                    fixture.tenantId(), assignmentId, fixture.approver(), 0,
                    "Independent concurrent approval decision."));
        } catch (BaseException exception) {
            return exception;
        } catch (Exception exception) {
            return exception;
        }
    }

    private ScopedAdminDutyAssignmentService.Request rawDuty(String dutyCode, UUID setId) {
        return new ScopedAdminDutyAssignmentService.Request(
                fixture.tenantId(), "USER", fixture.subjectOne().toString(), dutyCode,
                setId, null, "MANUAL", VALID_FROM, VALID_TO, REVIEW_DUE,
                "Raw pending assignment for database SoD proof.", fixture.requester());
    }

    private ScopedAdminDutyAssignmentService.Assignment request(
            ScopedAdminDutyAssignmentService.Request command) {
        return inTransaction(() -> assignments.request(command));
    }

    private ScopedAdminDutyAssignmentService.Assignment approve(UUID id, long version) {
        return inTransaction(() -> assignments.approve(
                fixture.tenantId(), id, fixture.approver(), version,
                "Independent scoped duty approval decision."));
    }

    private ScopedAdminDutyAssignmentService.Assignment revoke(UUID id, long version) {
        return inTransaction(() -> assignments.revoke(
                fixture.tenantId(), id, fixture.approver(), version,
                "Scoped duty no longer requires operational access."));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactions.execute(ignored -> work.get());
    }

    private void assertSod(org.assertj.core.api.ThrowableAssert.ThrowingCallable mutation) {
        assertThatThrownBy(mutation)
                .rootCause()
                .hasMessageContaining("Scoped duty separation-of-duties conflict")
                .hasMessageContaining("policy=SOD-APR-");
    }

    private int openAssignments(UUID left, UUID right) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM com_admin_scoped_duty_assignments
                 WHERE tenant_id = ? AND resource_set_id IN (?, ?)
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'ACTIVE')
                """, Integer.class, fixture.tenantId(), left, right);
    }

    private Fixture fixture() {
        int sequence = FIXTURE_SEQUENCE.incrementAndGet();
        Long tenantId = jdbc.queryForObject("""
                INSERT INTO com_tenants (code, name, status)
                VALUES (?, ?, 'ACTIVE') RETURNING tenant_id
                """, Long.class, "scoped-duty-test-" + sequence,
                "Scoped duty test " + sequence);
        Long requester = user(tenantId, "requester");
        Long approver = user(tenantId, "approver");
        Long subjectOne = user(tenantId, "subject-one");
        Long subjectTwo = user(tenantId, "subject-two");
        UUID approvalsSet = scopedSet(tenantId, "RS_APPROVALS", null);
        return new Fixture(
                tenantId, requester, approver, subjectOne, subjectTwo, approvalsSet);
    }

    private Long user(Long tenantId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, ?, ?, 'ACTIVE') RETURNING user_id
                """, Long.class, tenantId, name,
                name + '-' + tenantId + "@scoped-duty.test");
    }

    private Long group(Long tenantId, String key) {
        return jdbc.queryForObject("""
                INSERT INTO com_groups (
                    tenant_id, group_key, display_name, source_type, status)
                VALUES (?, ?, ?, 'LOCAL', 'ACTIVE') RETURNING group_id
                """, Long.class, tenantId, key, key);
    }

    private void member(Long tenantId, Long groupId, Long userId) {
        jdbc.update("""
                INSERT INTO com_group_members (
                    tenant_id, group_id, user_id, source_type)
                VALUES (?, ?, ?, 'LOCAL')
                """, tenantId, groupId, userId);
    }

    private UUID scopedSet(Long tenantId, String setKey, String childKey) {
        resource(tenantId, "APP", "APP.APPROVALS");
        if (childKey != null) resource(tenantId, "ADMIN", childKey);
        UUID setId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_resource_sets (
                    resource_set_id, tenant_id, resource_set_key, name,
                    resource_type, lifecycle_state)
                VALUES (?, ?, ?, ?, 'APP', 'ACTIVE')
                """, setId, tenantId, setKey, setKey);
        setMember(tenantId, setId, "APP", "APP.APPROVALS");
        if (childKey != null) setMember(tenantId, setId, "ADMIN", childKey);
        return setId;
    }

    private void resource(Long tenantId, String type, String key) {
        jdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (?, ?, ?, ?, TRUE)
                ON CONFLICT (tenant_id, type, key) DO NOTHING
                """, tenantId, type, key, key);
    }

    private void setMember(Long tenantId, UUID setId, String type, String key) {
        jdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    tenant_id, resource_set_id, resource_type, resource_key,
                    lifecycle_state)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, tenantId, setId, type, key);
    }

    private UUID responsibility(
            Long tenantId, String principalType, String principalRef,
            UUID setId, Long approver) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_admin_role_assignments (
                    admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                    responsibility_code, resource_set_id, assignment_source,
                    lifecycle_state, valid_from, valid_to, review_due_at,
                    justification, approved_by, approved_at, decision_reason)
                VALUES (?, ?, ?, ?, 'APP_CONFIG_ADMIN', ?, 'MANUAL', 'ACTIVE',
                        ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """, id, tenantId, principalType, principalRef, setId,
                VALID_FROM, VALID_TO, REVIEW_DUE,
                "Exact app configuration responsibility evidence.", approver,
                "Independent approval for scoped responsibility.");
        return id;
    }

    private record Fixture(
            Long tenantId,
            Long requester,
            Long approver,
            Long subjectOne,
            Long subjectTwo,
            UUID approvalsSet) {
    }
}
