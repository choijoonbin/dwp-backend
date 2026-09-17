package com.dwp.services.approval.policyautomation;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalFormManagementScopeTestSupport;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class PolicyAutomationPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-16T02:00:00Z");
    private static final UUID MAKER = UUID.fromString("00000000-0000-4000-8000-000000000019");
    private static final UUID CHECKER = UUID.fromString("00000000-0000-4000-8000-000000000020");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private PGSimpleDataSource dataSource;
    private TransactionTemplate transactions;
    private PolicyAutomationService service;
    private PolicyGovernanceService policyGovernance;
    private DelegationGovernanceService delegationService;
    private PolicyChannelAttestationVerifier attestationVerifier;

    @BeforeEach
    void setUp() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        JdbcTemplate bootstrap = new JdbcTemplate(dataSource);
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        flyway.clean();
        flyway.migrate();
        jdbc = bootstrap;
        jdbc.queryForObject("SELECT seed_approval_tenant(?)", Object.class, 42L);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        ApprovalDocumentCanonical canonical =
                new ApprovalDocumentCanonical(new ObjectMapper().findAndRegisterModules());
        PolicyAutomationRepository repository = new PolicyAutomationRepository(named, canonical);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        attestationVerifier = mock(PolicyChannelAttestationVerifier.class);
        when(attestationVerifier.verify(any(), any(), any()))
                .thenReturn("verified:" + "0".repeat(64));
        service = new PolicyAutomationService(repository, attestationVerifier, clock);
        policyGovernance = new PolicyGovernanceService(repository,
                new PolicyGovernanceRepository(named, canonical), clock);
        ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
        when(identities.require(anyLong(), anyLong())).thenAnswer(call -> {
            long tenant = call.getArgument(0);
            long user = call.getArgument(1);
            return new ApprovalIdentityDirectory.Subject(tenant, user, UUID.randomUUID(), UUID.randomUUID(),
                    "User " + user, "user" + user + "@example.com", "Approver", "ACTIVE",
                    List.of("APPROVAL_ADMIN"));
        });
        delegationService = new DelegationGovernanceService(repository,
                new DelegationGovernanceRepository(named, canonical), clock, identities);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        context(17, MAKER);
    }

    @AfterEach
    void tearDown() {
        ApprovalRequestContext.clear();
        ApprovalFormManagementScopeTestSupport.clear();
    }

    @Test
    void publishesOnlyWithIndependentCheckerAndCurrentCalendarChannelResolverEvidence() {
        UUID calendarId = UUID.randomUUID();
        CalendarView calendar = tx(() -> service.saveCalendar("calendar-create",
                calendar(calendarId)));
        assertThat(tx(() -> service.saveCalendar("calendar-create", calendar(calendarId))))
                .isEqualTo(calendar);

        UUID channelId = UUID.randomUUID();
        ChannelView channel = tx(() -> service.saveChannel("channel-create",
                new ChannelDraft(channelId, "EMAIL.PRIMARY", ChannelType.EMAIL,
                        Lifecycle.ACTIVE, 0)));
        tx(() -> service.observeChannel("channel-observe", channelId,
                new ChannelObservation(UUID.randomUUID(), Readiness.READY,
                        "notification-r12", "a".repeat(64), NOW.minusSeconds(10),
                        NOW.plusSeconds(300), channel.version(), "payload", "signature")));
        assertThat(jdbc.queryForObject("""
                SELECT verification_reference FROM apr_notification_channels
                 WHERE tenant_id=42 AND resource_set_key='RS_APPROVALS' AND channel_id=?
                """, String.class, channelId)).matches("verified:[0-9a-f]{64}");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_notification_channels SET verification_reference=NULL
                 WHERE tenant_id=42 AND resource_set_key='RS_APPROVALS' AND channel_id=?
                """, channelId)).isInstanceOf(DataIntegrityViolationException.class);

        UUID resolverId = UUID.randomUUID();
        seedResolver(resolverId);
        UUID policyId = UUID.randomUUID();
        PolicyView draft = tx(() -> service.savePolicyDraft("policy-draft",
                policy(policyId, calendarId, channelId, resolverId)));

        String reviewEvidence = "b".repeat(64);
        PublishCommand publish = new PublishCommand(
                draft.draftRevisionId(), draft.version(), reviewEvidence);
        assertThatThrownBy(() -> tx(() -> service.publishPolicy(
                "publish-maker", policyId, publish)))
                .isInstanceOf(PolicyAutomationRejected.class)
                .hasMessageContaining("independent checker");

        context(18, CHECKER);
        PolicyGovernanceModels.ReviewReceipt review = tx(() -> policyGovernance.review(
                "policy-review", policyId,
                new PolicyGovernanceModels.ReviewCommand(UUID.randomUUID(),
                        draft.draftRevisionId(), draft.version(),
                        PolicyGovernanceModels.ReviewDisposition.APPROVED,
                        "Independent policy review approved.", reviewEvidence)));
        PolicyView published = tx(() -> service.publishPolicy(
                "publish-checker", policyId,
                new PublishCommand(draft.draftRevisionId(),
                        review.committedPolicyVersion(), reviewEvidence)));
        assertThat(published.lifecycle()).isEqualTo(Lifecycle.ACTIVE);
        assertThat(published.draftRevisionId()).isNull();
        assertThat(published.publishedRevisionId()).isEqualTo(draft.draftRevisionId());
        assertThat(count("apr_policy_automation_publications")).isEqualTo(1);
    }

    @Test
    void staleChannelAndOptimisticCalendarUpdateFailClosed() {
        UUID calendarId = UUID.randomUUID();
        CalendarView calendar = tx(() -> service.saveCalendar("calendar", calendar(calendarId)));
        CalendarDraft stale = new CalendarDraft(calendarId, "CALENDAR.KR", "Stale",
                "Asia/Seoul", calendar.workWeek(), calendar.holidays(), calendar.exceptions(),
                Lifecycle.ACTIVE, 0);
        assertThatThrownBy(() -> tx(() -> service.saveCalendar("stale", stale)))
                .isInstanceOf(PolicyAutomationRejected.class);

        UUID channelId = UUID.randomUUID();
        ChannelView channel = tx(() -> service.saveChannel("channel",
                new ChannelDraft(channelId, "EMAIL.PRIMARY", ChannelType.EMAIL,
                        Lifecycle.ACTIVE, 0)));
        tx(() -> service.observeChannel("stale-observation", channelId,
                new ChannelObservation(UUID.randomUUID(), Readiness.READY,
                        "notification-old", "c".repeat(64), NOW.minusSeconds(600),
                        NOW.minusSeconds(1), channel.version(), "payload", "signature")));
        UUID resolverId = UUID.randomUUID();
        seedResolver(resolverId);
        UUID policyId = UUID.randomUUID();
        PolicyView draft = tx(() -> service.savePolicyDraft("draft",
                policy(policyId, calendarId, channelId, resolverId)));
        context(18, CHECKER);
        String reviewEvidence = "d".repeat(64);
        PolicyGovernanceModels.ReviewReceipt review = tx(() -> policyGovernance.review(
                "stale-policy-review", policyId,
                new PolicyGovernanceModels.ReviewCommand(UUID.randomUUID(),
                        draft.draftRevisionId(), draft.version(),
                        PolicyGovernanceModels.ReviewDisposition.APPROVED,
                        "Independent stale-channel policy review.", reviewEvidence)));
        assertThatThrownBy(() -> tx(() -> service.publishPolicy("publish", policyId,
                new PublishCommand(draft.draftRevisionId(),
                        review.committedPolicyVersion(), reviewEvidence))))
                .isInstanceOf(PolicyAutomationRejected.class)
                .hasMessageContaining("currently verified notification channels");

        ApprovalFormManagementScopeTestSupport.set("opaque-other", "RS_OTHER");
        assertThatThrownBy(() -> tx(() -> service.policy(policyId)))
                .isInstanceOf(PolicyAutomationRejected.class);
        ApprovalFormManagementScopeTestSupport.set("opaque-approvals", "RS_APPROVALS");
        assertThat(tx(() -> service.policy(policyId)).policyId()).isEqualTo(policyId);
    }

    @Test
    void rejectedReadyAttestationPreservesChannelAndCommandState() {
        UUID channelId = UUID.randomUUID();
        ChannelView channel = tx(() -> service.saveChannel("channel-untrusted",
                new ChannelDraft(channelId, "EMAIL.UNTRUSTED", ChannelType.EMAIL,
                        Lifecycle.ACTIVE, 0)));
        when(attestationVerifier.verify(any(), any(), any()))
                .thenThrow(PolicyAutomationRejected.forbidden(
                        "The notification-channel attestation is not trusted."));

        ChannelObservation observation = new ChannelObservation(
                UUID.randomUUID(), Readiness.READY, "caller-ready", "f".repeat(64),
                NOW.minusSeconds(10), NOW.plusSeconds(300), channel.version(),
                "payload", "signature");
        assertThatThrownBy(() -> tx(() -> service.observeChannel(
                "rejected-ready", channelId, observation)))
                .isInstanceOfSatisfying(PolicyAutomationRejected.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));

        ChannelView unchanged = tx(() -> service.channel(channelId));
        assertThat(unchanged.version()).isEqualTo(channel.version());
        assertThat(unchanged.readiness()).isEqualTo(Readiness.NOT_CONFIGURED);
        assertThat(count("apr_notification_channel_observations")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_policy_automation_commands
                 WHERE operation='OBSERVE_CHANNEL' AND idempotency_key='rejected-ready'
                """, Long.class)).isZero();
    }

    @Test
    void reviewsCurrentDelegationTruthWithoutClaimingUnavailableRoleSodEvidence() {
        UUID delegationId = UUID.randomUUID();
        insertDelegation(delegationId, 17, 18);
        insertDelegation(UUID.randomUUID(), 18, 19);

        DelegationView current = tx(() -> delegationService.delegation(delegationId));
        assertThat(current.noSubDelegationTruth()).isEqualTo(DelegationTruth.VIOLATED);
        assertThat(current.roleSeparationOfDutiesTruth())
                .isEqualTo(DelegationTruth.NOT_VERIFIED);
        assertThat(current.findings()).contains(
                "SUB_DELEGATION_PRESENT", "ROLE_SOD_EVIDENCE_UNAVAILABLE");

        DelegationReviewCommand command = new DelegationReviewCommand(UUID.randomUUID(),
                DelegationReviewDisposition.REMEDIATION_REQUESTED, "f".repeat(64),
                current.version());
        DelegationReviewView reviewed = tx(() -> delegationService.review(
                "delegation-review", delegationId, command));
        assertThat(reviewed.complianceState()).isEqualTo(DelegationComplianceState.BLOCKED);
        assertThat(tx(() -> delegationService.review(
                "delegation-review", delegationId, command))).isEqualTo(reviewed);
        assertThat(count("apr_delegation_governance_reviews")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT management_resource_set_key FROM apr_delegations
                 WHERE tenant_id = 42 AND delegation_id = ?
                """, String.class, delegationId)).isEqualTo("RS_APPROVALS");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_delegation_governance_reviews
                   SET resource_set_key = 'RS_OTHER'
                 WHERE tenant_id = 42 AND delegation_id = ?
                """, delegationId)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_delegation_governance_reviews
                   SET review_evidence_sha256 = ?
                 WHERE tenant_id = 42 AND delegation_id = ?
                """, "a".repeat(64), delegationId))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM apr_delegation_governance_reviews
                 WHERE tenant_id = 42 AND delegation_id = ?
                """, delegationId)).hasRootCauseInstanceOf(java.sql.SQLException.class);

        assertThatThrownBy(() -> tx(() -> delegationService.review("stale-review",
                delegationId, new DelegationReviewCommand(UUID.randomUUID(),
                        DelegationReviewDisposition.ACKNOWLEDGED_FINDINGS,
                        "e".repeat(64), current.version() + 1))))
                .isInstanceOf(PolicyAutomationRejected.class)
                .hasMessageContaining("version changed");

        ApprovalFormManagementScopeTestSupport.set("opaque-other", "RS_OTHER");
        assertThat(tx(() -> delegationService.delegations())).isEmpty();
        assertThatThrownBy(() -> tx(() -> delegationService.delegation(delegationId)))
                .isInstanceOf(PolicyAutomationRejected.class)
                .hasMessageContaining("management scope");
    }

    @Test
    void delegationAdministrationIsVersionFencedIdempotentAuditedAndKillSwitchBounded() {
        UUID scheduledId = UUID.randomUUID();
        DelegationDraft scheduled = new DelegationDraft(scheduledId, 17L, 18L,
                List.of("APPROVAL_ADMIN"), "ALL", null, null,
                NOW.plusSeconds(3_600), NOW.plusSeconds(86_400),
                "Quarter close approval coverage", 0L);
        DelegationView created = tx(() -> delegationService.create("delegation-create", scheduled));
        assertThat(tx(() -> delegationService.create("delegation-create", scheduled))).isEqualTo(created);
        assertThat(created.effectiveState()).isEqualTo(DelegationEffectiveState.SCHEDULED);
        assertThat(created.version()).isZero();

        DelegationDraft changed = new DelegationDraft(scheduledId, 17L, 18L,
                List.of("APPROVAL_ADMIN"), "ALL", null, null, scheduled.startsAt(),
                NOW.plusSeconds(172_800), "Extended quarter close coverage", created.version());
        DelegationView updated = tx(() -> delegationService.update(
                "delegation-update", scheduledId, changed));
        assertThat(updated.version()).isEqualTo(1);
        assertThat(tx(() -> delegationService.update(
                "delegation-update", scheduledId, changed))).isEqualTo(updated);
        assertThatThrownBy(() -> tx(() -> delegationService.update("delegation-stale", scheduledId,
                new DelegationDraft(scheduledId, 17L, 18L, List.of("APPROVAL_ADMIN"), "ALL",
                        null, null, scheduled.startsAt(), NOW.plusSeconds(259_200),
                        "Stale extension must fail", 0L))))
                .isInstanceOf(PolicyAutomationRejected.class)
                .hasMessageContaining("changed");

        DelegationView cancelled = tx(() -> delegationService.revoke("delegation-cancel", scheduledId,
                new DelegationStateCommand(updated.version(), "Scheduled coverage no longer required"), true));
        assertThat(cancelled.lifecycleState()).isEqualTo("REVOKED");
        assertThat(cancelled.version()).isEqualTo(2);

        UUID activeId = UUID.randomUUID();
        DelegationDraft active = new DelegationDraft(activeId, 17L, 19L,
                List.of("APPROVAL_ADMIN"), "ALL", null, null, NOW,
                NOW.plusSeconds(43_200), "Incident response approval coverage", 0L);
        assertThat(tx(() -> delegationService.create("delegation-active", active)).effectiveState())
                .isEqualTo(DelegationEffectiveState.IN_EFFECT);
        assertThatThrownBy(() -> tx(() -> delegationService.create("delegation-reverse",
                new DelegationDraft(UUID.randomUUID(), 19L, 17L, List.of("APPROVAL_ADMIN"),
                        "ALL", null, null, NOW, NOW.plusSeconds(21_600),
                        "Reverse delegation must be rejected", 0L))))
                .isInstanceOf(PolicyAutomationRejected.class)
                .hasMessageContaining("overlaps");

        DelegationKillSwitchCommand kill = new DelegationKillSwitchCommand(
                UUID.randomUUID(), 0L, "Emergency tenant delegation shutdown");
        DelegationKillSwitchView killed = tx(() -> delegationService.killSwitch("delegation-kill", kill));
        assertThat(killed.revokedDelegations()).isEqualTo(1);
        assertThat(killed.controlVersion()).isEqualTo(1);
        assertThat(tx(() -> delegationService.killSwitch("delegation-kill", kill))).isEqualTo(killed);
        assertThat(tx(() -> delegationService.delegation(activeId)).lifecycleState()).isEqualTo("REVOKED");

        assertThat(tx(() -> delegationService.audit(scheduledId, 20)))
                .extracting(DelegationAuditEvent::action)
                .containsExactly("CANCEL_SCHEDULED", "UPDATE", "CREATE");
        assertThat(tx(() -> delegationService.audit(null, 20)))
                .extracting(DelegationAuditEvent::action)
                .contains("KILL_SWITCH", "CREATE", "UPDATE", "CANCEL_SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_policy_automation_commands", Long.class))
                .isEqualTo(5);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_delegation_governance_events SET reason='tampered'
                 WHERE delegation_id=?
                """, scheduledId)).isInstanceOf(DataIntegrityViolationException.class);

        context(17, MAKER, "RS_OTHER");
        assertThatThrownBy(() -> tx(() -> delegationService.delegation(scheduledId)))
                .isInstanceOf(PolicyAutomationRejected.class)
                .hasMessageContaining("management scope");
    }

    @Test
    void migrationBackfillsAllAndWorkflowScopesAndDatabaseFencesReviewEvidence() {
        resetDatabaseTo("44");
        jdbc.queryForObject("SELECT seed_approval_tenant(?)", Object.class, 42L);
        UUID workflowId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_workflow_definitions(
                    workflow_id,tenant_id,workflow_key,name_ko,name_en,
                    description_ko,description_en,category,data_classification,
                    lifecycle_state,current_version,sla_minutes,allow_self_approval,
                    owner_group_ref,version,created_by,updated_by,
                    management_resource_set_key)
                VALUES(?,42,'FINANCE_DELEGATION','재무 위임','Finance delegation',
                    '재무 범위 위임 검증','Finance scope delegation verification',
                    'FINANCE','CONFIDENTIAL','DRAFT',1,1440,false,
                    'FINANCE_APPROVERS',0,17,17,'RS_FINANCE')
                """, workflowId);

        UUID legacyAll = UUID.randomUUID();
        UUID legacyWorkflow = UUID.randomUUID();
        insertLegacyDelegation(legacyAll, 17, 18, "ALL", null, null);
        insertLegacyDelegation(legacyWorkflow, 19, 20, "WORKFLOW",
                workflowId, "FINANCE_DELEGATION");

        migrateToLatest();

        assertThat(delegationScope(legacyAll)).isEqualTo("RS_APPROVALS");
        assertThat(delegationScope(legacyWorkflow)).isEqualTo("RS_FINANCE");

        UUID insertedAll = UUID.randomUUID();
        UUID insertedWorkflow = UUID.randomUUID();
        insertLegacyDelegation(insertedAll, 21, 22, "ALL", null, null);
        insertLegacyDelegation(insertedWorkflow, 23, 24, "WORKFLOW",
                workflowId, "FINANCE_DELEGATION");
        assertThat(delegationScope(insertedAll)).isEqualTo("RS_APPROVALS");
        assertThat(delegationScope(insertedWorkflow)).isEqualTo("RS_FINANCE");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_delegations(
                    delegation_id,tenant_id,delegator_user_id,delegate_user_id,
                    scope_type,workflow_id,workflow_key,starts_at,ends_at,reason,
                    created_by,updated_by,delegate_display_name,delegated_role_codes,
                    management_resource_set_key)
                VALUES(?,42,25,26,'WORKFLOW',?,'FINANCE_DELEGATION',?,?,?,
                    25,25,'Delegate','["APPROVAL_ADMIN"]'::jsonb,'RS_APPROVALS')
                """, UUID.randomUUID(), workflowId,
                java.sql.Timestamp.from(NOW.minusSeconds(3_600)),
                java.sql.Timestamp.from(NOW.plusSeconds(86_400)),
                "Cross-scope delegation"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertReview(
                insertedWorkflow, UUID.randomUUID(), "RS_APPROVALS", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReview(
                insertedWorkflow, UUID.randomUUID(), "RS_FINANCE", 1))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID reviewId = UUID.randomUUID();
        insertReview(insertedWorkflow, reviewId, "RS_FINANCE", 0);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_delegation_governance_reviews
                 WHERE tenant_id=42 AND resource_set_key='RS_FINANCE'
                   AND delegation_id=? AND review_id=?
                """, Integer.class, insertedWorkflow, reviewId)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_delegation_governance_reviews
                   SET review_evidence_sha256=?
                 WHERE tenant_id=42 AND resource_set_key='RS_FINANCE'
                   AND delegation_id=? AND review_id=?
                """, "b".repeat(64), insertedWorkflow, reviewId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM apr_delegation_governance_reviews
                 WHERE tenant_id=42 AND resource_set_key='RS_FINANCE'
                   AND delegation_id=? AND review_id=?
                """, insertedWorkflow, reviewId))
                .isInstanceOf(DataIntegrityViolationException.class);

        context(17, MAKER, "RS_FINANCE");
        assertThat(delegationService.delegation(insertedWorkflow))
                .satisfies(value -> {
                    assertThat(value.scopeBindingTruth()).isEqualTo(DelegationTruth.VERIFIED);
                    assertThat(value.workflowId()).isEqualTo(workflowId);
                });
    }

    private CalendarDraft calendar(UUID id) {
        WorkHours hours = new WorkHours(LocalTime.of(9, 0), LocalTime.of(18, 0));
        return new CalendarDraft(id, "CALENDAR.KR", "Korea business calendar", "Asia/Seoul",
                Map.of("MONDAY", hours, "TUESDAY", hours, "WEDNESDAY", hours,
                        "THURSDAY", hours, "FRIDAY", hours),
                List.of(new Holiday(LocalDate.parse("2026-10-03"), "Foundation Day")),
                List.of(), Lifecycle.ACTIVE, 0);
    }

    private PolicyDraft policy(
            UUID policyId, UUID calendarId, UUID channelId, UUID resolverId) {
        return new PolicyDraft(policyId, "POLICY.DEFAULT", "Default automation", calendarId,
                List.of(new Reminder("DUE_SOON", 60, channelId, "approval.due-soon", 2)),
                List.of(new Escalation("OVERDUE", 120, resolverId, channelId,
                        "ESCALATE", 1)), NOW.minusSeconds(60), null, 0);
    }

    private void seedResolver(UUID resolverId) {
        jdbc.update("""
                INSERT INTO apr_routing_resolvers(
                    tenant_id,resource_set_key,resolver_id,resolver_key,display_name,resolver_kind,
                    definition,lifecycle_state,effective_from,source_state,source_revision,
                    source_evidence_sha256,source_observed_at,source_valid_until,version,
                    created_by,updated_by)
                VALUES(42,'RS_APPROVALS',?,'RESOLVER.ESCALATION','Escalation','MANAGER',
                    '{}'::jsonb,'ACTIVE',?,'HEALTHY','people-r12',?,?,?,2,17,17)
                """, resolverId, java.sql.Timestamp.from(NOW.minusSeconds(60)),
                "e".repeat(64), java.sql.Timestamp.from(NOW.minusSeconds(10)),
                java.sql.Timestamp.from(NOW.plusSeconds(300)));
    }

    private void insertDelegation(UUID delegationId, long delegator, long delegate) {
        jdbc.update("""
                INSERT INTO apr_delegations(
                    delegation_id,tenant_id,delegator_user_id,delegate_user_id,scope_type,
                    starts_at,ends_at,reason,created_by,updated_by,delegate_display_name,
                    delegated_role_codes)
                VALUES(?,42,?,?,'ALL',?,?,?, ?,?,'Delegate',
                    '["APPROVAL_ADMIN"]'::jsonb)
                """, delegationId, delegator, delegate,
                java.sql.Timestamp.from(NOW.minusSeconds(3_600)),
                java.sql.Timestamp.from(NOW.plusSeconds(86_400)),
                "Governed temporary delegation", delegator, delegator);
    }

    private void insertLegacyDelegation(
            UUID delegationId,
            long delegator,
            long delegate,
            String scopeType,
            UUID workflowId,
            String workflowKey) {
        jdbc.update("""
                INSERT INTO apr_delegations(
                    delegation_id,tenant_id,delegator_user_id,delegate_user_id,scope_type,
                    workflow_id,workflow_key,starts_at,ends_at,reason,created_by,updated_by,
                    delegate_display_name,delegated_role_codes)
                VALUES(?,42,?,?,?,?,?,?,?, ?,?,?,'Delegate',
                    '["APPROVAL_ADMIN"]'::jsonb)
                """, delegationId, delegator, delegate, scopeType, workflowId, workflowKey,
                java.sql.Timestamp.from(NOW.minusSeconds(3_600)),
                java.sql.Timestamp.from(NOW.plusSeconds(86_400)),
                "Governed temporary delegation", delegator, delegator);
    }

    private void insertReview(
            UUID delegationId, UUID reviewId, String resourceSet, long version) {
        jdbc.update("""
                INSERT INTO apr_delegation_governance_reviews(
                    tenant_id,resource_set_key,delegation_id,review_id,
                    delegation_version,disposition,compliance_state,
                    scope_binding_truth,time_window_truth,no_sub_delegation_truth,
                    identity_separation_truth,role_snapshot_truth,role_sod_truth,
                    findings,review_evidence_sha256,reviewed_by,reviewed_at)
                VALUES(42,?,?,?,?,'ACKNOWLEDGED_FINDINGS','EVIDENCE_REQUIRED',
                    'VERIFIED','VERIFIED','VERIFIED','VERIFIED','VERIFIED','NOT_VERIFIED',
                    '["ROLE_SOD_EVIDENCE_UNAVAILABLE"]'::jsonb,?,17,?)
                """, resourceSet, delegationId, reviewId, version,
                "a".repeat(64), java.sql.Timestamp.from(NOW));
    }

    private String delegationScope(UUID delegationId) {
        return jdbc.queryForObject("""
                SELECT management_resource_set_key FROM apr_delegations
                 WHERE tenant_id=42 AND delegation_id=?
                """, String.class, delegationId);
    }

    private void resetDatabaseTo(String version) {
        jdbc.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .load().migrate();
    }

    private void context(long userId, UUID personId) {
        context(userId, personId, "RS_APPROVALS");
    }

    private void context(long userId, UUID personId, String resourceSet) {
        ApprovalRequestContext.set(userId, 42L, personId,
                Set.of("APPROVAL_ADMIN"), Set.of());
        ApprovalFormManagementScopeTestSupport.set(
                "opaque-" + resourceSet.toLowerCase(), resourceSet);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private <T> T tx(Supplier<T> supplier) {
        return transactions.execute(ignored -> supplier.get());
    }
}
