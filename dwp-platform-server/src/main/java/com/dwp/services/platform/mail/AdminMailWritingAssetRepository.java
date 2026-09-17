package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.AdminMailWritingAssetDtos.*;

@Repository
class AdminMailWritingAssetRepository {

    private final JdbcTemplate jdbc;

    AdminMailWritingAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<OrganizationAsset> assets(long tenantId, AssetKind kind, String state) {
        if (kind == null) {
            return java.util.stream.Stream.concat(
                    assets(tenantId, AssetKind.TEMPLATE, state).stream(),
                    assets(tenantId, AssetKind.SIGNATURE, state).stream())
                    .sorted(java.util.Comparator
                            .comparing(OrganizationAsset::updatedAt).reversed()
                            .thenComparing(OrganizationAsset::assetId))
                    .toList();
        }
        String table = table(kind);
        return jdbc.query(select(kind) + """
                 WHERE asset.tenant_id = ?
                   AND asset.%s = 'ORGANIZATION'
                   AND (? = '' OR asset.publication_state = ?)
                 ORDER BY asset.updated_at DESC, asset.%s
                """.formatted(scopeColumn(kind), idColumn(kind)),
                (result, ignored) -> asset(result, kind), tenantId, state, state);
    }

    Optional<OrganizationAsset> asset(long tenantId, AssetKind kind, UUID assetId) {
        return jdbc.query(select(kind) + """
                 WHERE asset.tenant_id = ? AND asset.%s = ?
                   AND asset.%s = 'ORGANIZATION'
                """.formatted(idColumn(kind), scopeColumn(kind)),
                (result, ignored) -> asset(result, kind), tenantId, assetId)
                .stream().findFirst();
    }

    OrganizationAsset createDraft(
            long tenantId, long userId, AssetKind kind, DraftRequest request) {
        UUID assetId = UUID.randomUUID();
        UUID publicationKey = UUID.randomUUID();
        int publicationVersion = 1;
        if (request.supersedesId() != null) {
            OrganizationAsset predecessor = asset(
                    tenantId, kind, request.supersedesId()).orElseThrow();
            if (predecessor.publicationState() != PublicationState.PUBLISHED) {
                throw new IllegalStateException("Only a published asset can start a new version.");
            }
            publicationKey = predecessor.publicationKey();
            publicationVersion = Math.addExact(predecessor.publicationVersion(), 1);
        }
        if (kind == AssetKind.TEMPLATE) {
            jdbc.update("""
                    INSERT INTO mail_templates (
                        template_id, tenant_id, owner_user_id, template_scope,
                        display_name, subject_template, body_content, body_format,
                        mandatory_content, lifecycle_state, publication_key,
                        publication_version, publication_state, supersedes_id,
                        created_by, updated_by)
                    VALUES (?, ?, ?, 'ORGANIZATION', ?, ?, ?, ?, ?, 'ACTIVE',
                            ?, ?, 'DRAFT', ?, ?, ?)
                    """, assetId, tenantId, userId, request.name().trim(),
                    value(request.subject()), request.body().trim(), request.bodyFormat().name(),
                    request.mandatoryContent().trim(), publicationKey, publicationVersion,
                    request.supersedesId(), userId, userId);
        } else {
            jdbc.update("""
                    INSERT INTO mail_signatures (
                        signature_id, tenant_id, owner_user_id, signature_scope,
                        display_name, body_content, body_format, default_for_new,
                        default_for_reply, mandatory_content, lifecycle_state,
                        publication_key, publication_version, publication_state,
                        supersedes_id, created_by, updated_by)
                    VALUES (?, ?, ?, 'ORGANIZATION', ?, ?, ?, ?, ?, ?, 'ACTIVE',
                            ?, ?, 'DRAFT', ?, ?, ?)
                    """, assetId, tenantId, userId, request.name().trim(),
                    request.body().trim(), request.bodyFormat().name(),
                    request.defaultForNew(), request.defaultForReply(),
                    request.mandatoryContent().trim(), publicationKey, publicationVersion,
                    request.supersedesId(), userId, userId);
        }
        return asset(tenantId, kind, assetId).orElseThrow();
    }

    Optional<OrganizationAsset> updateDraft(
            long tenantId, long userId, AssetKind kind, UUID assetId, DraftRequest request) {
        int updated;
        if (kind == AssetKind.TEMPLATE) {
            updated = jdbc.update("""
                    UPDATE mail_templates
                       SET display_name = ?, subject_template = ?, body_content = ?,
                           body_format = ?, mandatory_content = ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND template_id = ?
                       AND template_scope = 'ORGANIZATION'
                       AND publication_state = 'DRAFT' AND version = ?
                    """, request.name().trim(), value(request.subject()), request.body().trim(),
                    request.bodyFormat().name(), request.mandatoryContent().trim(), userId,
                    tenantId, assetId, request.version());
        } else {
            updated = jdbc.update("""
                    UPDATE mail_signatures
                       SET display_name = ?, body_content = ?, body_format = ?,
                           default_for_new = ?, default_for_reply = ?, mandatory_content = ?,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE tenant_id = ? AND signature_id = ?
                       AND signature_scope = 'ORGANIZATION'
                       AND publication_state = 'DRAFT' AND version = ?
                    """, request.name().trim(), request.body().trim(), request.bodyFormat().name(),
                    request.defaultForNew(), request.defaultForReply(),
                    request.mandatoryContent().trim(), userId,
                    tenantId, assetId, request.version());
        }
        return updated == 1 ? asset(tenantId, kind, assetId) : Optional.empty();
    }

    Optional<OrganizationAsset> transition(
            long tenantId,
            long userId,
            AssetKind kind,
            UUID assetId,
            PublicationState from,
            PublicationState to,
            long version) {
        String actorColumns = switch (to) {
            case PENDING_APPROVAL -> "submitted_at = CURRENT_TIMESTAMP, submitted_by = ?,";
            case APPROVED -> "approved_at = CURRENT_TIMESTAMP, approved_by = ?,";
            case PUBLISHED -> "published_at = CURRENT_TIMESTAMP, published_by = ?,";
            case RETIRED -> "retired_at = CURRENT_TIMESTAMP, retired_by = ?,";
            case DRAFT -> throw new IllegalArgumentException("A transition cannot return to draft.");
        };
        int updated = jdbc.update("""
                UPDATE %s
                   SET publication_state = ?, %s version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND %s = ? AND %s = 'ORGANIZATION'
                   AND publication_state = ? AND version = ?
                """.formatted(table(kind), actorColumns, idColumn(kind), scopeColumn(kind)),
                to.name(), userId, userId, tenantId, assetId, from.name(), version);
        return updated == 1 ? asset(tenantId, kind, assetId) : Optional.empty();
    }

    void retirePublishedPredecessor(
            long tenantId, long userId, AssetKind kind, OrganizationAsset asset) {
        jdbc.update("""
                UPDATE %s
                   SET publication_state = 'RETIRED', retired_at = CURRENT_TIMESTAMP,
                       retired_by = ?, lifecycle_state = 'ARCHIVED',
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND publication_key = ?
                   AND %s <> ? AND %s = 'ORGANIZATION'
                   AND publication_state = 'PUBLISHED'
                """.formatted(table(kind), idColumn(kind), scopeColumn(kind)),
                userId, userId, tenantId, asset.publicationKey(), asset.assetId());
    }

    private String select(AssetKind kind) {
        if (kind == AssetKind.TEMPLATE) {
            return """
                    SELECT asset.template_id AS asset_id, asset.publication_key,
                           asset.publication_version, asset.publication_state,
                           asset.supersedes_id, asset.display_name,
                           asset.subject_template AS subject, asset.body_content,
                           asset.body_format, asset.mandatory_content,
                           FALSE AS default_for_new, FALSE AS default_for_reply,
                           asset.created_by, asset.approved_by, asset.submitted_at,
                           asset.approved_at, asset.published_at, asset.retired_at,
                           asset.version, asset.updated_at
                      FROM mail_templates asset
                    """;
        }
        return """
                SELECT asset.signature_id AS asset_id, asset.publication_key,
                       asset.publication_version, asset.publication_state,
                       asset.supersedes_id, asset.display_name, '' AS subject,
                       asset.body_content, asset.body_format, asset.mandatory_content,
                       asset.default_for_new, asset.default_for_reply,
                       asset.created_by, asset.approved_by, asset.submitted_at,
                       asset.approved_at, asset.published_at, asset.retired_at,
                       asset.version, asset.updated_at
                  FROM mail_signatures asset
                """;
    }

    private OrganizationAsset asset(ResultSet result, AssetKind kind) throws SQLException {
        return new OrganizationAsset(
                result.getObject("asset_id", UUID.class), kind,
                result.getObject("publication_key", UUID.class),
                result.getInt("publication_version"),
                PublicationState.valueOf(result.getString("publication_state")),
                result.getObject("supersedes_id", UUID.class),
                result.getString("display_name"), result.getString("subject"),
                result.getString("body_content"),
                MailWorkspaceDtos.BodyFormat.valueOf(result.getString("body_format")),
                result.getString("mandatory_content"),
                result.getBoolean("default_for_new"),
                result.getBoolean("default_for_reply"),
                result.getLong("created_by"), nullableLong(result, "approved_by"),
                result.getObject("submitted_at", OffsetDateTime.class),
                result.getObject("approved_at", OffsetDateTime.class),
                result.getObject("published_at", OffsetDateTime.class),
                result.getObject("retired_at", OffsetDateTime.class),
                result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private String table(AssetKind kind) {
        return kind == AssetKind.TEMPLATE ? "mail_templates" : "mail_signatures";
    }

    private String idColumn(AssetKind kind) {
        return kind == AssetKind.TEMPLATE ? "template_id" : "signature_id";
    }

    private String scopeColumn(AssetKind kind) {
        return kind == AssetKind.TEMPLATE ? "template_scope" : "signature_scope";
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }
}
