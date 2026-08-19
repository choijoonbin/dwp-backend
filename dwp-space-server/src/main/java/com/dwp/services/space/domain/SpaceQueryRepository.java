package com.dwp.services.space.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.space.security.SpaceRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class SpaceQueryRepository {

    private static final String EFFECTIVE_ROLE = """
            (
                SELECT membership.member_role
                  FROM spc_memberships membership
                 WHERE membership.tenant_id = space.tenant_id
                   AND membership.space_id = space.space_id
                   AND membership.lifecycle_state = 'ACTIVE'
                   AND membership.valid_from <= CURRENT_TIMESTAMP
                   AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                   AND ((membership.principal_type = 'USER' AND membership.principal_ref IN (:userRefs))
                        OR (membership.principal_type = 'GROUP' AND membership.principal_ref IN (:groups)))
                 ORDER BY CASE membership.member_role
                    WHEN 'OWNER' THEN 6 WHEN 'MODERATOR' THEN 5 WHEN 'EDITOR' THEN 4
                    WHEN 'CONTRIBUTOR' THEN 3 WHEN 'VIEWER' THEN 2 ELSE 1 END DESC
                 LIMIT 1
            )
            """;

    private static final String SPACE_SUMMARY_SELECT = """
            SELECT space.space_id, space.space_key, space.name_ko, space.name_en,
                   space.summary_ko, space.summary_en, space.purpose_type,
                   space.visibility, space.data_classification,
                   %s AS effective_role,
                   COALESCE((
                       SELECT COUNT(*)::INTEGER
                         FROM spc_memberships member_count
                        WHERE member_count.tenant_id = space.tenant_id
                          AND member_count.space_id = space.space_id
                          AND member_count.lifecycle_state = 'ACTIVE'
                   ), 0) AS member_count,
                   (SELECT COUNT(*)::INTEGER FROM spc_content_items content
                     WHERE content.tenant_id = space.tenant_id
                       AND content.space_id = space.space_id
                       AND content.lifecycle_state = 'PUBLISHED') AS content_count,
                   (SELECT COUNT(*)::INTEGER FROM spc_activity_events activity
                     WHERE activity.tenant_id = space.tenant_id
                       AND activity.space_id = space.space_id
                       AND activity.occurred_at > CURRENT_TIMESTAMP - INTERVAL '24 hours') AS unread_count,
                   space.icon_key, space.accent_token, space.cover_asset_url,
                   space.lifecycle_state, space.last_activity_at, space.version
              FROM spc_spaces space
            """.formatted(EFFECTIVE_ROLE);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SpaceQueryRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public SpaceDtos.HomeMetrics homeMetrics(SpaceRequestContext.Subject subject) {
        MapSqlParameterSource params = subjectParams(subject);
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE effective_role IS NOT NULL)::INTEGER AS my_spaces,
                       COUNT(*) FILTER (
                           WHERE visibility IN ('OPEN', 'REQUEST')
                             AND effective_role IS NULL)::INTEGER AS discoverable_spaces,
                       (SELECT COUNT(*)::INTEGER FROM spc_space_requests request
                         WHERE request.tenant_id = :tenantId
                           AND request.requester_user_id = :userId
                           AND request.status = 'PENDING') AS pending_requests,
                       (SELECT COUNT(*)::INTEGER FROM spc_publication_reviews review
                         WHERE review.tenant_id = :tenantId
                           AND review.status = 'PENDING') AS review_queue,
                       COALESCE(SUM(unread_count), 0)::INTEGER AS unread_signals
                  FROM (
                       SELECT space.visibility,
                              %s AS effective_role,
                              (SELECT COUNT(*) FROM spc_activity_events activity
                                WHERE activity.tenant_id = space.tenant_id
                                  AND activity.space_id = space.space_id
                                  AND activity.occurred_at > CURRENT_TIMESTAMP - INTERVAL '24 hours') AS unread_count
                         FROM spc_spaces space
                        WHERE space.tenant_id = :tenantId
                          AND space.lifecycle_state = 'ACTIVE'
                  ) visible
                """.formatted(EFFECTIVE_ROLE), params, (resultSet, rowNumber) ->
                new SpaceDtos.HomeMetrics(
                        resultSet.getInt("my_spaces"),
                        resultSet.getInt("discoverable_spaces"),
                        resultSet.getInt("pending_requests"),
                        resultSet.getInt("review_queue"),
                        resultSet.getInt("unread_signals")));
    }

    public List<SpaceDtos.SpaceSummary> spaces(
            SpaceRequestContext.Subject subject,
            String scope,
            String query,
            int limit) {
        MapSqlParameterSource params = subjectParams(subject)
                .addValue("query", "%" + (query == null ? "" : query.trim()) + "%")
                .addValue("limit", Math.max(1, Math.min(limit, 100)))
                .addValue("tenantAdmin", subject.tenantAdministrator());
        String scopePredicate = "MY".equalsIgnoreCase(scope)
                ? "effective_role IS NOT NULL"
                : "(effective_role IS NOT NULL OR visibility IN ('OPEN', 'REQUEST') OR :tenantAdmin)";
        return jdbc.query("""
                SELECT * FROM (
                    %s
                     WHERE space.tenant_id = :tenantId
                       AND space.lifecycle_state = 'ACTIVE'
                       AND (space.name_ko ILIKE :query OR space.name_en ILIKE :query
                            OR space.summary_ko ILIKE :query OR space.summary_en ILIKE :query)
                ) visible
                WHERE %s
                ORDER BY CASE WHEN effective_role IS NOT NULL THEN 0 ELSE 1 END,
                         last_activity_at DESC
                LIMIT :limit
                """.formatted(SPACE_SUMMARY_SELECT, scopePredicate), params, this::spaceSummary);
    }

    public SpaceDtos.SpaceSummary space(
            SpaceRequestContext.Subject subject,
            String spaceKey) {
        MapSqlParameterSource params = subjectParams(subject)
                .addValue("spaceKey", spaceKey)
                .addValue("tenantAdmin", subject.tenantAdministrator());
        List<SpaceDtos.SpaceSummary> results = jdbc.query("""
                SELECT * FROM (
                    %s
                     WHERE space.tenant_id = :tenantId
                       AND space.space_key = :spaceKey
                       AND space.lifecycle_state <> 'DELETED'
                ) visible
                WHERE effective_role IS NOT NULL OR visibility IN ('OPEN', 'REQUEST') OR :tenantAdmin
                """.formatted(SPACE_SUMMARY_SELECT), params, this::spaceSummary);
        if (results.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND, "Space was not found.");
        return results.getFirst();
    }

    public List<SpaceDtos.SpaceSummary> adminSpaces(
            SpaceRequestContext.Subject subject,
            String query,
            int limit) {
        MapSqlParameterSource params = subjectParams(subject)
                .addValue("query", "%" + (query == null ? "" : query.trim()) + "%")
                .addValue("limit", Math.max(1, Math.min(limit, 100)));
        return jdbc.query("""
                SELECT * FROM (
                    %s
                     WHERE space.tenant_id = :tenantId
                       AND space.lifecycle_state <> 'DELETED'
                       AND (space.name_ko ILIKE :query OR space.name_en ILIKE :query
                            OR space.summary_ko ILIKE :query OR space.summary_en ILIKE :query)
                ) managed
                ORDER BY last_activity_at DESC
                LIMIT :limit
                """.formatted(SPACE_SUMMARY_SELECT), params, this::spaceSummary);
    }

    public SpaceDtos.SpaceSummary adminSpace(
            SpaceRequestContext.Subject subject,
            String spaceKey) {
        List<SpaceDtos.SpaceSummary> results = jdbc.query("""
                %s
                 WHERE space.tenant_id = :tenantId
                   AND space.space_key = :spaceKey
                   AND space.lifecycle_state <> 'DELETED'
                """.formatted(SPACE_SUMMARY_SELECT), subjectParams(subject)
                .addValue("spaceKey", spaceKey), this::spaceSummary);
        if (results.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND, "Space was not found.");
        return results.getFirst();
    }

    public boolean hasActiveOwner(long tenantId, UUID spaceId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
                  FROM spc_memberships
                 WHERE tenant_id = :tenantId
                   AND space_id = :spaceId
                   AND member_role = 'OWNER'
                   AND lifecycle_state = 'ACTIVE'
                   AND valid_from <= CURRENT_TIMESTAMP
                   AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("spaceId", spaceId), Integer.class);
        return count != null && count > 0;
    }

    public Map<String, String> policies(long tenantId, UUID spaceId) {
        return jdbc.queryForObject("""
                SELECT content_policy, app_policy, ai_policy
                  FROM spc_spaces
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("spaceId", spaceId), (resultSet, rowNumber) -> Map.of(
                "contentPolicy", resultSet.getString("content_policy"),
                "appPolicy", resultSet.getString("app_policy"),
                "aiPolicy", resultSet.getString("ai_policy")));
    }

    public List<SpaceDtos.TemplateSummary> templates(long tenantId, boolean includeDrafts) {
        return jdbc.query("""
                SELECT template_id, template_key, name_ko, name_en,
                       description_ko, description_en, purpose_type, creation_mode,
                       default_visibility, default_data_classification,
                       icon_key, accent_token, lifecycle_state, current_version, version
                  FROM spc_templates
                 WHERE tenant_id = :tenantId
                   AND (:includeDrafts OR lifecycle_state = 'PUBLISHED')
                 ORDER BY CASE lifecycle_state WHEN 'PUBLISHED' THEN 0 ELSE 1 END,
                          purpose_type, name_en
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("includeDrafts", includeDrafts), this::templateSummary);
    }

    public List<SpaceDtos.ContentSummary> content(
            long tenantId,
            UUID spaceId,
            boolean includeUnpublished,
            int limit) {
        return jdbc.query("""
                SELECT content_id, content_type, title, summary, route,
                       data_classification, lifecycle_state, author_user_id,
                       author_name, current_revision, published_at, updated_at
                 FROM spc_content_items
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND (:includeUnpublished OR lifecycle_state = 'PUBLISHED')
                 ORDER BY CASE lifecycle_state WHEN 'IN_REVIEW' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                          updated_at DESC
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("spaceId", spaceId)
                .addValue("includeUnpublished", includeUnpublished)
                .addValue("limit", Math.max(1, Math.min(limit, 100))), this::contentSummary);
    }

    public List<SpaceDtos.AppBindingSummary> apps(long tenantId, UUID spaceId) {
        return jdbc.query("""
                SELECT binding_id, app_key, display_name_ko, display_name_en,
                       launch_target, icon_key, data_access_scope, lifecycle_state
                  FROM spc_app_bindings
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND lifecycle_state <> 'RETIRED'
                 ORDER BY display_name_en
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("spaceId", spaceId), (resultSet, rowNumber) ->
                new SpaceDtos.AppBindingSummary(
                        resultSet.getObject("binding_id", UUID.class),
                        resultSet.getString("app_key"),
                        resultSet.getString("display_name_ko"),
                        resultSet.getString("display_name_en"),
                        resultSet.getString("launch_target"),
                        resultSet.getString("icon_key"),
                        resultSet.getString("data_access_scope"),
                        resultSet.getString("lifecycle_state")));
    }

    public List<SpaceDtos.ActivitySummary> activity(
            long tenantId,
            UUID spaceId,
            int limit) {
        String spaceFilter = spaceId == null ? "" : "AND activity.space_id = :spaceId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("spaceId", spaceId)
                .addValue("limit", Math.max(1, Math.min(limit, 100)));
        return jdbc.query("""
                SELECT activity.activity_id, space.space_key,
                       space.name_ko AS space_name_ko, space.name_en AS space_name_en,
                       activity.activity_type, activity.actor_type, activity.actor_name,
                       activity.object_type, activity.title_ko, activity.title_en,
                       activity.route, activity.occurred_at
                  FROM spc_activity_events activity
                  JOIN spc_spaces space
                    ON space.tenant_id = activity.tenant_id
                   AND space.space_id = activity.space_id
                 WHERE activity.tenant_id = :tenantId
                   %s
                 ORDER BY activity.occurred_at DESC
                 LIMIT :limit
                """.formatted(spaceFilter), params, this::activitySummary);
    }

    public List<SpaceDtos.MemberSummary> members(long tenantId, UUID spaceId) {
        return jdbc.query("""
                SELECT membership_id, principal_type, principal_ref, member_role,
                       membership_source, lifecycle_state, valid_from, valid_until, version
                  FROM spc_memberships
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                 ORDER BY CASE member_role WHEN 'OWNER' THEN 0 WHEN 'MODERATOR' THEN 1
                              WHEN 'EDITOR' THEN 2 WHEN 'CONTRIBUTOR' THEN 3 ELSE 4 END,
                          principal_ref
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("spaceId", spaceId), (resultSet, rowNumber) ->
                new SpaceDtos.MemberSummary(
                        resultSet.getObject("membership_id", UUID.class),
                        resultSet.getString("principal_type"),
                        resultSet.getString("principal_ref"),
                        resultSet.getString("member_role"),
                        resultSet.getString("membership_source"),
                        resultSet.getString("lifecycle_state"),
                        instant(resultSet, "valid_from"),
                        instant(resultSet, "valid_until"),
                        resultSet.getLong("version")));
    }

    public List<SpaceDtos.AccessRequestSummary> accessRequests(
            long tenantId,
            UUID spaceId,
            Long requesterUserId,
            String status) {
        String spacePredicate = spaceId == null ? "" : "AND request.space_id = :spaceId";
        String requesterPredicate = requesterUserId == null
                ? "" : "AND request.requester_user_id = :requesterUserId";
        String normalizedStatus = status == null ? "ALL" : status.toUpperCase();
        String statusPredicate = "ALL".equals(normalizedStatus)
                ? "" : "AND request.status = :status";
        return jdbc.query("""
                SELECT request.access_request_id, request.space_id,
                       space.space_key, space.name_ko, space.name_en,
                       request.requester_user_id, request.requester_name,
                       request.requested_role, request.justification,
                       request.decision_mode, request.status, request.decision_note,
                       request.created_at, request.decided_at, request.version
                  FROM spc_access_requests request
                  JOIN spc_spaces space
                    ON space.tenant_id = request.tenant_id
                   AND space.space_id = request.space_id
                 WHERE request.tenant_id = :tenantId
                   %s
                   %s
                   %s
                 ORDER BY CASE request.status WHEN 'PENDING' THEN 0 ELSE 1 END,
                          request.created_at DESC
                """.formatted(spacePredicate, requesterPredicate, statusPredicate),
                new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("spaceId", spaceId)
                .addValue("requesterUserId", requesterUserId)
                .addValue("status", normalizedStatus), (resultSet, rowNumber) ->
                new SpaceDtos.AccessRequestSummary(
                        resultSet.getObject("access_request_id", UUID.class),
                        resultSet.getObject("space_id", UUID.class),
                        resultSet.getString("space_key"),
                        resultSet.getString("name_ko"),
                        resultSet.getString("name_en"),
                        resultSet.getLong("requester_user_id"),
                        resultSet.getString("requester_name"),
                        resultSet.getString("requested_role"),
                        resultSet.getString("justification"),
                        resultSet.getString("decision_mode"),
                        resultSet.getString("status"),
                        resultSet.getString("decision_note"),
                        instant(resultSet, "created_at"),
                        instant(resultSet, "decided_at"),
                        resultSet.getLong("version")));
    }

    public List<SpaceDtos.RequestSummary> requests(
            long tenantId,
            Long requesterUserId,
            String status) {
        String requesterPredicate = requesterUserId == null
                ? "" : "AND request.requester_user_id = :requesterUserId";
        String normalizedStatus = status == null ? "ALL" : status.toUpperCase();
        String statusPredicate = "ALL".equals(normalizedStatus)
                ? "" : "AND request.status = :status";
        return jdbc.query("""
                SELECT request.request_id, request.template_id,
                       template.name_ko AS template_name_ko,
                       template.name_en AS template_name_en,
                       request.requester_user_id, request.requester_name,
                       request.requested_key, request.requested_name,
                       request.requested_summary, request.requested_visibility,
                       request.justification, request.decision_mode, request.risk_level,
                       request.policy_evidence, request.status, request.decision_note,
                       request.created_at, request.decided_at, request.version
                  FROM spc_space_requests request
                  JOIN spc_templates template
                    ON template.tenant_id = request.tenant_id
                   AND template.template_id = request.template_id
                 WHERE request.tenant_id = :tenantId
                   %s
                   %s
                 ORDER BY CASE request.risk_level
                            WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                            WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                          request.created_at DESC
                """.formatted(requesterPredicate, statusPredicate),
                new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("requesterUserId", requesterUserId)
                .addValue("status", normalizedStatus), this::requestSummary);
    }

    public List<SpaceDtos.PublicationReviewSummary> publicationReviews(long tenantId, String status) {
        return jdbc.query("""
                SELECT review.review_id, review.space_id, space.space_key,
                       space.name_ko AS space_name_ko, space.name_en AS space_name_en,
                       review.content_id, content.title AS content_title,
                       content.content_type, content.data_classification,
                       review.reviewer_strategy, review.status, review.created_at
                  FROM spc_publication_reviews review
                  JOIN spc_spaces space
                    ON space.tenant_id = review.tenant_id AND space.space_id = review.space_id
                  JOIN spc_content_items content
                    ON content.tenant_id = review.tenant_id AND content.content_id = review.content_id
                 WHERE review.tenant_id = :tenantId
                   AND (:status = 'ALL' OR review.status = :status)
                 ORDER BY review.created_at
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("status", status == null ? "ALL" : status.toUpperCase()),
                (resultSet, rowNumber) -> new SpaceDtos.PublicationReviewSummary(
                        resultSet.getObject("review_id", UUID.class),
                        resultSet.getObject("space_id", UUID.class),
                        resultSet.getString("space_key"),
                        resultSet.getString("space_name_ko"),
                        resultSet.getString("space_name_en"),
                        resultSet.getObject("content_id", UUID.class),
                        resultSet.getString("content_title"),
                        resultSet.getString("content_type"),
                        resultSet.getString("data_classification"),
                        resultSet.getString("reviewer_strategy"),
                        resultSet.getString("status"),
                        instant(resultSet, "created_at")));
    }

    public List<SpaceDtos.LifecycleReviewSummary> lifecycleReviews(long tenantId, String status) {
        return jdbc.query("""
                SELECT review.lifecycle_review_id, review.space_id, space.space_key,
                       space.name_ko AS space_name_ko, space.name_en AS space_name_en,
                       review.review_type, review.due_at, review.status,
                       review.recommendation, review.evidence
                  FROM spc_lifecycle_reviews review
                  JOIN spc_spaces space
                    ON space.tenant_id = review.tenant_id AND space.space_id = review.space_id
                 WHERE review.tenant_id = :tenantId
                   AND (:status = 'ALL' OR review.status = :status)
                 ORDER BY review.due_at
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("status", status == null ? "ALL" : status.toUpperCase()),
                (resultSet, rowNumber) -> new SpaceDtos.LifecycleReviewSummary(
                        resultSet.getObject("lifecycle_review_id", UUID.class),
                        resultSet.getObject("space_id", UUID.class),
                        resultSet.getString("space_key"),
                        resultSet.getString("space_name_ko"),
                        resultSet.getString("space_name_en"),
                        resultSet.getString("review_type"),
                        instant(resultSet, "due_at"),
                        resultSet.getString("status"),
                        resultSet.getString("recommendation"),
                        json(resultSet.getString("evidence"))));
    }

    public SpaceDtos.AdminMetrics adminMetrics(long tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE lifecycle_state = 'ACTIVE')::INTEGER AS active_spaces,
                       COUNT(*) FILTER (WHERE lifecycle_state = 'ACTIVE'
                         AND data_classification IN ('CONFIDENTIAL', 'RESTRICTED'))::INTEGER AS restricted_spaces,
                       (SELECT COUNT(*)::INTEGER FROM spc_space_requests
                         WHERE tenant_id = :tenantId AND status = 'PENDING') AS pending_requests,
                       (SELECT COUNT(*)::INTEGER FROM spc_publication_reviews
                         WHERE tenant_id = :tenantId AND status = 'PENDING') AS pending_reviews,
                       (SELECT COUNT(*)::INTEGER FROM spc_lifecycle_reviews
                         WHERE tenant_id = :tenantId AND status = 'OVERDUE') AS overdue_reviews,
                       (SELECT COUNT(*)::INTEGER FROM spc_memberships
                         WHERE tenant_id = :tenantId AND lifecycle_state = 'ACTIVE') AS active_memberships
                  FROM spc_spaces
                 WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId), (resultSet, rowNumber) ->
                new SpaceDtos.AdminMetrics(
                        resultSet.getInt("active_spaces"),
                        resultSet.getInt("restricted_spaces"),
                        resultSet.getInt("pending_requests"),
                        resultSet.getInt("pending_reviews"),
                        resultSet.getInt("overdue_reviews"),
                        resultSet.getInt("active_memberships")));
    }

    public boolean canModerate(SpaceRequestContext.Subject subject, UUID spaceId) {
        return hasActiveRole(subject, spaceId, List.of("OWNER", "MODERATOR"));
    }

    public boolean canManage(SpaceRequestContext.Subject subject, UUID spaceId) {
        return hasActiveRole(subject, spaceId, List.of("OWNER"));
    }

    private boolean hasActiveRole(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            List<String> roles) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
                  FROM spc_memberships membership
                 WHERE membership.tenant_id = :tenantId
                   AND membership.space_id = :spaceId
                   AND membership.lifecycle_state = 'ACTIVE'
                   AND membership.member_role IN (:roles)
                   AND ((membership.principal_type = 'USER' AND membership.principal_ref IN (:userRefs))
                        OR (membership.principal_type = 'GROUP' AND membership.principal_ref IN (:groups)))
                """, subjectParams(subject)
                .addValue("spaceId", spaceId)
                .addValue("roles", roles), Integer.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource subjectParams(SpaceRequestContext.Subject subject) {
        List<String> groups = new ArrayList<>(subject.groupRefs());
        if (groups.isEmpty()) groups.add("__NO_GROUP__");
        List<String> userRefs = new ArrayList<>();
        userRefs.add(Long.toString(subject.userId()));
        if (subject.personPublicId() != null) userRefs.add(subject.personPublicId().toString());
        return new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("userId", subject.userId())
                .addValue("userRefs", userRefs)
                .addValue("groups", groups);
    }

    private SpaceDtos.SpaceSummary spaceSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SpaceDtos.SpaceSummary(
                resultSet.getObject("space_id", UUID.class),
                resultSet.getString("space_key"),
                resultSet.getString("name_ko"),
                resultSet.getString("name_en"),
                resultSet.getString("summary_ko"),
                resultSet.getString("summary_en"),
                resultSet.getString("purpose_type"),
                resultSet.getString("visibility"),
                resultSet.getString("data_classification"),
                resultSet.getString("effective_role"),
                resultSet.getInt("member_count"),
                resultSet.getInt("content_count"),
                resultSet.getInt("unread_count"),
                resultSet.getString("icon_key"),
                resultSet.getString("accent_token"),
                resultSet.getString("cover_asset_url"),
                resultSet.getString("lifecycle_state"),
                instant(resultSet, "last_activity_at"),
                resultSet.getLong("version"));
    }

    private SpaceDtos.TemplateSummary templateSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new SpaceDtos.TemplateSummary(
                resultSet.getObject("template_id", UUID.class),
                resultSet.getString("template_key"),
                resultSet.getString("name_ko"),
                resultSet.getString("name_en"),
                resultSet.getString("description_ko"),
                resultSet.getString("description_en"),
                resultSet.getString("purpose_type"),
                resultSet.getString("creation_mode"),
                resultSet.getString("default_visibility"),
                resultSet.getString("default_data_classification"),
                resultSet.getString("icon_key"),
                resultSet.getString("accent_token"),
                resultSet.getString("lifecycle_state"),
                resultSet.getInt("current_version"),
                resultSet.getLong("version"));
    }

    private SpaceDtos.ContentSummary contentSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new SpaceDtos.ContentSummary(
                resultSet.getObject("content_id", UUID.class),
                resultSet.getString("content_type"),
                resultSet.getString("title"),
                resultSet.getString("summary"),
                resultSet.getString("route"),
                resultSet.getString("data_classification"),
                resultSet.getString("lifecycle_state"),
                resultSet.getLong("author_user_id"),
                resultSet.getString("author_name"),
                resultSet.getInt("current_revision"),
                instant(resultSet, "published_at"),
                instant(resultSet, "updated_at"));
    }

    private SpaceDtos.RequestSummary requestSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new SpaceDtos.RequestSummary(
                resultSet.getObject("request_id", UUID.class),
                resultSet.getObject("template_id", UUID.class),
                resultSet.getString("template_name_ko"),
                resultSet.getString("template_name_en"),
                resultSet.getLong("requester_user_id"),
                resultSet.getString("requester_name"),
                resultSet.getString("requested_key"),
                resultSet.getString("requested_name"),
                resultSet.getString("requested_summary"),
                resultSet.getString("requested_visibility"),
                resultSet.getString("justification"),
                resultSet.getString("decision_mode"),
                resultSet.getString("risk_level"),
                json(resultSet.getString("policy_evidence")),
                resultSet.getString("status"),
                resultSet.getString("decision_note"),
                instant(resultSet, "created_at"),
                instant(resultSet, "decided_at"),
                resultSet.getLong("version"));
    }

    private SpaceDtos.ActivitySummary activitySummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new SpaceDtos.ActivitySummary(
                resultSet.getObject("activity_id", UUID.class),
                resultSet.getString("space_key"),
                resultSet.getString("space_name_ko"),
                resultSet.getString("space_name_en"),
                resultSet.getString("activity_type"),
                resultSet.getString("actor_type"),
                resultSet.getString("actor_name"),
                resultSet.getString("object_type"),
                resultSet.getString("title_ko"),
                resultSet.getString("title_en"),
                resultSet.getString("route"),
                instant(resultSet, "occurred_at"));
    }

    private Map<String, Object> json(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(payload, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Space JSON is invalid.", exception);
        }
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
