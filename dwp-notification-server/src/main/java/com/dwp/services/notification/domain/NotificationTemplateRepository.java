package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateContent;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateDraftRequest;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateRevision;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationTemplateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationTemplateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProviderVariant> providerVariants(long tenantId) {
        return jdbc.query("""
                SELECT DISTINCT ON (
                           type_version.type_version_id, template.channel, template.locale)
                       type_version.type_version_id,
                       type.type_key,
                       type.owner_app_key,
                       template.channel,
                       template.locale,
                       template.title_template,
                       template.preview_template,
                       template.body_template,
                       COALESCE(template.action_payload ->> 'label', '') AS action_label,
                       concat_ws(' ', template.title_template, template.preview_template,
                           template.body_template, template.action_payload::text) AS variable_material
                  FROM ntf_notification_types type
                  JOIN ntf_notification_type_versions type_version
                    ON type_version.type_id = type.type_id
                  JOIN ntf_template_versions template
                    ON template.type_version_id = type_version.type_version_id
                 WHERE type.lifecycle_state = 'ACTIVE'
                   AND type_version.lifecycle_state = 'ACTIVE'
                   AND template.state = 'PUBLISHED'
                   AND (type.tenant_id IS NULL OR type.tenant_id = :tenantId)
                 ORDER BY type_version.type_version_id, template.channel, template.locale,
                          template.version DESC
                """, new MapSqlParameterSource("tenantId", tenantId),
                (resultSet, rowNumber) -> new ProviderVariant(
                        resultSet.getObject("type_version_id", UUID.class),
                        resultSet.getString("type_key"),
                        resultSet.getString("owner_app_key"),
                        resultSet.getString("channel"),
                        resultSet.getString("locale"),
                        new TemplateContent(
                                resultSet.getString("title_template"),
                                resultSet.getString("preview_template"),
                                resultSet.getString("body_template"),
                                resultSet.getString("action_label")),
                        resultSet.getString("variable_material")));
    }

    public List<TemplateRevision> revisions(long tenantId) {
        return jdbc.query("""
                SELECT revision.template_revision_id,
                       revision.type_version_id,
                       type.type_key,
                       type.owner_app_key,
                       revision.channel,
                       revision.locale,
                       revision.state,
                       revision.revision,
                       revision.title_template,
                       revision.preview_template,
                       revision.body_template,
                       revision.action_label,
                       revision.checksum,
                       revision.change_reason,
                       revision.created_by,
                       revision.approved_by,
                       revision.approved_at,
                       revision.approval_reason,
                       revision.created_at
                  FROM ntf_tenant_template_revisions revision
                  JOIN ntf_notification_type_versions type_version
                    ON type_version.type_version_id = revision.type_version_id
                  JOIN ntf_notification_types type ON type.type_id = type_version.type_id
                 WHERE revision.tenant_id = :tenantId
                 ORDER BY revision.type_version_id, revision.channel, revision.locale,
                          revision.revision DESC
                """, new MapSqlParameterSource("tenantId", tenantId),
                (resultSet, rowNumber) -> revision(resultSet));
    }

    public Optional<ProviderVariant> providerVariant(
            long tenantId,
            UUID typeVersionId,
            String channel,
            String locale) {
        return providerVariants(tenantId).stream()
                .filter(item -> item.typeVersionId().equals(typeVersionId)
                        && item.channel().equals(channel)
                        && item.locale().equals(locale))
                .findFirst();
    }

    public long latestRevision(
            long tenantId,
            UUID typeVersionId,
            String channel,
            String locale) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(revision), 0)
                  FROM ntf_tenant_template_revisions
                 WHERE tenant_id = :tenantId
                   AND type_version_id = :typeVersionId
                   AND channel = :channel
                   AND locale = :locale
                """, identity(tenantId, typeVersionId, channel, locale), Long.class);
        return value == null ? 0 : value;
    }

    public TemplateRevision createDraft(
            long tenantId,
            long userId,
            TemplateDraftRequest request,
            int revision,
            String checksum) {
        UUID revisionId = UUID.randomUUID();
        UUID supersedes = jdbc.query("""
                SELECT template_revision_id
                  FROM ntf_tenant_template_revisions
                 WHERE tenant_id = :tenantId
                   AND type_version_id = :typeVersionId
                   AND channel = :channel
                   AND locale = :locale
                   AND state = 'PUBLISHED'
                 ORDER BY revision DESC
                 LIMIT 1
                """, identity(
                        tenantId, request.typeVersionId(), request.channel(), request.locale()),
                resultSet -> resultSet.next()
                        ? resultSet.getObject("template_revision_id", UUID.class)
                        : null);
        jdbc.update("""
                INSERT INTO ntf_tenant_template_revisions (
                    template_revision_id, tenant_id, type_version_id, channel, locale,
                    revision, title_template, preview_template, body_template, action_label,
                    state, checksum, change_reason, created_by, supersedes_revision_id)
                VALUES (
                    :revisionId, :tenantId, :typeVersionId, :channel, :locale,
                    :revision, :title, :preview, :body, :actionLabel,
                    'DRAFT', :checksum, :changeReason, :createdBy, :supersedes)
                """, identity(tenantId, request.typeVersionId(), request.channel(), request.locale())
                .addValue("revisionId", revisionId)
                .addValue("revision", revision)
                .addValue("title", request.title().trim())
                .addValue("preview", request.preview() == null ? "" : request.preview().trim())
                .addValue("body", request.body().trim())
                .addValue("actionLabel", request.actionLabel().trim())
                .addValue("checksum", checksum)
                .addValue("changeReason", request.changeReason().trim())
                .addValue("createdBy", userId)
                .addValue("supersedes", supersedes));
        return revision(tenantId, revisionId).orElseThrow();
    }

    public Optional<TemplateRevision> revision(long tenantId, UUID revisionId) {
        return jdbc.query("""
                SELECT revision.template_revision_id,
                       revision.type_version_id,
                       type.type_key,
                       type.owner_app_key,
                       revision.channel,
                       revision.locale,
                       revision.state,
                       revision.revision,
                       revision.title_template,
                       revision.preview_template,
                       revision.body_template,
                       revision.action_label,
                       revision.checksum,
                       revision.change_reason,
                       revision.created_by,
                       revision.approved_by,
                       revision.approved_at,
                       revision.approval_reason,
                       revision.created_at
                  FROM ntf_tenant_template_revisions revision
                  JOIN ntf_notification_type_versions type_version
                    ON type_version.type_version_id = revision.type_version_id
                  JOIN ntf_notification_types type ON type.type_id = type_version.type_id
                 WHERE revision.tenant_id = :tenantId
                   AND revision.template_revision_id = :revisionId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("revisionId", revisionId),
                (resultSet, rowNumber) -> revision(resultSet)).stream().findFirst();
    }

    public boolean publish(
            long tenantId,
            long approverId,
            UUID revisionId,
            int expectedRevision,
            String reason) {
        return jdbc.update("""
                UPDATE ntf_tenant_template_revisions
                   SET state = 'PUBLISHED',
                       approved_by = :approvedBy,
                       approved_at = CURRENT_TIMESTAMP,
                       approval_reason = :approvalReason
                 WHERE tenant_id = :tenantId
                   AND template_revision_id = :revisionId
                   AND revision = :expectedRevision
                   AND state = 'DRAFT'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("revisionId", revisionId)
                .addValue("expectedRevision", expectedRevision)
                .addValue("approvedBy", approverId)
                .addValue("approvalReason", reason.trim())) == 1;
    }

    public boolean retireDraft(long tenantId, UUID revisionId, int expectedRevision) {
        return jdbc.update("""
                UPDATE ntf_tenant_template_revisions
                   SET state = 'RETIRED'
                 WHERE tenant_id = :tenantId
                   AND template_revision_id = :revisionId
                   AND revision = :expectedRevision
                   AND state = 'DRAFT'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("revisionId", revisionId)
                .addValue("expectedRevision", expectedRevision)) == 1;
    }

    private MapSqlParameterSource identity(
            long tenantId,
            UUID typeVersionId,
            String channel,
            String locale) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("typeVersionId", typeVersionId)
                .addValue("channel", channel)
                .addValue("locale", locale);
    }

    private TemplateRevision revision(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        Timestamp approvedAt = resultSet.getTimestamp("approved_at");
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new TemplateRevision(
                resultSet.getObject("template_revision_id", UUID.class),
                resultSet.getObject("type_version_id", UUID.class),
                resultSet.getString("type_key"),
                resultSet.getString("owner_app_key"),
                resultSet.getString("channel"),
                resultSet.getString("locale"),
                resultSet.getString("state"),
                resultSet.getInt("revision"),
                new TemplateContent(
                        resultSet.getString("title_template"),
                        resultSet.getString("preview_template"),
                        resultSet.getString("body_template"),
                        resultSet.getString("action_label")),
                resultSet.getString("checksum"),
                resultSet.getString("change_reason"),
                (Long) resultSet.getObject("created_by"),
                (Long) resultSet.getObject("approved_by"),
                approvedAt == null ? null : approvedAt.toInstant(),
                resultSet.getString("approval_reason"),
                Integer.toString(resultSet.getInt("revision")),
                createdAt.toInstant());
    }

    public record ProviderVariant(
            UUID typeVersionId,
            String typeKey,
            String appKey,
            String channel,
            String locale,
            TemplateContent content,
            String variableMaterial) {
    }
}
