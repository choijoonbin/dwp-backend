package com.dwp.services.provider.resourcegovernance;

import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AppendArtifactEvidenceRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactReviewDecisionRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.AssessArtifactCompatibilityRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactManifestRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactRolloutPlanRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactEvidenceRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactReviewRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.PlanRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ArtifactGovernanceJdbcRepository implements ArtifactGovernancePersistence<
        ArtifactRow, ArtifactReviewRow, PlanRow, ArtifactEvidenceRow> {
    private static final String ARTIFACT_SELECT = """
            SELECT artifact.artifact_id, artifact.product_key, artifact.artifact_version,
                   artifact.artifact_type, artifact.manifest_schema_version, artifact.manifest,
                   artifact.compatibility_policy, artifact.declared_digest, artifact.lifecycle_state,
                   artifact.compatibility_state, artifact.compatibility_evidence,
                   artifact.signature_state, artifact.distribution_state, artifact.created_by,
                   artifact.updated_by, artifact.created_at, artifact.updated_at, artifact.version
              FROM prv_product_artifact_manifests artifact
            """;

    private static final String PLAN_SELECT = """
            SELECT plan.rollout_plan_id, plan.artifact_id, artifact.product_key,
                   artifact.artifact_version, plan.name, plan.target_scope, plan.stages,
                   plan.rollback_plan, plan.rollback_feasibility, plan.lifecycle_state,
                   plan.executor_state, plan.reason, plan.requested_by, plan.approved_by,
                   plan.submitted_at, plan.approved_at, plan.decision_reason, plan.version,
                   plan.created_at, plan.updated_at
              FROM prv_artifact_rollout_plans plan
              JOIN prv_product_artifact_manifests artifact
                ON artifact.artifact_id = plan.artifact_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ArtifactGovernanceJdbcRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ArtifactRow> artifacts(int fetchLimit) {
        return jdbc.query(ARTIFACT_SELECT
                + " ORDER BY artifact.created_at DESC, artifact.product_key,"
                + " artifact.artifact_version, artifact.artifact_id DESC"
                + " LIMIT :fetchLimit",
                new MapSqlParameterSource("fetchLimit", fetchLimit), this::artifactRow);
    }

    public Optional<ArtifactRow> artifact(UUID artifactId) {
        return jdbc.query(ARTIFACT_SELECT + " WHERE artifact.artifact_id = :artifactId",
                new MapSqlParameterSource("artifactId", artifactId), this::artifactRow)
                .stream().findFirst();
    }

    public Optional<ArtifactRow> lockArtifact(UUID artifactId) {
        return jdbc.query(ARTIFACT_SELECT + " WHERE artifact.artifact_id = :artifactId FOR UPDATE",
                new MapSqlParameterSource("artifactId", artifactId), this::artifactRow)
                .stream().findFirst();
    }

    public ArtifactRow createArtifact(
            UUID artifactId,
            CreateArtifactManifestRequest request,
            Long actorId) {
        jdbc.update("""
                INSERT INTO prv_product_artifact_manifests (
                    artifact_id, product_key, artifact_version, artifact_type,
                    manifest_schema_version, manifest, compatibility_policy, declared_digest,
                    created_by, updated_by)
                VALUES (
                    :artifactId, :productKey, :artifactVersion, :artifactType,
                    :schemaVersion, CAST(:manifest AS JSONB), CAST(:compatibilityPolicy AS JSONB),
                    :declaredDigest, :actorId, :actorId)
                """, new MapSqlParameterSource("artifactId", artifactId)
                .addValue("productKey", request.productKey())
                .addValue("artifactVersion", request.artifactVersion())
                .addValue("artifactType", request.artifactType())
                .addValue("schemaVersion", request.manifestSchemaVersion())
                .addValue("manifest", json(request.manifest()))
                .addValue("compatibilityPolicy", json(request.compatibilityPolicy()))
                .addValue("declaredDigest", request.declaredDigest())
                .addValue("actorId", actorId));
        return artifact(artifactId).orElseThrow();
    }

    public boolean assessCompatibility(
            UUID artifactId,
            AssessArtifactCompatibilityRequest request,
            Long actorId) {
        return jdbc.update("""
                UPDATE prv_product_artifact_manifests
                   SET compatibility_state = :compatibilityState,
                       compatibility_evidence = CAST(:evidence AS JSONB),
                       updated_by = :actorId, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE artifact_id = :artifactId
                   AND lifecycle_state IN ('DRAFT', 'REVIEW_REQUIRED')
                   AND version = :version
                """, new MapSqlParameterSource("artifactId", artifactId)
                .addValue("version", request.version())
                .addValue("compatibilityState", request.compatibilityState())
                .addValue("evidence", json(request.evidence()))
                .addValue("actorId", actorId)) == 1;
    }

    public boolean submitArtifact(UUID artifactId, long version, Long actorId) {
        return jdbc.update("""
                UPDATE prv_product_artifact_manifests
                   SET lifecycle_state = 'REVIEW_REQUIRED', updated_by = :actorId,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE artifact_id = :artifactId
                   AND lifecycle_state = 'DRAFT'
                   AND compatibility_state = 'COMPATIBLE'
                   AND version = :version
                """, new MapSqlParameterSource("artifactId", artifactId)
                .addValue("version", version)
                .addValue("actorId", actorId)) == 1;
    }

    public boolean decideArtifact(
            UUID artifactId,
            ArtifactReviewDecisionRequest request,
            Long actorId) {
        String nextState = "APPROVED".equals(request.decision()) ? "APPROVED" : "DRAFT";
        int changed = jdbc.update("""
                UPDATE prv_product_artifact_manifests
                   SET lifecycle_state = :nextState, updated_by = :actorId,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE artifact_id = :artifactId
                   AND lifecycle_state = 'REVIEW_REQUIRED'
                   AND created_by <> :actorId
                   AND version = :version
                   AND (:decision <> 'APPROVED' OR compatibility_state = 'COMPATIBLE')
                """, new MapSqlParameterSource("artifactId", artifactId)
                .addValue("version", request.version())
                .addValue("decision", request.decision())
                .addValue("nextState", nextState)
                .addValue("actorId", actorId));
        if (changed != 1) return false;
        jdbc.update("""
                INSERT INTO prv_product_artifact_reviews (
                    review_id, artifact_id, decision, reason, evidence, reviewed_by)
                VALUES (
                    :reviewId, :artifactId, :decision, :reason, CAST(:evidence AS JSONB), :actorId)
                """, new MapSqlParameterSource("reviewId", UUID.randomUUID())
                .addValue("artifactId", artifactId)
                .addValue("decision", request.decision())
                .addValue("reason", request.reason())
                .addValue("evidence", json(request.evidence()))
                .addValue("actorId", actorId));
        return true;
    }

    public List<ArtifactReviewRow> reviews(UUID artifactId, int fetchLimit) {
        return jdbc.query("""
                SELECT review_id, artifact_id, decision, reason, evidence, reviewed_by, reviewed_at
                  FROM prv_product_artifact_reviews
                 WHERE artifact_id = :artifactId
                 ORDER BY reviewed_at DESC, review_id DESC
                 LIMIT :fetchLimit
                """, new MapSqlParameterSource("artifactId", artifactId)
                .addValue("fetchLimit", fetchLimit), this::reviewRow);
    }

    public List<PlanRow> plans(int fetchLimit) {
        return jdbc.query(PLAN_SELECT
                + " ORDER BY plan.updated_at DESC, plan.created_at DESC,"
                + " plan.rollout_plan_id DESC LIMIT :fetchLimit",
                new MapSqlParameterSource("fetchLimit", fetchLimit),
                this::planRow);
    }

    public Optional<PlanRow> plan(UUID planId) {
        return jdbc.query(PLAN_SELECT + " WHERE plan.rollout_plan_id = :planId",
                new MapSqlParameterSource("planId", planId), this::planRow).stream().findFirst();
    }

    public Optional<PlanRow> lockPlan(UUID planId) {
        return jdbc.query(PLAN_SELECT + " WHERE plan.rollout_plan_id = :planId FOR UPDATE",
                new MapSqlParameterSource("planId", planId), this::planRow).stream().findFirst();
    }

    public PlanRow createPlan(
            UUID planId,
            CreateArtifactRolloutPlanRequest request,
            Long actorId) {
        jdbc.update("""
                INSERT INTO prv_artifact_rollout_plans (
                    rollout_plan_id, artifact_id, name, target_scope, stages, rollback_plan,
                    rollback_feasibility, reason, requested_by)
                VALUES (
                    :planId, :artifactId, :name, CAST(:targetScope AS JSONB), CAST(:stages AS JSONB),
                    CAST(:rollbackPlan AS JSONB), :rollbackFeasibility, :reason, :actorId)
                """, new MapSqlParameterSource("planId", planId)
                .addValue("artifactId", request.artifactId())
                .addValue("name", request.name())
                .addValue("targetScope", json(request.targetScope()))
                .addValue("stages", json(request.stages()))
                .addValue("rollbackPlan", request.rollbackPlan() == null ? null : json(request.rollbackPlan()))
                .addValue("rollbackFeasibility", request.rollbackFeasibility())
                .addValue("reason", request.reason())
                .addValue("actorId", actorId));
        return plan(planId).orElseThrow();
    }

    public boolean submitPlan(UUID planId, long version) {
        return jdbc.update("""
                UPDATE prv_artifact_rollout_plans
                   SET lifecycle_state = 'PENDING_APPROVAL', submitted_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE rollout_plan_id = :planId
                   AND lifecycle_state = 'DRAFT'
                   AND version = :version
                """, new MapSqlParameterSource("planId", planId)
                .addValue("version", version)) == 1;
    }

    public boolean decidePlan(
            UUID planId,
            long version,
            String decision,
            String reason,
            Long actorId) {
        String nextState = "APPROVED".equals(decision) ? "APPROVED" : "REJECTED";
        return jdbc.update("""
                UPDATE prv_artifact_rollout_plans
                   SET lifecycle_state = :nextState, approved_by = :actorId,
                       approved_at = CURRENT_TIMESTAMP, decision_reason = :reason,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE rollout_plan_id = :planId
                   AND lifecycle_state = 'PENDING_APPROVAL'
                   AND requested_by <> :actorId
                   AND version = :version
                """, new MapSqlParameterSource("planId", planId)
                .addValue("version", version)
                .addValue("nextState", nextState)
                .addValue("actorId", actorId)
                .addValue("reason", reason)) == 1;
    }

    public boolean markPlanReady(UUID planId, long version) {
        return jdbc.update("""
                UPDATE prv_artifact_rollout_plans
                   SET lifecycle_state = 'READY', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE rollout_plan_id = :planId
                   AND lifecycle_state = 'APPROVED'
                   AND approved_by IS NOT NULL
                   AND version = :version
                """, new MapSqlParameterSource("planId", planId)
                .addValue("version", version)) == 1;
    }

    public ArtifactEvidenceRow appendEvidence(
            UUID evidenceId,
            UUID planId,
            AppendArtifactEvidenceRequest request,
            Long actorId) {
        jdbc.update("""
                INSERT INTO prv_artifact_rollout_evidence (
                    evidence_id, rollout_plan_id, evidence_type, evidence_state, evidence,
                    recorded_by)
                VALUES (
                    :evidenceId, :planId, :evidenceType, :evidenceState, CAST(:evidence AS JSONB),
                    :actorId)
                """, new MapSqlParameterSource("evidenceId", evidenceId)
                .addValue("planId", planId)
                .addValue("evidenceType", request.evidenceType())
                .addValue("evidenceState", request.evidenceState())
                .addValue("evidence", json(request.evidence()))
                .addValue("actorId", actorId));
        return evidenceById(evidenceId).orElseThrow();
    }

    public List<ArtifactEvidenceRow> evidence(UUID planId, int fetchLimit) {
        return jdbc.query("""
                SELECT evidence_id, rollout_plan_id, evidence_type, evidence_state, evidence,
                       source, recorded_by, recorded_at
                  FROM prv_artifact_rollout_evidence
                 WHERE rollout_plan_id = :planId
                 ORDER BY recorded_at DESC, evidence_id DESC
                 LIMIT :fetchLimit
                """, new MapSqlParameterSource("planId", planId)
                .addValue("fetchLimit", fetchLimit), this::evidenceRow);
    }

    public List<ArtifactEvidenceRow> readinessEvidence(UUID planId) {
        return jdbc.query("""
                SELECT DISTINCT ON (evidence_type, evidence_state)
                       evidence_id, rollout_plan_id, evidence_type, evidence_state, evidence,
                       source, recorded_by, recorded_at
                  FROM prv_artifact_rollout_evidence
                 WHERE rollout_plan_id = :planId
                   AND (evidence_state = 'FAILED'
                        OR (evidence_type IN ('PRE_FLIGHT', 'ROLLBACK_FEASIBILITY')
                            AND evidence_state = 'PASSED'))
                 ORDER BY evidence_type, evidence_state, recorded_at DESC, evidence_id DESC
                """, new MapSqlParameterSource("planId", planId), this::evidenceRow);
    }

    private Optional<ArtifactEvidenceRow> evidenceById(UUID evidenceId) {
        return jdbc.query("""
                SELECT evidence_id, rollout_plan_id, evidence_type, evidence_state, evidence,
                       source, recorded_by, recorded_at
                  FROM prv_artifact_rollout_evidence
                 WHERE evidence_id = :evidenceId
                """, new MapSqlParameterSource("evidenceId", evidenceId), this::evidenceRow)
                .stream().findFirst();
    }
    private ArtifactRow artifactRow(ResultSet result, int ignored) throws SQLException {
        return new ArtifactRow(
                result.getObject("artifact_id", UUID.class),
                result.getString("product_key"),
                result.getString("artifact_version"),
                result.getString("artifact_type"),
                result.getInt("manifest_schema_version"),
                node(result.getString("manifest")),
                node(result.getString("compatibility_policy")),
                result.getString("declared_digest"),
                result.getString("lifecycle_state"),
                result.getString("compatibility_state"),
                node(result.getString("compatibility_evidence")),
                result.getString("signature_state"),
                result.getString("distribution_state"),
                result.getLong("created_by"),
                result.getLong("updated_by"),
                result.getObject("created_at", Instant.class),
                result.getObject("updated_at", Instant.class),
                result.getLong("version"));
    }

    private PlanRow planRow(ResultSet result, int ignored) throws SQLException {
        return new PlanRow(
                result.getObject("rollout_plan_id", UUID.class),
                result.getObject("artifact_id", UUID.class),
                result.getString("product_key"),
                result.getString("artifact_version"),
                result.getString("name"),
                node(result.getString("target_scope")),
                node(result.getString("stages")),
                nullableNode(result.getString("rollback_plan")),
                result.getString("rollback_feasibility"),
                result.getString("lifecycle_state"),
                result.getString("executor_state"),
                result.getString("reason"),
                result.getLong("requested_by"),
                result.getObject("approved_by", Long.class),
                result.getObject("submitted_at", Instant.class),
                result.getObject("approved_at", Instant.class),
                result.getString("decision_reason"),
                result.getLong("version"),
                result.getObject("created_at", Instant.class),
                result.getObject("updated_at", Instant.class));
    }

    private ArtifactReviewRow reviewRow(ResultSet result, int ignored) throws SQLException {
        return new ArtifactReviewRow(
                result.getObject("review_id", UUID.class),
                result.getObject("artifact_id", UUID.class),
                result.getString("decision"),
                result.getString("reason"),
                node(result.getString("evidence")),
                result.getLong("reviewed_by"),
                result.getObject("reviewed_at", Instant.class));
    }

    private ArtifactEvidenceRow evidenceRow(ResultSet result, int ignored) throws SQLException {
        return new ArtifactEvidenceRow(
                result.getObject("evidence_id", UUID.class),
                result.getObject("rollout_plan_id", UUID.class),
                result.getString("evidence_type"),
                result.getString("evidence_state"),
                node(result.getString("evidence")),
                result.getString("source"),
                result.getLong("recorded_by"),
                result.getObject("recorded_at", Instant.class));
    }

    private JsonNode node(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider resource-governance JSON is invalid.", exception);
        }
    }

    private JsonNode nullableNode(String value) {
        return value == null ? null : node(value);
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider resource-governance JSON cannot be serialized.", exception);
        }
    }
}
