package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.domain.*;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalDraftPostgresFixture {
    static final Set<String> PERMISSIONS = Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:CREATE",
            "ACTION.APPROVAL_REQUEST:UPDATE", "ACTION.APPROVAL_REQUEST:VIEW", "ACTION.APPROVAL_TASK:VIEW");
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    ApprovalIdentityDirectory identities;
    ApprovalQueryRepository queries;
    ApprovalCommandRepository commands;
    ApprovalService approvals;
    ApprovalDraftRepository repository;
    ApprovalDraftService drafts;
    ApprovalSearchService search;
    UUID workflowId;
    UUID formId;

    void initialize(PostgreSQLContainer<?> postgres) {
        var source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        // Only this disposable Testcontainers database is reset, never the dev database.
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        var named = new NamedParameterJdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var mapper = new ObjectMapper().findAndRegisterModules();
        identities = mock(ApprovalIdentityDirectory.class);
        when(identities.require(42, 99)).thenReturn(subject(99, List.of("APPROVAL_OPERATOR")));
        when(identities.require(42, 100)).thenReturn(subject(100, List.of("APPROVAL_OPERATOR")));
        when(identities.requireRole(anyLong(), anyString())).thenAnswer(invocation ->
                new ApprovalIdentityDirectory.RoleEligibility(invocation.getArgument(0),
                        invocation.getArgument(1), "ACTIVE", 1, true));
        var authority = new ApprovalWorkAuthority(identities);
        queries = new ApprovalQueryRepository(named, mapper);
        commands = new ApprovalCommandRepository(named, mapper);
        ApprovalAttachmentLifecycleTestWiring.bindDefault(commands, named, identities, mapper);
        var audit = new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test");
        approvals = new ApprovalService(queries, commands, audit, identities, null,
                new ApprovalOwnerPredicateEvaluator(named, identities));
        repository = new ApprovalDraftRepository(named, mapper);
        drafts = new ApprovalDraftService(repository, authority, approvals, commands, audit);
        search = new ApprovalSearchService(new ApprovalSearchRepository(named), authority, identities, mapper);
        queries.ensureTenant(42);
        workflowId = jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions "
                + "WHERE tenant_id=42 AND workflow_key='ACCESS_EXCEPTION'", UUID.class);
        formId = jdbc.queryForObject("SELECT form_id FROM apr_forms "
                + "WHERE tenant_id=42 AND form_key='ACCESS_EXCEPTION_FORM'", UUID.class);
        context(99, true);
    }

    static ApprovalIdentityDirectory.Subject subject(long id, List<String> roles) {
        return new ApprovalIdentityDirectory.Subject(42L, id, null, null, "Owner", "owner@example.test", null,
                "ACTIVE", roles, List.copyOf(PERMISSIONS));
    }

    static void context(long userId, boolean exact) {
        ApprovalRequestContext.set(userId, 42L, null, "Owner", Set.of("APPROVAL_OPERATOR"), PERMISSIONS);
        ApprovalDecisionRevisionContext.clear();
        ApprovalPilotAuthorizationContext.clear();
        if (exact) {
            ApprovalDecisionRevisionContext.set("rev1", OffsetDateTime.now().plusMinutes(5), "work", "own",
                    "route.approvals.work.request-draft-recover.action", "110");
            ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(
                    "route.approvals.work.request-draft-recover.action", "ACTION", "owner", false,
                    Set.of("predicate.approval.own-request.v1", "predicate.approval.own-draft-receipt.v1",
                            "predicate.approval-task-readable.v1"), null, null, null, false, null, null)));
        }
    }

    static void clear() {
        ApprovalRequestContext.clear();
        ApprovalDecisionRevisionContext.clear();
        ApprovalPilotAuthorizationContext.clear();
        ApprovalManagementScopeContext.clear();
    }

    <T> T tx(Supplier<T> body) { return transaction.execute(ignored -> body.get()); }

    ApprovalDtos.CreateRequest body(String title) {
        return new ApprovalDtos.CreateRequest(workflowId, formId, title, "Reason", "NORMAL", payload("System"));
    }

    java.util.Map<String, Object> payload(String system) {
        return java.util.Map.of("systemName", system, "accessRole", "VIEW", "startDate", "2026-09-15",
                "endDate", "2026-10-15", "compensatingControl", "Daily access review");
    }

    ApprovalDtos.UpdateDraftRequest update(ApprovalDtos.RequestSummary request, String title, String system) {
        return new ApprovalDtos.UpdateDraftRequest(workflowId, formId, title, "Reason", "HIGH", payload(system), request.version());
    }

    ApprovalWorkDtos.SearchFilter filter(String query, int page, int size) {
        return new ApprovalWorkDtos.SearchFilter(query, "", "", null, ApprovalWorkDtos.DueFilter.ALL,
                page, size, ApprovalWorkDtos.Sort.NEWEST);
    }

    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
}
