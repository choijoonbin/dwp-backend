package com.dwp.services.approval.routingdirectory;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.approval.routingdirectory.RoutingDirectoryModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class RoutingDirectoryPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-16T02:00:00Z");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000000019");
    private static final UUID CANDIDATE = UUID.fromString("00000000-0000-4000-8000-000000000071");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private RoutingDirectoryRepository repository;
    private RoutingDirectoryService service;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(source)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        JdbcTemplate bootstrap = new JdbcTemplate(source);
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        bootstrap.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        flyway.clean();
        flyway.migrate();
        jdbc = bootstrap;
        jdbc.queryForObject("SELECT seed_approval_tenant(?)", Object.class, 42L);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(source);
        repository = new RoutingDirectoryRepository(named,
                new ApprovalDocumentCanonical(new ObjectMapper().findAndRegisterModules()));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        service = new RoutingDirectoryService(repository,
                (context, resolver, at) -> List.of(
                        new Candidate(71, CANDIDATE, "Verified Approver", "people-r11")),
                Clock.fixed(NOW, ZoneOffset.UTC));
        context("RS_APPROVALS");
    }

    @AfterEach
    void tearDown() {
        ApprovalRequestContext.clear();
        ApprovalFormManagementScopeTestSupport.clear();
    }

    @Test
    void resolvesNestedVerifiedGroupsAndReplaysCommandsExactlyOnce() {
        UUID resolverId = UUID.randomUUID();
        ResolverView created = tx(() -> service.saveResolver("resolver-create", resolver(resolverId)));
        ResolverView replay = tx(() -> service.saveResolver("resolver-create", resolver(resolverId)));
        assertThat(replay).isEqualTo(created);
        assertThat(created.sourceState()).isEqualTo(SourceState.NOT_VERIFIED);
        assertThatThrownBy(() -> tx(() -> service.observeResolver(
                "resolver-observe", resolverId, observation(created.version()))))
                .isInstanceOf(RoutingDirectoryRejected.class)
                .hasMessageContaining("authoritative source adapter");

        UUID childId = UUID.randomUUID();
        GroupView child = tx(() -> service.saveGroup("group-child", group(childId,
                List.of(MemberDraft.resolver(UUID.randomUUID(), resolverId, 1, true)))));
        UUID rootId = UUID.randomUUID();
        tx(() -> service.saveGroup("group-root", group(rootId,
                List.of(MemberDraft.group(UUID.randomUUID(), childId, 1, true)))));

        assertThatThrownBy(() -> tx(() -> service.resolve(rootId, NOW)))
                .isInstanceOf(RoutingDirectoryRejected.class)
                .hasMessageContaining("not currently verified");
        seedVerifiedResolver(resolverId, created.version());
        Resolution resolution = tx(() -> service.resolve(rootId, NOW));
        assertThat(resolution.candidates()).extracting(Candidate::personPublicId)
                .containsExactly(CANDIDATE);
        assertThat(resolution.sourceRevisions()).containsExactly("people-source-r11");
        assertThat(count("apr_routing_directory_commands")).isEqualTo(3);
        assertThat(child.version()).isEqualTo(1);
    }

    @Test
    void cycleNoCandidateScopeAndRetirementImpactAreFailClosed() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        tx(() -> service.saveGroup("first", group(firstId,
                List.of(MemberDraft.subject(UUID.randomUUID(), 72, UUID.randomUUID(), 1, true)))));
        tx(() -> service.saveGroup("second", group(secondId,
                List.of(MemberDraft.group(UUID.randomUUID(), firstId, 1, true)))));
        GroupDraft cycle = new GroupDraft(firstId, "GROUP.FIRST", "First", "",
                Lifecycle.ACTIVE, NOW.minusSeconds(60), null,
                List.of(MemberDraft.group(UUID.randomUUID(), secondId, 1, true)), 1);
        assertThatThrownBy(() -> tx(() -> service.saveGroup("cycle", cycle)))
                .isInstanceOf(RuntimeException.class);

        Usage usage = new Usage(firstId, "WORKFLOW", UUID.randomUUID(),
                "workflow-r1", "b".repeat(64), true, NOW, 1);
        tx(() -> service.recordUsage("usage-active", usage));
        assertThat(tx(() -> service.retirementImpact(firstId)).totalImpactCount()).isEqualTo(2);
        assertThatThrownBy(() -> tx(() -> service.retireGroup(
                "retire-active", firstId, 1, 2)))
                .isInstanceOf(RoutingDirectoryRejected.class);

        tx(() -> service.recordUsage("usage-inactive", new Usage(firstId,
                usage.usageKind(), usage.usageOwnerId(), "workflow-r2",
                "c".repeat(64), false, NOW.plusSeconds(1), 1)));
        context("RS_OTHER");
        assertThatThrownBy(() -> tx(() -> service.resolve(firstId, NOW)))
                .isInstanceOf(RoutingDirectoryRejected.class);

        context("RS_APPROVALS");
        RoutingDirectoryService empty = new RoutingDirectoryService(repository,
                (ignoredContext, ignoredResolver, ignoredAt) -> List.of(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        UUID resolverId = UUID.randomUUID();
        ResolverView resolver = tx(() -> service.saveResolver("empty-resolver", resolver(resolverId)));
        seedVerifiedResolver(resolverId, resolver.version());
        UUID emptyId = UUID.randomUUID();
        tx(() -> service.saveGroup("empty-group", group(emptyId,
                List.of(MemberDraft.resolver(UUID.randomUUID(), resolverId, 1, true)))));
        assertThatThrownBy(() -> tx(() -> empty.resolve(emptyId, NOW)))
                .isInstanceOf(RoutingDirectoryRejected.class)
                .hasMessageContaining("required approver member");
    }

    private ResolverDraft resolver(UUID id) {
        return new ResolverDraft(id, "RESOLVER.MANAGER", "Manager",
                ResolverKind.MANAGER, Map.of("purpose", "APPROVAL_ROUTING"),
                Lifecycle.ACTIVE, NOW.minusSeconds(60), null, 0);
    }

    private SourceObservation observation(long version) {
        return new SourceObservation(UUID.randomUUID(), SourceState.HEALTHY,
                "people-source-r11", "a".repeat(64), NOW.minusSeconds(10),
                NOW.plusSeconds(300), version);
    }

    private void seedVerifiedResolver(UUID resolverId, long version) {
        int updated = jdbc.update("""
                UPDATE apr_routing_resolvers
                   SET source_state='HEALTHY',source_revision='people-source-r11',
                       source_evidence_sha256=?,source_observed_at=?,source_valid_until=?
                 WHERE tenant_id=42 AND resource_set_key='RS_APPROVALS'
                   AND resolver_id=? AND version=? AND source_state='NOT_VERIFIED'
                """, "a".repeat(64), java.sql.Timestamp.from(NOW.minusSeconds(10)),
                java.sql.Timestamp.from(NOW.plusSeconds(300)), resolverId, version);
        assertThat(updated).isEqualTo(1);
    }

    private GroupDraft group(UUID id, List<MemberDraft> members) {
        return new GroupDraft(id, "GROUP." + id.toString().substring(0, 8).toUpperCase(),
                "Approvers", "", Lifecycle.ACTIVE, NOW.minusSeconds(60), null, members, 0);
    }

    private void context(String scope) {
        ApprovalRequestContext.set(17L, 42L, ACTOR, Set.of("APPROVAL_ADMIN"), Set.of());
        ApprovalFormManagementScopeTestSupport.set("opaque-" + scope, scope);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private <T> T tx(Supplier<T> supplier) {
        return transactions.execute(ignored -> supplier.get());
    }
}
