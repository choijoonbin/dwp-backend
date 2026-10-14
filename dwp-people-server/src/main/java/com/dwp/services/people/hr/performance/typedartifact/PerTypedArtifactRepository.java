package com.dwp.services.people.hr.performance.typedartifact;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class PerTypedArtifactRepository {

    private final NamedParameterJdbcTemplate jdbc;

    PerTypedArtifactRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenantId AS VARCHAR), TRUE)",
                new MapSqlParameterSource("tenantId", tenantId), String.class);
    }

    long nextRevision(
            long tenantId,
            String ownerType,
            UUID ownerId,
            PerArtifactType artifactType) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(artifact_revision), 0) + 1
                  FROM hris_performance.prf_per_artifact_versions
                 WHERE tenant_id = :tenantId
                   AND owner_aggregate_type = :ownerType
                   AND owner_aggregate_public_id = :ownerId
                   AND artifact_type = :artifactType
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("ownerType", ownerType)
                .addValue("ownerId", ownerId)
                .addValue("artifactType", artifactType.name()), Long.class);
        return value == null ? 1 : value;
    }

    void insert(
            long tenantId,
            UUID publicId,
            PerArtifactType artifactType,
            long revision,
            String ownerType,
            UUID ownerId,
            String digest,
            byte[] encryptedContent,
            UUID encryptionKeyVersionId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            UUID createdBy) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_per_artifact_versions (
                    tenant_id, public_id, artifact_type, schema_version,
                    artifact_revision, owner_aggregate_type,
                    owner_aggregate_public_id, canonical_content_digest,
                    encrypted_content, encryption_key_version_public_id,
                    effective_from, effective_to, created_at, created_by)
                VALUES (
                    :tenantId, :publicId, :artifactType, :schemaVersion,
                    :revision, :ownerType, :ownerId, :digest,
                    :encryptedContent, :keyVersionId,
                    :effectiveFrom, :effectiveTo, CURRENT_TIMESTAMP, :createdBy)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("publicId", publicId)
                .addValue("artifactType", artifactType.name())
                .addValue("schemaVersion", PerArtifactType.SCHEMA_VERSION)
                .addValue("revision", revision)
                .addValue("ownerType", ownerType)
                .addValue("ownerId", ownerId)
                .addValue("digest", digest)
                .addValue("encryptedContent", encryptedContent)
                .addValue("keyVersionId", encryptionKeyVersionId)
                .addValue("effectiveFrom", Date.valueOf(effectiveFrom))
                .addValue("effectiveTo",
                        effectiveTo == null ? null : Date.valueOf(effectiveTo))
                .addValue("createdBy", createdBy));
    }

    Optional<StoredArtifact> find(long tenantId, UUID publicId) {
        List<StoredArtifact> rows = jdbc.query("""
                SELECT public_id, artifact_type, schema_version, artifact_revision,
                       owner_aggregate_type, owner_aggregate_public_id,
                       canonical_content_digest, encrypted_content,
                       encryption_key_version_public_id, effective_from,
                       effective_to, created_at, created_by
                  FROM hris_performance.prf_per_artifact_versions
                 WHERE tenant_id = :tenantId AND public_id = :publicId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("publicId", publicId), (rs, rowNum) -> new StoredArtifact(
                rs.getObject("public_id", UUID.class),
                rs.getString("artifact_type"),
                rs.getInt("schema_version"),
                rs.getLong("artifact_revision"),
                rs.getString("owner_aggregate_type"),
                rs.getObject("owner_aggregate_public_id", UUID.class),
                rs.getString("canonical_content_digest"),
                rs.getBytes("encrypted_content"),
                rs.getObject("encryption_key_version_public_id", UUID.class),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class),
                instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class)));
        return rows.stream().findFirst();
    }

    List<StoredArtifact> findAll(long tenantId, List<UUID> publicIds) {
        if (publicIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT public_id, artifact_type, schema_version, artifact_revision,
                       owner_aggregate_type, owner_aggregate_public_id,
                       canonical_content_digest, encrypted_content,
                       encryption_key_version_public_id, effective_from,
                       effective_to, created_at, created_by
                  FROM hris_performance.prf_per_artifact_versions
                 WHERE tenant_id = :tenantId
                   AND public_id = ANY(
                       CAST(string_to_array(:publicIds, ',') AS UUID[]))
                 ORDER BY public_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("publicIds", publicIds.stream()
                        .map(UUID::toString)
                        .collect(java.util.stream.Collectors.joining(","))),
                this::storedArtifact);
    }

    private StoredArtifact storedArtifact(
            java.sql.ResultSet rs,
            int rowNum) throws java.sql.SQLException {
        return new StoredArtifact(
                rs.getObject("public_id", UUID.class),
                rs.getString("artifact_type"),
                rs.getInt("schema_version"),
                rs.getLong("artifact_revision"),
                rs.getString("owner_aggregate_type"),
                rs.getObject("owner_aggregate_public_id", UUID.class),
                rs.getString("canonical_content_digest"),
                rs.getBytes("encrypted_content"),
                rs.getObject("encryption_key_version_public_id", UUID.class),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class),
                instant(rs, "created_at"),
                rs.getObject("created_by", UUID.class));
    }

    private static Instant instant(
            java.sql.ResultSet rs,
            String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    record StoredArtifact(
            UUID publicId,
            String artifactType,
            int schemaVersion,
            long revision,
            String ownerAggregateType,
            UUID ownerAggregatePublicId,
            String digest,
            byte[] encryptedContent,
            UUID encryptionKeyVersionPublicId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant createdAt,
            UUID createdBy) {
        StoredArtifact {
            encryptedContent = encryptedContent.clone();
        }

        @Override
        public byte[] encryptedContent() {
            return encryptedContent.clone();
        }
    }
}
