package com.dwp.services.platform.auditcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AuditPolicyImpactSnapshotPostgresTest {

    private static final long TENANT_ID = 9_101L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void backfillsLegacyEvidenceAndPersistsCanonicalImmutablePopulationImpact() throws Exception {
        PGSimpleDataSource source = dataSource();
        Flyway preImpact = Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .target("316")
                .cleanDisabled(false)
                .load();
        preImpact.clean();
        preImpact.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(source);
        UUID legacyRevisionId = seedLegacyPublishedPolicy(jdbc);

        Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .load()
                .migrate();

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        Map<String, Object> legacy = jdbc.queryForMap("""
                SELECT impact_snapshot::text AS impact_snapshot,
                       impact_sha256,
                       encode(digest(impact_snapshot::text, 'sha256'), 'hex') AS expected_hash
                  FROM sys_audit_policy_revisions
                 WHERE audit_policy_revision_id = ?
                """, legacyRevisionId);
        JsonNode legacySnapshot = objectMapper.readTree((String) legacy.get("impact_snapshot"));
        assertThat(legacySnapshot.path("coverageState").asText())
                .isEqualTo("UNAVAILABLE_LEGACY_REVISION");
        assertThat(legacySnapshot.path("exclusions").get(0).asText())
                .isEqualTo("LEGACY_REVISION_NOT_SNAPSHOTTED");
        assertThat(legacy.get("impact_sha256")).isEqualTo(legacy.get("expected_hash"));

        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        insertAuditEvent(
                jdbc, observedAt.minus(500, ChronoUnit.DAYS), "STANDARD", 75,
                "actor-a", "target-a");
        insertAuditEvent(
                jdbc, observedAt.minus(1_200, ChronoUnit.DAYS), "EXTENDED", 20,
                "actor-b", "target-b");
        insertAuditEvent(
                jdbc, observedAt.minus(100, ChronoUnit.DAYS), "LEGAL_HOLD", 20,
                "actor-c", "target-c");
        insertAuditEvent(
                jdbc, observedAt.minus(500, ChronoUnit.DAYS), "STANDARD", 75,
                "USER", "collision-actor-a", "A:B", "C");
        insertAuditEvent(
                jdbc, observedAt.minus(500, ChronoUnit.DAYS), "STANDARD", 75,
                "USER", "collision-actor-b", "A", "B:C");

        AuditControlRepository repository = new AuditControlRepository(
                new NamedParameterJdbcTemplate(source), objectMapper);
        AuditControlDtos.RetentionPolicy active = repository.policy(TENANT_ID);
        AuditControlDtos.PolicyRevisionCreate request =
                new AuditControlDtos.PolicyRevisionCreate(
                        730, 1_000, 100, true, true, 80,
                        "Capture the population affected by revised audit controls.", null);
        AuditControlDtos.PolicyImpactSnapshot snapshot = repository.policyImpactSnapshot(
                TENANT_ID, active, request, observedAt);

        assertThat(snapshot.coverageState()).isEqualTo("COMPLETE_INTERNAL_AUDIT_EVENT_OWNER");
        assertThat(snapshot.includedOwners()).containsExactly("PLATFORM_SYS_AUDIT_EVENTS");
        assertThat(snapshot.exclusions()).contains(
                "EXTERNAL_PRODUCT_DATA_RETENTION",
                "EXTERNAL_SHARING_POPULATIONS",
                "EXTERNAL_LEGAL_HOLD_POPULATIONS",
                "FILTER_SPECIFIC_EXPORT_POPULATIONS");
        assertThat(snapshot.auditEventCount()).isEqualTo(5);
        assertThat(snapshot.affectedAuditEventCount()).isEqualTo(4);
        assertThat(snapshot.affectedActorCount()).isEqualTo(4);
        assertThat(snapshot.affectedTargetCount()).isEqualTo(4);
        assertThat(snapshot.standardRetentionEventCount()).isEqualTo(3);
        assertThat(snapshot.extendedRetentionEventCount()).isEqualTo(1);
        assertThat(snapshot.legalHoldEventCount()).isEqualTo(1);
        assertThat(snapshot.standardRetentionAffectedEventCount()).isEqualTo(3);
        assertThat(snapshot.extendedRetentionAffectedEventCount()).isEqualTo(1);
        assertThat(snapshot.highRiskClassificationAffectedEventCount()).isEqualTo(3);
        assertThat(snapshot.exportableEventCountBefore()).isEqualTo(5);
        assertThat(snapshot.exportableEventCountAfter()).isEqualTo(5);
        assertThat(snapshot.integrityProtectedEventCountBefore()).isEqualTo(5);
        assertThat(snapshot.integrityProtectedEventCountAfter()).isEqualTo(5);

        UUID revisionId = repository.createPolicyRevision(
                TENANT_ID,
                "policy-author",
                request,
                active.activeRevisionId(),
                null,
                Map.of("standardRetentionDays", Map.of("before", 365, "after", 730)),
                "b".repeat(64),
                snapshot);
        AuditControlDtos.PolicyRevision stored = repository.policyRevision(TENANT_ID, revisionId)
                .orElseThrow();
        String databaseHash = jdbc.queryForObject("""
                SELECT encode(digest(impact_snapshot::text, 'sha256'), 'hex')
                  FROM sys_audit_policy_revisions
                 WHERE audit_policy_revision_id = ?
                """, String.class, revisionId);

        assertThat(stored.impactSnapshot()).isEqualTo(snapshot);
        assertThat(stored.impactSha256()).isEqualTo(databaseHash);
        assertThat(stored.impactSha256()).matches("[0-9a-f]{64}");

        insertAuditEvent(
                jdbc, observedAt.minus(400, ChronoUnit.DAYS), "STANDARD", 90,
                "actor-later", "target-later");
        AuditControlDtos.PolicyRevision reread = repository.policyRevision(TENANT_ID, revisionId)
                .orElseThrow();
        assertThat(reread.impactSnapshot()).isEqualTo(snapshot);
        assertThat(reread.impactSha256()).isEqualTo(databaseHash);
        assertThat(repository.submitPolicyRevision(
                TENANT_ID, revisionId, "policy-author", reread.version())).isTrue();
        AuditControlDtos.PolicyRevision submitted = repository.policyRevision(TENANT_ID, revisionId)
                .orElseThrow();
        assertThat(submitted.lifecycleState()).isEqualTo("IN_REVIEW");
        assertThat(submitted.impactSnapshot()).isEqualTo(snapshot);
        assertThat(submitted.impactSha256()).isEqualTo(databaseHash);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE sys_audit_policy_revisions
                   SET impact_snapshot = jsonb_set(
                           impact_snapshot, '{auditEventCount}', '999'::jsonb)
                 WHERE audit_policy_revision_id = ?
                """, revisionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit policy impact evidence is immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM sys_audit_policy_revisions
                 WHERE audit_policy_revision_id = ?
                """, revisionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit policy impact evidence is immutable");

        insertBulkAuditEvents(jdbc, observedAt.minus(1, ChronoUnit.DAYS), 97);
        AuditControlDtos.PolicyRevisionCreate exportOnly =
                new AuditControlDtos.PolicyRevisionCreate(
                        365, 2_555, 100, true, true, 70,
                        "Reduce only the maximum rows in an audit export.", null);
        AuditControlDtos.PolicyImpactSnapshot exportImpact = repository.policyImpactSnapshot(
                TENANT_ID, active, exportOnly, observedAt);
        assertThat(exportImpact.auditEventCount()).isEqualTo(103);
        assertThat(exportImpact.affectedAuditEventCount()).isZero();
        assertThat(exportImpact.exportableEventCountBefore()).isEqualTo(103);
        assertThat(exportImpact.exportableEventCountAfter()).isEqualTo(100);
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private UUID seedLegacyPublishedPolicy(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO sys_audit_retention_policies (tenant_id, updated_by)
                VALUES (?, 'SYSTEM')
                """, TENANT_ID);
        UUID revisionId = jdbc.queryForObject("""
                INSERT INTO sys_audit_policy_revisions (
                    tenant_id, revision_number, lifecycle_state,
                    standard_retention_days, extended_retention_days,
                    export_limit_rows, require_export_reason, integrity_enabled,
                    high_risk_threshold, change_reason, diff_data, content_sha256,
                    created_by, published_by, published_at)
                VALUES (?, 1, 'PUBLISHED', 365, 2555, 50000, TRUE, TRUE, 70,
                        'Legacy published baseline', '{}'::jsonb, ?,
                        'SYSTEM', 'SYSTEM', CURRENT_TIMESTAMP)
                RETURNING audit_policy_revision_id
                """, UUID.class, TENANT_ID, "a".repeat(64));
        jdbc.update("""
                UPDATE sys_audit_retention_policies
                   SET active_revision_id = ?, active_revision_number = 1
                 WHERE tenant_id = ?
                """, revisionId, TENANT_ID);
        return revisionId;
    }

    private void insertAuditEvent(
            JdbcTemplate jdbc,
            Instant occurredAt,
            String retentionClass,
            int riskScore,
            String actorId,
            String targetId) {
        insertAuditEvent(
                jdbc, occurredAt, retentionClass, riskScore,
                "USER", actorId, "AUDIT_POLICY", targetId);
    }

    private void insertAuditEvent(
            JdbcTemplate jdbc,
            Instant occurredAt,
            String retentionClass,
            int riskScore,
            String actorType,
            String actorId,
            String targetType,
            String targetId) {
        jdbc.update("""
                INSERT INTO sys_audit_events (
                    event_id, occurred_at, event_version, tenant_id,
                    category, action, outcome, severity, risk_score,
                    actor_type, actor_id, source_service, source_module,
                    environment, target_type, target_id, retention_class, record_hash)
                VALUES (?, ?, '1', ?, 'ADMIN_CHANGE', 'audit.policy.tested',
                        'SUCCESS', 'MEDIUM', ?, ?, ?,
                        'dwp-platform-server', 'audit-control', 'test',
                        ?, ?, ?, ?)
                """, UUID.randomUUID(), Timestamp.from(occurredAt), TENANT_ID,
                riskScore, actorType, actorId, targetType, targetId,
                retentionClass, "c".repeat(64));
    }

    private void insertBulkAuditEvents(JdbcTemplate jdbc, Instant occurredAt, int count) {
        jdbc.update("""
                INSERT INTO sys_audit_events (
                    event_id, occurred_at, event_version, tenant_id,
                    category, action, outcome, severity, risk_score,
                    actor_type, actor_id, source_service, source_module,
                    environment, target_type, target_id, retention_class, record_hash)
                SELECT gen_random_uuid(), ?, '1', ?, 'ADMIN_CHANGE', 'audit.policy.bulk-tested',
                       'SUCCESS', 'LOW', 10, 'USER', 'bulk-actor-' || sequence,
                       'dwp-platform-server', 'audit-control', 'test',
                       'AUDIT_POLICY', 'bulk-target-' || sequence, 'STANDARD', ?
                  FROM generate_series(1, ?) AS sequence
                """, Timestamp.from(occurredAt), TENANT_ID, "d".repeat(64), count);
    }
}
