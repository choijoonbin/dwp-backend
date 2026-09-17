package com.dwp.services.approval.connectors;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.security.ApprovalFormManagementScopeTestSupport;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.approval.connectors.ConnectorModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ConnectorPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-16T02:00:00Z");
    private static final UUID MAKER = UUID.fromString("00000000-0000-4000-8000-000000000019");
    private static final UUID CHECKER = UUID.fromString("00000000-0000-4000-8000-000000000020");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ConnectorService service;
    private ConnectorProbeAttestationVerifier attestationVerifier;

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
        ApprovalDocumentCanonical canonical =
                new ApprovalDocumentCanonical(new ObjectMapper().findAndRegisterModules());
        attestationVerifier = mock(ConnectorProbeAttestationVerifier.class);
        when(attestationVerifier.verify(any(), any(), any(), any()))
                .thenReturn("verified:" + "0".repeat(64));
        service = new ConnectorService(new ConnectorRepository(named, canonical),
                attestationVerifier, Clock.fixed(NOW, ZoneOffset.UTC));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        context(17, MAKER);
    }

    @AfterEach
    void tearDown() {
        ApprovalRequestContext.clear();
        ApprovalFormManagementScopeTestSupport.clear();
    }

    @Test
    void requiresExactVerifiedProbeAndIndependentCheckerBeforeActivation() {
        UUID connectorId = UUID.randomUUID();
        ConnectorView draft = tx(() -> service.saveDraft("connector-draft", draft(connectorId)));
        assertThat(tx(() -> service.saveDraft("connector-draft", draft(connectorId))))
                .isEqualTo(draft);

        UUID unknownProbe = UUID.randomUUID();
        tx(() -> service.startProbe("probe-unknown", connectorId,
                probe(unknownProbe, draft, "a".repeat(64))));
        ProbeView unknown = tx(() -> service.completeProbe("probe-unknown-complete",
                connectorId, unknownProbe, completion(ProbeState.UNKNOWN_REMOTE_OUTCOME)));
        assertThat(unknown.state()).isEqualTo(ProbeState.UNKNOWN_REMOTE_OUTCOME);

        context(18, CHECKER);
        assertThatThrownBy(() -> tx(() -> service.publish("publish-unknown", connectorId,
                new PublishCommand(draft.draftRevisionId(), unknownProbe,
                        draft.version(), "b".repeat(64)))))
                .isInstanceOf(ConnectorRejected.class)
                .hasMessageContaining("current verified probe");

        context(17, MAKER);
        UUID verifiedProbe = UUID.randomUUID();
        tx(() -> service.startProbe("probe-verified", connectorId,
                probe(verifiedProbe, draft, "c".repeat(64))));
        ProbeView verified = tx(() -> service.completeProbe("probe-verified-complete",
                connectorId, verifiedProbe, completion(ProbeState.VERIFIED)));
        assertThat(verified.state()).isEqualTo(ProbeState.VERIFIED);
        assertThat(verified.diagnostics()).containsEntry("message", "[REDACTED]");
        assertThat(jdbc.queryForObject("""
                SELECT verification_reference FROM apr_connector_probe_runs
                 WHERE tenant_id=42 AND resource_set_key='RS_APPROVALS'
                   AND connector_id=? AND probe_id=?
                """, String.class, connectorId, verifiedProbe))
                .matches("verified:[0-9a-f]{64}");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_connector_probe_runs SET verification_reference=NULL
                 WHERE tenant_id=42 AND resource_set_key='RS_APPROVALS'
                   AND connector_id=? AND probe_id=?
                """, connectorId, verifiedProbe))
                .isInstanceOf(DataIntegrityViolationException.class);

        PublishCommand publish = new PublishCommand(draft.draftRevisionId(), verifiedProbe,
                draft.version(), "d".repeat(64));
        assertThatThrownBy(() -> tx(() -> service.publish(
                "publish-maker", connectorId, publish)))
                .isInstanceOf(ConnectorRejected.class)
                .hasMessageContaining("independent checker");

        context(18, CHECKER);
        ConnectorView active = tx(() -> service.publish(
                "publish-checker", connectorId, publish));
        assertThat(active.lifecycle()).isEqualTo(Lifecycle.ACTIVE);
        assertThat(active.publishedRevisionId()).isEqualTo(draft.draftRevisionId());
        assertThat(count("apr_connector_publications")).isEqualTo(1);
    }

    @Test
    void optimisticVersionAndIdempotencyFingerprintAreEnforced() {
        UUID connectorId = UUID.randomUUID();
        tx(() -> service.saveDraft("create", draft(connectorId)));
        ConnectorDraft changed = new ConnectorDraft(connectorId, "ERP.CHANGED", "Changed",
                ConnectorType.ERP, "https://erp.example.com/v2/approval",
                "vault://tenant-42/approval/erp", List.of("X-Correlation-Id"),
                Map.of("requestId", "$.requestId"), Map.of("remoteId", "$.id"),
                2_000, 120, 3, 100, 2_000,
                IdempotencyMode.HEADER, SigningMode.HMAC_SHA256, 0);
        assertThatThrownBy(() -> tx(() -> service.saveDraft("create", changed)))
                .isInstanceOf(ConnectorRejected.class)
                .hasMessageContaining("idempotency key");
        assertThatThrownBy(() -> tx(() -> service.saveDraft("stale", changed)))
                .isInstanceOf(ConnectorRejected.class);
        assertThat(jdbc.queryForObject("""
                SELECT credential_reference FROM apr_connector_revisions
                 WHERE connector_id=?
                """, String.class, connectorId)).startsWith("vault://");

        ApprovalFormManagementScopeTestSupport.set("opaque-other", "RS_OTHER");
        assertThatThrownBy(() -> tx(() -> service.connector(connectorId)))
                .isInstanceOf(ConnectorRejected.class);
        ApprovalFormManagementScopeTestSupport.set("opaque-approvals", "RS_APPROVALS");
        assertThat(tx(() -> service.connector(connectorId)).connector().connectorId())
                .isEqualTo(connectorId);
    }

    @Test
    void rejectedVerifiedAttestationPreservesProbeAndCommandState() {
        UUID connectorId = UUID.randomUUID();
        ConnectorView connector = tx(() -> service.saveDraft(
                "connector-untrusted", draft(connectorId)));
        UUID probeId = UUID.randomUUID();
        ProbeView pending = tx(() -> service.startProbe("probe-untrusted", connectorId,
                probe(probeId, connector, "9".repeat(64))));
        when(attestationVerifier.verify(any(), any(), any(), any()))
                .thenThrow(ConnectorRejected.forbidden(
                        "The connector-probe attestation is not trusted."));

        assertThatThrownBy(() -> tx(() -> service.completeProbe(
                "rejected-verified", connectorId, probeId,
                completion(ProbeState.VERIFIED))))
                .isInstanceOfSatisfying(ConnectorRejected.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN));

        ProbeView unchanged = tx(() -> service.probe(connectorId, probeId));
        assertThat(unchanged.version()).isEqualTo(pending.version());
        assertThat(unchanged.state()).isEqualTo(ProbeState.PENDING);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_connector_commands
                 WHERE operation='COMPLETE_PROBE' AND idempotency_key='rejected-verified'
                """, Long.class)).isZero();
    }

    private ConnectorDraft draft(UUID connectorId) {
        return new ConnectorDraft(connectorId, "ERP.PRIMARY", "Primary ERP",
                ConnectorType.ERP, "https://erp.example.com/v1/approval",
                "vault://tenant-42/approval/erp", List.of("X-Correlation-Id"),
                Map.of("requestId", "$.requestId"), Map.of("remoteId", "$.id"),
                2_000, 120, 3, 100, 2_000,
                IdempotencyMode.HEADER, SigningMode.HMAC_SHA256, 0);
    }

    private ProbeStart probe(UUID probeId, ConnectorView connector, String requestSha) {
        return new ProbeStart(probeId, connector.draftRevisionId(),
                ProbeKind.SYNTHETIC_TEST, requestSha, connector.version());
    }

    private ProbeCompletion completion(ProbeState state) {
        return new ProbeCompletion(1, state, "provider-r17", "e".repeat(64),
                Map.of("status", 200,
                        "message", "Bearer abcdefghijklmnopqrstuvwxyz0123456789",
                        "ignoredSecret", "not persisted"),
                NOW, NOW.plusSeconds(300),
                "eyJmaXh0dXJlIjoidGVzdCJ9", "A".repeat(86));
    }

    private void context(long userId, UUID personId) {
        ApprovalRequestContext.set(userId, 42L, personId,
                Set.of("APPROVAL_ADMIN"), Set.of());
        ApprovalFormManagementScopeTestSupport.set("opaque-approvals", "RS_APPROVALS");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private <T> T tx(Supplier<T> supplier) {
        return transactions.execute(ignored -> supplier.get());
    }
}
