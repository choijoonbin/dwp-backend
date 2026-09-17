package com.dwp.services.approval.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;

@Repository
public class ApprovalDeploymentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDeploymentCanonical canonical;

    public ApprovalDeploymentRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.canonical = new ApprovalDeploymentCanonical(mapper);
    }

    PackageRecord packageById(Scope scope, UUID packageId) {
        List<PackageRecord> rows = jdbc.query("""
                SELECT package_id, package_key, package_version, display_name,
                       manifest_sha256, rollback_class, created_at, created_by
                  FROM apr_deployment_packages
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND package_id = :id
                """, params(scope).addValue("id", packageId), (result, row) ->
                packageRecord(scope, result));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    PackageRecord packageByKey(Scope scope, String key, int version) {
        List<PackageRecord> rows = jdbc.query("""
                SELECT package_id, package_key, package_version, display_name,
                       manifest_sha256, rollback_class, created_at, created_by
                  FROM apr_deployment_packages
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND package_key = :key
                   AND package_version = :version
                """, params(scope).addValue("key", key).addValue("version", version),
                (result, row) -> packageRecord(scope, result));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<PackageRecord> packages(Scope scope, int limit) {
        List<UUID> ids = jdbc.query("""
                SELECT package_id
                  FROM apr_deployment_packages
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                 ORDER BY created_at DESC, package_id
                 LIMIT :limit
                """, params(scope).addValue("limit", limit),
                (result, row) -> result.getObject("package_id", UUID.class));
        return ids.stream().map(id -> packageById(scope, id)).toList();
    }

    PackageRecord priorPackage(Scope scope, long maker, String idempotencyKey) {
        List<UUID> ids = jdbc.query("""
                SELECT package_id
                  FROM apr_deployment_packages
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND created_by = :maker
                   AND idempotency_key = :key
                """, params(scope)
                .addValue("maker", maker)
                .addValue("key", idempotencyKey),
                (result, row) -> result.getObject("package_id", UUID.class));
        return ids.isEmpty() ? null : packageById(scope, ids.getFirst());
    }

    String packageRequestHash(Scope scope, UUID packageId) {
        List<String> rows = jdbc.query("""
                SELECT request_sha256
                  FROM apr_deployment_packages
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND package_id = :id
                """, params(scope).addValue("id", packageId),
                (result, row) -> result.getString(1));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void insertPackage(
            Scope scope,
            PackageCommand command,
            Manifest manifest,
            String manifestSha256,
            RollbackDisposition rollback,
            GovernedCommand governed,
            String requestSha256,
            Instant now) {
        jdbc.update("""
                INSERT INTO apr_deployment_packages (
                    package_id, tenant_id, management_resource_set_key,
                    package_key, package_version, display_name,
                    manifest_payload, manifest_sha256, rollback_class,
                    created_by, idempotency_key, request_sha256, created_at)
                VALUES (:id, :tenant, :scope, :key, :packageVersion, :name,
                    CAST(:manifest AS jsonb), :hash, :rollback, :actor,
                    :idempotencyKey, :requestSha256, :now)
                """, params(scope)
                .addValue("id", command.packageId())
                .addValue("key", command.packageKey())
                .addValue("packageVersion", command.packageVersion())
                .addValue("name", command.displayName())
                .addValue("manifest", canonical.json(manifest))
                .addValue("hash", manifestSha256)
                .addValue("rollback", rollback.name())
                .addValue("actor", scope.actorUserId())
                .addValue("idempotencyKey", governed.idempotencyKey())
                .addValue("requestSha256", requestSha256)
                .addValue("now", Timestamp.from(now)));
        command.assets().forEach(asset -> jdbc.update("""
                INSERT INTO apr_deployment_assets (
                    tenant_id, management_resource_set_key, package_id,
                    asset_key, asset_type, asset_id, asset_version,
                    content_sha256, rollback_disposition, external_side_effects)
                VALUES (:tenant, :scope, :packageId, :assetKey, :assetType,
                    :assetId, :assetVersion, :hash, :rollback, :external)
                """, params(scope)
                .addValue("packageId", command.packageId())
                .addValue("assetKey", asset.assetKey())
                .addValue("assetType", asset.assetType().name())
                .addValue("assetId", asset.assetId())
                .addValue("assetVersion", asset.assetVersion())
                .addValue("hash", asset.contentSha256())
                .addValue("rollback", asset.rollbackDisposition().name())
                .addValue("external", asset.externalSideEffects())));
        command.dependencies().forEach(dependency -> jdbc.update("""
                INSERT INTO apr_deployment_dependencies (
                    tenant_id, management_resource_set_key, package_id,
                    asset_key, depends_on_asset_key, required_sha256, optional)
                VALUES (:tenant, :scope, :packageId, :assetKey,
                    :dependsOn, :hash, :optional)
                """, params(scope)
                .addValue("packageId", command.packageId())
                .addValue("assetKey", dependency.assetKey())
                .addValue("dependsOn", dependency.dependsOnAssetKey())
                .addValue("hash", dependency.requiredSha256())
                .addValue("optional", dependency.optional())));
        upsertDevelopmentHead(scope, command.packageId(), now);
    }

    Promotion priorPromotion(Scope scope, long maker, String idempotencyKey) {
        List<Promotion> rows = jdbc.query(PROMOTION_SELECT + """
                 AND promotion.maker_user_id = :maker
                 AND promotion.idempotency_key = :key
                """, params(scope).addValue("maker", maker).addValue("key", idempotencyKey),
                this::promotion);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    String promotionRequestHash(Scope scope, UUID promotionId) {
        List<String> rows = jdbc.query("""
                SELECT request_sha256
                  FROM apr_deployment_promotions
                 WHERE tenant_id = :tenant AND management_resource_set_key = :scope
                   AND promotion_id = :id
                """, params(scope).addValue("id", promotionId),
                (result, row) -> result.getString(1));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    void insertPromotion(
            Scope scope,
            PromotionCommand command,
            GovernedCommand governed,
            String requestSha256,
            Instant now) {
        jdbc.update("""
                INSERT INTO apr_deployment_promotions (
                    promotion_id, tenant_id, management_resource_set_key,
                    package_id, source_environment, target_environment,
                    status, maker_user_id, idempotency_key, request_sha256,
                    created_at, updated_at)
                VALUES (:id, :tenant, :scope, :packageId, :source, :target,
                    'PENDING_REVIEW', :actor, :key, :hash, :now, :now)
                """, params(scope)
                .addValue("id", command.promotionId())
                .addValue("packageId", command.packageId())
                .addValue("source", command.sourceEnvironment().name())
                .addValue("target", command.targetEnvironment().name())
                .addValue("actor", scope.actorUserId())
                .addValue("key", governed.idempotencyKey())
                .addValue("hash", requestSha256)
                .addValue("now", Timestamp.from(now)));
        journal(scope, command.promotionId(), "PROMOTION_REQUESTED",
                scope.actorUserId(), "{}", now);
    }

    Promotion promotion(Scope scope, UUID promotionId, boolean lock) {
        List<Promotion> rows = jdbc.query(PROMOTION_SELECT + """
                 AND promotion.promotion_id = :id
                """ + (lock ? " FOR UPDATE" : ""),
                params(scope).addValue("id", promotionId), this::promotion);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<Promotion> promotions(Scope scope, String status, int limit) {
        StringBuilder sql = new StringBuilder(PROMOTION_SELECT);
        MapSqlParameterSource parameters = params(scope).addValue("limit", limit);
        if (status != null) {
            sql.append(" AND promotion.status = :status");
            parameters.addValue("status", status);
        }
        sql.append(" ORDER BY promotion.updated_at DESC, promotion.promotion_id LIMIT :limit");
        return jdbc.query(sql.toString(), parameters, this::promotion);
    }

    boolean approve(
            Scope scope,
            UUID promotionId,
            long expectedVersion,
            String comment,
            GovernedCommand command,
            Instant now) {
        return jdbc.update("""
                UPDATE apr_deployment_promotions
                   SET status = 'APPROVED', checker_user_id = :checker,
                       review_comment = :comment,
                       step_up_evidence_reference = :stepUp,
                       authorization_context_key = :context,
                       decision_revision = :revision,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenant AND management_resource_set_key = :scope
                   AND promotion_id = :id AND version = :version
                   AND status = 'PENDING_REVIEW'
                   AND maker_user_id <> :checker
                """, params(scope)
                .addValue("id", promotionId)
                .addValue("version", expectedVersion)
                .addValue("checker", scope.actorUserId())
                .addValue("comment", comment)
                .addValue("stepUp", command.stepUpEvidenceReference())
                .addValue("context", command.authorizationContextKey())
                .addValue("revision", command.decisionRevision())
                .addValue("now", Timestamp.from(now))) == 1;
    }

    boolean transition(
            Scope scope,
            UUID promotionId,
            long expectedVersion,
            List<String> expectedStatuses,
            String status,
            Instant scheduledFor,
            Instant activationStartedAt,
            Instant completedAt,
            Instant now) {
        return jdbc.update("""
                UPDATE apr_deployment_promotions
                   SET status = :status,
                       scheduled_for = COALESCE(:scheduledFor, scheduled_for),
                       activation_started_at = COALESCE(:activationStartedAt, activation_started_at),
                       completed_at = COALESCE(:completedAt, completed_at),
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenant AND management_resource_set_key = :scope
                   AND promotion_id = :id AND version = :version
                   AND status IN (:expectedStatuses)
                """, params(scope)
                .addValue("id", promotionId)
                .addValue("version", expectedVersion)
                .addValue("expectedStatuses", expectedStatuses)
                .addValue("status", status)
                .addValue("scheduledFor", timestamp(scheduledFor))
                .addValue("activationStartedAt", timestamp(activationStartedAt))
                .addValue("completedAt", timestamp(completedAt))
                .addValue("now", Timestamp.from(now))) == 1;
    }

    void insertEvidence(
            Scope scope,
            UUID promotionId,
            ExternalHealthEvidence evidence,
            Instant now) {
        jdbc.update("""
                INSERT INTO apr_deployment_evidence (
                    evidence_id, tenant_id, management_resource_set_key,
                    promotion_id, evidence_type, observed_outcome,
                    external_reference, payload_sha256, verification_reference,
                    source_generated_at, recorded_at)
                VALUES (:evidenceId, :tenant, :scope, :promotionId, :type,
                    :outcome, :reference, :hash, :verificationReference,
                    :generatedAt, :now)
                """, params(scope)
                .addValue("evidenceId", evidence.evidenceId())
                .addValue("promotionId", promotionId)
                .addValue("type", evidence.evidenceType())
                .addValue("outcome", evidence.outcome().name())
                .addValue("reference", evidence.externalReference())
                .addValue("hash", evidence.payloadSha256())
                .addValue("verificationReference", evidence.verificationReference())
                .addValue("generatedAt", Timestamp.from(evidence.sourceGeneratedAt()))
                .addValue("now", Timestamp.from(now)));
    }

    List<EvidenceRecord> evidence(Scope scope, UUID promotionId) {
        return jdbc.query("""
                SELECT evidence_id, evidence_type, observed_outcome,
                       external_reference, payload_sha256, verification_reference,
                       source_generated_at, recorded_at
                  FROM apr_deployment_evidence
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                   AND promotion_id = :promotionId
                 ORDER BY recorded_at, evidence_id
                """, params(scope).addValue("promotionId", promotionId),
                (result, row) -> new EvidenceRecord(
                        new ExternalHealthEvidence(
                                result.getObject("evidence_id", UUID.class),
                                result.getString("evidence_type"),
                                HealthOutcome.valueOf(result.getString("observed_outcome")),
                                result.getString("external_reference"),
                                result.getString("payload_sha256"),
                                instant(result, "source_generated_at"),
                                result.getString("verification_reference")),
                        instant(result, "recorded_at")));
    }

    EnvironmentHead environmentHead(Scope scope, Environment environment, boolean lock) {
        List<EnvironmentHead> rows = jdbc.query("""
                SELECT environment, active_package_id, previous_package_id,
                       version, updated_at
                  FROM apr_deployment_environment_heads
                 WHERE tenant_id = :tenant AND management_resource_set_key = :scope
                   AND environment = :environment
                """ + (lock ? " FOR UPDATE" : ""),
                params(scope).addValue("environment", environment.name()),
                (result, row) -> new EnvironmentHead(
                        Environment.valueOf(result.getString("environment")),
                        result.getObject("active_package_id", UUID.class),
                        result.getObject("previous_package_id", UUID.class),
                        result.getLong("version"),
                        result.getTimestamp("updated_at").toInstant()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    List<EnvironmentHead> environmentHeads(Scope scope) {
        return jdbc.query("""
                SELECT environment, active_package_id, previous_package_id,
                       version, updated_at
                  FROM apr_deployment_environment_heads
                 WHERE tenant_id = :tenant
                   AND management_resource_set_key = :scope
                 ORDER BY CASE environment
                     WHEN 'DEVELOPMENT' THEN 1
                     WHEN 'TEST' THEN 2
                     WHEN 'PRODUCTION' THEN 3
                     ELSE 4 END
                """, params(scope), (result, row) -> new EnvironmentHead(
                Environment.valueOf(result.getString("environment")),
                result.getObject("active_package_id", UUID.class),
                result.getObject("previous_package_id", UUID.class),
                result.getLong("version"), instant(result, "updated_at")));
    }

    void activateHead(
            Scope scope,
            Environment environment,
            UUID packageId,
            Instant now) {
        jdbc.update("""
                INSERT INTO apr_deployment_environment_heads (
                    tenant_id, management_resource_set_key, environment,
                    active_package_id, previous_package_id, version, updated_at)
                VALUES (:tenant, :scope, :environment, :packageId, NULL, 1, :now)
                ON CONFLICT (tenant_id, management_resource_set_key, environment)
                DO UPDATE SET previous_package_id = apr_deployment_environment_heads.active_package_id,
                              active_package_id = EXCLUDED.active_package_id,
                              version = apr_deployment_environment_heads.version + 1,
                              updated_at = EXCLUDED.updated_at
                """, params(scope)
                .addValue("environment", environment.name())
                .addValue("packageId", packageId)
                .addValue("now", Timestamp.from(now)));
    }

    void restorePreviousHead(Scope scope, Environment environment, Instant now) {
        jdbc.update("""
                UPDATE apr_deployment_environment_heads
                   SET active_package_id = previous_package_id,
                       previous_package_id = active_package_id,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenant AND management_resource_set_key = :scope
                   AND environment = :environment
                   AND previous_package_id IS NOT NULL
                """, params(scope)
                .addValue("environment", environment.name())
                .addValue("now", Timestamp.from(now)));
    }

    void journal(
            Scope scope,
            UUID promotionId,
            String eventType,
            long actor,
            String payload,
            Instant now) {
        jdbc.update("""
                INSERT INTO apr_deployment_journal (
                    journal_id, tenant_id, management_resource_set_key,
                    promotion_id, event_type, actor_user_id, event_payload, occurred_at)
                VALUES (:journalId, :tenant, :scope, :promotionId,
                    :eventType, :actor, CAST(:payload AS jsonb), :now)
                """, params(scope)
                .addValue("journalId", UUID.randomUUID())
                .addValue("promotionId", promotionId)
                .addValue("eventType", eventType)
                .addValue("actor", actor)
                .addValue("payload", payload)
                .addValue("now", Timestamp.from(now)));
    }

    private PackageRecord packageRecord(Scope scope, ResultSet result) throws SQLException {
        UUID packageId = result.getObject("package_id", UUID.class);
        return new PackageRecord(
                packageId, result.getString("package_key"),
                result.getInt("package_version"), result.getString("display_name"),
                result.getString("manifest_sha256"),
                RollbackDisposition.valueOf(result.getString("rollback_class")),
                assets(scope, packageId), dependencies(scope, packageId),
                result.getTimestamp("created_at").toInstant(), result.getLong("created_by"));
    }

    private List<Asset> assets(Scope scope, UUID packageId) {
        return jdbc.query("""
                SELECT asset_key, asset_type, asset_id, asset_version,
                       content_sha256, rollback_disposition, external_side_effects
                  FROM apr_deployment_assets
                 WHERE tenant_id = :tenant AND management_resource_set_key = :scope
                   AND package_id = :id ORDER BY asset_key
                """, params(scope).addValue("id", packageId), (result, row) -> new Asset(
                result.getString("asset_key"), AssetType.valueOf(result.getString("asset_type")),
                result.getObject("asset_id", UUID.class), result.getString("asset_version"),
                result.getString("content_sha256"),
                RollbackDisposition.valueOf(result.getString("rollback_disposition")),
                result.getBoolean("external_side_effects")));
    }

    private List<Dependency> dependencies(Scope scope, UUID packageId) {
        return jdbc.query("""
                SELECT asset_key, depends_on_asset_key, required_sha256, optional
                  FROM apr_deployment_dependencies
                 WHERE tenant_id = :tenant AND management_resource_set_key = :scope
                   AND package_id = :id ORDER BY asset_key, depends_on_asset_key
                """, params(scope).addValue("id", packageId), (result, row) -> new Dependency(
                result.getString("asset_key"), result.getString("depends_on_asset_key"),
                result.getString("required_sha256"), result.getBoolean("optional")));
    }

    private Promotion promotion(ResultSet result, int row) throws SQLException {
        return new Promotion(
                result.getObject("promotion_id", UUID.class),
                result.getObject("package_id", UUID.class),
                Environment.valueOf(result.getString("source_environment")),
                Environment.valueOf(result.getString("target_environment")),
                result.getString("status"), result.getLong("maker_user_id"),
                result.getObject("checker_user_id", Long.class),
                result.getString("review_comment"), instant(result, "scheduled_for"),
                instant(result, "activation_started_at"), instant(result, "completed_at"),
                result.getLong("version"), result.getTimestamp("updated_at").toInstant());
    }

    private void upsertDevelopmentHead(Scope scope, UUID packageId, Instant now) {
        activateHead(scope, Environment.DEVELOPMENT, packageId, now);
    }

    private MapSqlParameterSource params(Scope scope) {
        return new MapSqlParameterSource()
                .addValue("tenant", scope.tenantId())
                .addValue("scope", scope.resourceSetKey());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static final String PROMOTION_SELECT = """
            SELECT promotion_id, package_id, source_environment, target_environment,
                   status, maker_user_id, checker_user_id, review_comment,
                   scheduled_for, activation_started_at, completed_at,
                   version, updated_at
              FROM apr_deployment_promotions promotion
             WHERE promotion.tenant_id = :tenant
               AND promotion.management_resource_set_key = :scope
            """;
}
