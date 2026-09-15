package com.dwp.services.approval.policy;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalFormManagementScopeTestSupport;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalPolicyDraftPostgresTest {
    private static final long TENANT = 42L;
    private static final long MAKER = 99L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ApprovalPolicyDraftService service;
    private ApprovalIdentityDirectory identities;
    private ApprovalCommandRepository commands;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        JdbcTemplate bootstrap = new JdbcTemplate(source);
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        Flyway migration = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        migration.clean();
        migration.migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.queryForObject("SELECT seed_approval_tenant(?)", Object.class, TENANT);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(source);
        identities = mock(ApprovalIdentityDirectory.class);
        when(identities.require(TENANT, MAKER)).thenReturn(subject(
                TENANT, MAKER, List.of("APPROVAL_OPERATOR"),
                List.of("ADMIN.APPROVAL_POLICY:UPDATE")));
        service = new ApprovalPolicyDraftService(
                new ApprovalPolicyDraftRepository(
                        named, mapper, new ApprovalDocumentCanonical(mapper)),
                identities,
                new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test"));
        commands = new ApprovalCommandRepository(named, mapper);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        context(TENANT, MAKER);
    }

    @AfterEach
    void tearDown() {
        ApprovalRequestContext.clear();
        ApprovalFormManagementScopeTestSupport.clear();
    }

    @Test
    void createsOneInactiveMakerDraftAndReplaysTheImmutableResultExactlyOnce() {
        var first = tx(() -> service.create(request("SOD.FINANCE.REQUESTER_MAKER"),
                "create-one", "correlation-15"));
        var replay = tx(() -> service.create(request("SOD.FINANCE.REQUESTER_MAKER"),
                "create-one", "different-correlation"));

        assertThat(replay).isEqualTo(first);
        assertThat(first.version()).isZero();
        assertThat(first.lifecycleState()).isEqualTo("DISABLED");
        assertThat(first.enforcementMode()).isEqualTo("MONITOR");
        assertThat(first.pendingReview()).isTrue();
        assertThat(first.pendingLifecycleState()).isEqualTo("ACTIVE");
        assertThat(first.pendingBy()).isEqualTo(MAKER);
        assertThat(count("apr_policy_rules", "policy_key='SOD.FINANCE.REQUESTER_MAKER'"))
                .isEqualTo(1);
        assertThat(count("apr_policy_creation_commands", "idempotency_key='create-one'"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_audit_outbox
                 WHERE payload->>'action'='approval.policy.draft.created'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void conflictsOnAReboundCommandOrDuplicateScopedKeyAndFailsClosedOnUnknown() {
        tx(() -> service.create(request("SOD.FINANCE.REQUESTER_MAKER"),
                "create-one", null));
        assertThatThrownBy(() -> tx(() -> service.create(
                request("SOD.FINANCE.OTHER"), "create-one", null)))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        assertThatThrownBy(() -> tx(() -> service.create(
                request("SOD.FINANCE.REQUESTER_MAKER"), "create-two", null)))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);

        jdbc.update("""
                UPDATE apr_policy_creation_commands
                   SET status='UNKNOWN', policy_id=NULL, result_payload=NULL, completed_at=NULL
                 WHERE tenant_id=? AND management_resource_set_key='RS_APPROVALS'
                   AND actor_user_id=? AND idempotency_key='create-one'
                """, TENANT, MAKER);
        assertThatThrownBy(() -> tx(() -> service.create(
                request("SOD.FINANCE.REQUESTER_MAKER"), "create-one", null)))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void currentAuthorityRevocationAndTenantBoundariesAreFailClosed() {
        when(identities.require(TENANT, MAKER)).thenReturn(subject(
                TENANT, MAKER, List.of("APPROVAL_OPERATOR"), List.of()));
        assertThatThrownBy(() -> tx(() -> service.create(
                request("SOD.FINANCE.REQUESTER_MAKER"), "denied", null)))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(count("apr_policy_creation_commands", "TRUE")).isZero();

        when(identities.require(TENANT, MAKER)).thenReturn(subject(
                TENANT + 1, MAKER, List.of("APPROVAL_OPERATOR"),
                List.of("ADMIN.APPROVAL_POLICY:UPDATE")));
        assertThatThrownBy(() -> tx(() -> service.create(
                request("SOD.FINANCE.REQUESTER_MAKER"), "wrong-tenant", null)))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(count("apr_policy_creation_commands", "TRUE")).isZero();
    }

    @Test
    void createdDraftRequiresAnIndependentPublisherAndStartsHistoryAtVersionOne() {
        var created = tx(() -> service.create(request("SOD.FINANCE.REQUESTER_MAKER"),
                "maker-checker", null));
        ApprovalDtos.PublishPolicyRequest publish = new ApprovalDtos.PublishPolicyRequest(
                0L, "독립 검토자가 신규 정책의 적용 범위를 확인했습니다");

        assertThatThrownBy(() -> tx(() -> {
            commands.publishPolicy(actor(MAKER), created.policyId(), publish);
            return null;
        })).isInstanceOf(BaseException.class);

        tx(() -> {
            commands.publishPolicy(actor(100L), created.policyId(), publish);
            return null;
        });
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT lifecycle_state, version, pending_by
                  FROM apr_policy_rules WHERE policy_id=?
                """, created.policyId());
        assertThat(row.get("lifecycle_state")).isEqualTo("ACTIVE");
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(row.get("pending_by")).isNull();
        Map<String, Object> history = jdbc.queryForMap("""
                SELECT version_number, submitted_by, published_by
                  FROM apr_policy_rule_versions WHERE policy_id=?
                """, created.policyId());
        assertThat(history).containsEntry("version_number", 1)
                .containsEntry("submitted_by", MAKER)
                .containsEntry("published_by", 100L);
    }

    private ApprovalPolicyDraftDtos.Create request(String key) {
        return new ApprovalPolicyDraftDtos.Create(
                key, "재무 기안자-게시자 분리", "Finance requester and publisher separation",
                "SEGREGATION_OF_DUTIES", "BLOCK", "CRITICAL", "ACTIVE",
                Map.of("requesterCannotPublish", true),
                "재무 결재의 기안자와 게시자를 분리합니다");
    }

    private void context(long tenant, long user) {
        ApprovalRequestContext.set(user, tenant, null, "Maker",
                Set.of("APPROVAL_OPERATOR"), Set.of("ADMIN.APPROVAL_POLICY:UPDATE"));
        ApprovalFormManagementScopeTestSupport.set("scope-apr15", "RS_APPROVALS");
    }

    private ApprovalRequestContext.Actor actor(long user) {
        return new ApprovalRequestContext.Actor(
                user, TENANT, null, "Policy user", Set.of("APPROVAL_PUBLISHER"),
                Set.of("ADMIN.APPROVAL_POLICY:PUBLISH"));
    }

    private ApprovalIdentityDirectory.Subject subject(
            long tenant, long user, List<String> roles, List<String> permissions) {
        return new ApprovalIdentityDirectory.Subject(
                tenant, user, null, null, "Maker", "maker@example.test", null,
                "ACTIVE", roles, permissions);
    }

    private <T> T tx(java.util.concurrent.Callable<T> work) {
        return transactions.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    private Integer count(String table, String predicate) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + predicate,
                Integer.class);
    }
}
