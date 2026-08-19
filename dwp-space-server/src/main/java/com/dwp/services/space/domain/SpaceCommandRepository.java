package com.dwp.services.space.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.space.security.SpaceRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class SpaceCommandRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SpaceCommandRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }
    public UUID createRequest(
            SpaceRequestContext.Subject subject,
            SpaceDtos.CreateSpaceRequest input) {
        TemplatePolicy template = template(subject.tenantId(), input.templateId());
        DecisionPolicy decision = decision(template, input.requestedVisibility());
        UUID requestId = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("tenantId", subject.tenantId())
                .addValue("templateId", input.templateId())
                .addValue("requesterUserId", subject.userId())
                .addValue("personPublicId", subject.personPublicId())
                .addValue("requesterName", subject.displayName())
                .addValue("requestedKey", input.requestedKey())
                .addValue("requestedName", input.requestedName().trim())
                .addValue("requestedSummary", input.requestedSummary().trim())
                .addValue("visibility", input.requestedVisibility())
                .addValue("justification", input.justification().trim())
                .addValue("decisionMode", decision.mode())
                .addValue("riskLevel", decision.risk())
                .addValue("evidence", json(Map.of(
                        "templateMode", template.creationMode(),
                        "classification", template.classification(),
                        "visibility", input.requestedVisibility(),
                        "result", decision.autoProvision() ? "AUTO_APPROVE" : "REVIEW")))
                .addValue("status", decision.autoProvision() ? "APPROVED" : "PENDING")
                .addValue("decidedBy", decision.autoProvision() ? subject.userId() : null)
                .addValue("decisionNote", decision.autoProvision()
                        ? "Approved by tenant Space creation policy." : null)
                .addValue("decidedAt", decision.autoProvision() ? Instant.now() : null);
        try {
            jdbc.update("""
                    INSERT INTO spc_space_requests (
                        request_id, tenant_id, template_id, requester_user_id,
                        requester_person_public_id, requester_name, requested_key,
                        requested_name, requested_summary, requested_visibility,
                        justification, decision_mode, risk_level, policy_evidence,
                        status, decided_by, decision_note, decided_at)
                    VALUES (
                        :requestId, :tenantId, :templateId, :requesterUserId,
                        :personPublicId, :requesterName, :requestedKey,
                        :requestedName, :requestedSummary, :visibility,
                        :justification, :decisionMode, :riskLevel, CAST(:evidence AS jsonb),
                        :status, :decidedBy, :decisionNote, :decidedAt)
                    """, params);
        } catch (DuplicateKeyException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "A Space request with this key already exists.", exception);
        }
        if (decision.autoProvision()) provisionRequest(subject.tenantId(), requestId, subject.userId());
        return requestId;
    }

    public void decideRequest(
            SpaceRequestContext.Subject subject,
            UUID requestId,
            SpaceDtos.RequestDecision input) {
        int updated = jdbc.update("""
                UPDATE spc_space_requests
                   SET status = :status,
                       decided_by = :userId,
                       decision_note = :note,
                       decided_at = CURRENT_TIMESTAMP,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND request_id = :requestId
                   AND status = 'PENDING'
                   AND version = :expectedVersion
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("requestId", requestId)
                .addValue("status", "APPROVE".equals(input.decision()) ? "APPROVED" : "REJECTED")
                .addValue("userId", subject.userId())
                .addValue("note", input.note().trim())
                .addValue("expectedVersion", input.expectedVersion()));
        if (updated == 0) throw conflict("Space request was already decided or changed.");
        if ("APPROVE".equals(input.decision())) {
            provisionRequest(subject.tenantId(), requestId, subject.userId());
        }
    }

    public UUID createContent(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            SpaceDtos.CreateContentRequest input) {
        String contentPolicy = jdbc.queryForObject("""
                SELECT content_policy FROM spc_spaces
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND lifecycle_state = 'ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId), String.class);
        UUID contentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        boolean publishDirectly = "OPEN_PUBLISH".equals(contentPolicy);
        String lifecycleState = publishDirectly ? "PUBLISHED" : "IN_REVIEW";
        String payload = json(input.content());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("contentId", contentId)
                .addValue("revisionId", revisionId)
                .addValue("type", input.contentType())
                .addValue("title", input.title().trim())
                .addValue("summary", input.summary() == null ? "" : input.summary().trim())
                .addValue("classification", input.dataClassification())
                .addValue("state", lifecycleState)
                .addValue("userId", subject.userId())
                .addValue("displayName", subject.displayName())
                .addValue("payload", payload)
                .addValue("sha256", sha256(payload))
                .addValue("publishedAt", publishDirectly ? Instant.now() : null);
        jdbc.update("""
                INSERT INTO spc_content_items (
                    content_id, tenant_id, space_id, content_type, title, summary,
                    data_classification, lifecycle_state, author_user_id,
                    author_name, current_revision, published_at)
                VALUES (:contentId, :tenantId, :spaceId, :type, :title, :summary,
                    :classification, :state, :userId, :displayName, 1, :publishedAt)
                """, params);
        jdbc.update("""
                INSERT INTO spc_content_revisions (
                    revision_id, tenant_id, content_id, revision_number,
                    content_payload, content_sha256, created_by)
                VALUES (:revisionId, :tenantId, :contentId, 1,
                    CAST(:payload AS jsonb), :sha256, :userId)
                """, params);
        if (!publishDirectly) {
            jdbc.update("""
                    INSERT INTO spc_publication_reviews (
                        review_id, tenant_id, space_id, content_id, requested_by,
                        reviewer_strategy, status)
                    VALUES (:reviewId, :tenantId, :spaceId, :contentId, :userId,
                        :strategy, 'PENDING')
                    """, params
                    .addValue("reviewId", UUID.randomUUID())
                    .addValue("strategy", "COMPLIANCE_REVIEW".equals(contentPolicy)
                            ? "COMPLIANCE" : "SPACE_OWNER"));
        }
        recordActivity(subject, spaceId,
                publishDirectly ? "CONTENT_PUBLISHED" : "CONTENT_SUBMITTED",
                "CONTENT", contentId.toString(),
                input.title().trim(), input.title().trim(), null);
        return contentId;
    }

    public void decidePublication(
            SpaceRequestContext.Subject subject,
            UUID reviewId,
            SpaceDtos.ReviewDecision input) {
        String targetState = "APPROVE".equals(input.decision()) ? "PUBLISHED" : "REJECTED";
        int updated = jdbc.update("""
                WITH decision AS (
                    UPDATE spc_publication_reviews
                       SET status = :reviewState, decision_note = :note,
                           decided_by = :userId, decided_at = CURRENT_TIMESTAMP,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId AND review_id = :reviewId
                       AND status = 'PENDING'
                    RETURNING content_id, space_id
                )
                UPDATE spc_content_items content
                   SET lifecycle_state = :contentState,
                       published_at = CASE WHEN :contentState = 'PUBLISHED'
                                           THEN CURRENT_TIMESTAMP ELSE published_at END,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM decision
                 WHERE content.tenant_id = :tenantId
                   AND content.content_id = decision.content_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("reviewId", reviewId)
                .addValue("reviewState", "APPROVE".equals(input.decision()) ? "APPROVED" : "REJECTED")
                .addValue("contentState", targetState)
                .addValue("note", input.note().trim())
                .addValue("userId", subject.userId()));
        if (updated == 0) throw conflict("Publication review was already decided.");
    }

    public void updatePolicies(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            SpaceDtos.UpdatePolicyRequest input) {
        int updated = jdbc.update("""
                UPDATE spc_spaces
                   SET content_policy = :contentPolicy,
                       app_policy = :appPolicy,
                       ai_policy = :aiPolicy,
                       version = version + 1,
                       updated_by = :userId,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND version = :expectedVersion
                   AND lifecycle_state = 'ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("contentPolicy", input.contentPolicy())
                .addValue("appPolicy", input.appPolicy())
                .addValue("aiPolicy", input.aiPolicy())
                .addValue("expectedVersion", input.expectedVersion())
                .addValue("userId", subject.userId()));
        if (updated == 0) throw conflict("Space policies changed in another session.");
    }

    public UUID createAccessRequest(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            SpaceDtos.CreateAccessRequest input) {
        Integer activeMemberships = jdbc.queryForObject("""
                SELECT COUNT(*) FROM spc_memberships
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND principal_type = 'USER' AND principal_ref IN (:userRefs)
                   AND lifecycle_state = 'ACTIVE'
                   AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("userRefs", subject.personPublicId() == null
                        ? List.of(Long.toString(subject.userId()))
                        : List.of(Long.toString(subject.userId()), subject.personPublicId().toString())),
                Integer.class);
        if (activeMemberships != null && activeMemberships > 0) {
            throw conflict("The requester already has an active Space membership.");
        }
        String visibility = jdbc.queryForObject("""
                SELECT visibility FROM spc_spaces
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND lifecycle_state = 'ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId), String.class);
        boolean autoApprove = "OPEN".equals(visibility) && "VIEWER".equals(input.requestedRole());
        UUID requestId = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("userId", subject.userId())
                .addValue("personPublicId", subject.personPublicId())
                .addValue("displayName", subject.displayName())
                .addValue("requestedRole", input.requestedRole())
                .addValue("justification", input.justification().trim())
                .addValue("decisionMode", autoApprove ? "AUTO" : "OWNER_REVIEW")
                .addValue("status", autoApprove ? "APPROVED" : "PENDING")
                .addValue("decisionNote", autoApprove
                        ? "Approved by the open Space membership policy." : null)
                .addValue("decidedAt", autoApprove ? Instant.now() : null);
        try {
            jdbc.update("""
                    INSERT INTO spc_access_requests (
                        access_request_id, tenant_id, space_id, requester_user_id,
                        requester_person_public_id, requester_name, requested_role,
                        justification, decision_mode, status, decided_by,
                        decision_note, decided_at)
                    VALUES (
                        :requestId, :tenantId, :spaceId, :userId,
                        :personPublicId, :displayName, :requestedRole,
                        :justification, :decisionMode, :status,
                        :userId, :decisionNote, :decidedAt)
                    """, params);
        } catch (DuplicateKeyException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "A pending access request already exists for this Space.", exception);
        }
        if (autoApprove) {
            upsertMember(subject, spaceId, "USER",
                    subject.personPublicId() == null
                            ? Long.toString(subject.userId()) : subject.personPublicId().toString(),
                    input.requestedRole(), "REQUEST", null);
        }
        return requestId;
    }

    public void decideAccessRequest(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            UUID accessRequestId,
            SpaceDtos.AccessDecision input) {
        List<AccessProvisioning> requests = jdbc.query("""
                UPDATE spc_access_requests
                   SET status = :status,
                       decided_by = :userId,
                       decision_note = :note,
                       decided_at = CURRENT_TIMESTAMP,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND space_id = :spaceId
                   AND access_request_id = :requestId
                   AND status = 'PENDING'
                   AND version = :expectedVersion
                RETURNING requester_user_id, requester_person_public_id, requested_role
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("requestId", accessRequestId)
                .addValue("status", "APPROVE".equals(input.decision()) ? "APPROVED" : "REJECTED")
                .addValue("userId", subject.userId())
                .addValue("note", input.note().trim())
                .addValue("expectedVersion", input.expectedVersion()),
                (resultSet, rowNumber) -> new AccessProvisioning(
                        resultSet.getLong("requester_user_id"),
                        resultSet.getObject("requester_person_public_id", UUID.class),
                        resultSet.getString("requested_role")));
        if (requests.isEmpty()) throw conflict("Space access request was already decided or changed.");
        if ("APPROVE".equals(input.decision())) {
            AccessProvisioning request = requests.get(0);
            upsertMember(subject, spaceId, "USER",
                    request.personPublicId() == null
                            ? Long.toString(request.userId()) : request.personPublicId().toString(),
                    request.role(), "REQUEST", null);
        }
    }

    public void saveMember(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            SpaceDtos.SaveMemberRequest input) {
        upsertMember(subject, spaceId, input.principalType(), input.principalRef().trim(),
                input.memberRole(), "DIRECT", input.validUntil());
    }

    public void updateMember(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            UUID membershipId,
            SpaceDtos.UpdateMemberRequest input) {
        int updated = jdbc.update("""
                UPDATE spc_memberships
                   SET member_role = :memberRole,
                       valid_until = :validUntil,
                       lifecycle_state = 'ACTIVE',
                       approved_by = :userId,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND membership_id = :membershipId
                   AND version = :expectedVersion
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("membershipId", membershipId)
                .addValue("memberRole", input.memberRole())
                .addValue("validUntil", input.validUntil())
                .addValue("userId", subject.userId())
                .addValue("expectedVersion", input.expectedVersion()));
        if (updated == 0) throw conflict("Space membership changed in another session.");
    }

    public void revokeMember(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            UUID membershipId) {
        Integer targetIsSoleOwner = jdbc.queryForObject("""
                SELECT CASE WHEN target.member_role = 'OWNER'
                                  AND (SELECT COUNT(*) FROM spc_memberships owner_record
                                        WHERE owner_record.tenant_id = target.tenant_id
                                          AND owner_record.space_id = target.space_id
                                          AND owner_record.member_role = 'OWNER'
                                          AND owner_record.lifecycle_state = 'ACTIVE') <= 1
                            THEN 1 ELSE 0 END
                  FROM spc_memberships target
                 WHERE target.tenant_id = :tenantId AND target.space_id = :spaceId
                   AND target.membership_id = :membershipId
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("membershipId", membershipId), Integer.class);
        if (Integer.valueOf(1).equals(targetIsSoleOwner)) {
            throw conflict("The final active Space owner cannot be revoked.");
        }
        int updated = jdbc.update("""
                UPDATE spc_memberships
                   SET lifecycle_state = 'REVOKED', valid_until = CURRENT_TIMESTAMP,
                       version = version + 1, approved_by = :userId,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND membership_id = :membershipId
                   AND lifecycle_state = 'ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("membershipId", membershipId)
                .addValue("userId", subject.userId()));
        if (updated == 0) throw conflict("Space membership was already inactive.");
    }

    private void upsertMember(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            String principalType,
            String principalRef,
            String memberRole,
            String source,
            Instant validUntil) {
        jdbc.update("""
                INSERT INTO spc_memberships (
                    membership_id, tenant_id, space_id, principal_type,
                    principal_ref, member_role, membership_source,
                    lifecycle_state, valid_until, approved_by)
                VALUES (:membershipId, :tenantId, :spaceId, :principalType,
                    :principalRef, :memberRole, :source, 'ACTIVE', :validUntil, :userId)
                ON CONFLICT (tenant_id, space_id, principal_type, principal_ref)
                DO UPDATE SET member_role = EXCLUDED.member_role,
                              membership_source = EXCLUDED.membership_source,
                              lifecycle_state = 'ACTIVE',
                              valid_from = CURRENT_TIMESTAMP,
                              valid_until = EXCLUDED.valid_until,
                              approved_by = EXCLUDED.approved_by,
                              version = spc_memberships.version + 1,
                              updated_at = CURRENT_TIMESTAMP
                """, new MapSqlParameterSource()
                .addValue("membershipId", UUID.randomUUID())
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("principalType", principalType)
                .addValue("principalRef", principalRef)
                .addValue("memberRole", memberRole)
                .addValue("source", source)
                .addValue("validUntil", validUntil)
                .addValue("userId", subject.userId()));
    }

    public void decideLifecycle(
            SpaceRequestContext.Subject subject,
            UUID lifecycleReviewId,
            SpaceDtos.LifecycleDecision input) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("reviewId", lifecycleReviewId)
                .addValue("userId", subject.userId())
                .addValue("recommendation", input.recommendation())
                .addValue("note", input.note().trim());
        List<UUID> spaces = jdbc.query("""
                UPDATE spc_lifecycle_reviews
                   SET status = 'COMPLETED',
                       recommendation = :recommendation,
                       evidence = evidence || jsonb_build_object(
                           'decisionNote', :note,
                           'decidedBy', CAST(:userId AS text)),
                       decided_by = :userId,
                       decided_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND lifecycle_review_id = :reviewId
                   AND status IN ('OPEN', 'OVERDUE')
                RETURNING space_id
                """, params, (resultSet, rowNumber) -> resultSet.getObject("space_id", UUID.class));
        if (spaces.isEmpty()) throw conflict("Space lifecycle review was already decided.");
        String nextState = switch (input.recommendation()) {
            case "ARCHIVE" -> "ARCHIVED";
            case "DELETE" -> "DELETION_PENDING";
            default -> null;
        };
        if (nextState != null) {
            jdbc.update("""
                    UPDATE spc_spaces
                       SET lifecycle_state = :nextState,
                           archived_at = CASE WHEN :nextState = 'ARCHIVED'
                                              THEN CURRENT_TIMESTAMP ELSE archived_at END,
                           version = version + 1,
                           updated_by = :userId,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId AND space_id = :spaceId
                    """, params
                    .addValue("spaceId", spaces.get(0))
                    .addValue("nextState", nextState));
        }
    }

    private void provisionRequest(long tenantId, UUID requestId, long actorUserId) {
        RequestProvisioning request = jdbc.queryForObject("""
                SELECT request.template_id, request.requester_user_id,
                       request.requester_person_public_id,
                       request.requested_key, request.requested_name,
                       request.requested_summary, request.requested_visibility,
                       template.purpose_type, template.default_data_classification,
                       template.icon_key, template.accent_token
                  FROM spc_space_requests request
                  JOIN spc_templates template
                    ON template.tenant_id = request.tenant_id
                   AND template.template_id = request.template_id
                 WHERE request.tenant_id = :tenantId
                   AND request.request_id = :requestId
                   AND request.status = 'APPROVED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId), (resultSet, rowNumber) ->
                new RequestProvisioning(
                        resultSet.getObject("template_id", UUID.class),
                        resultSet.getLong("requester_user_id"),
                        resultSet.getObject("requester_person_public_id", UUID.class),
                        resultSet.getString("requested_key"),
                        resultSet.getString("requested_name"),
                        resultSet.getString("requested_summary"),
                        resultSet.getString("requested_visibility"),
                        resultSet.getString("purpose_type"),
                        resultSet.getString("default_data_classification"),
                        resultSet.getString("icon_key"),
                        resultSet.getString("accent_token")));
        if (request == null) throw conflict("Approved Space request could not be provisioned.");
        UUID spaceId = UUID.randomUUID();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("spaceId", spaceId)
                .addValue("requestId", requestId)
                .addValue("templateId", request.templateId())
                .addValue("requesterUserId", request.requesterUserId())
                .addValue("spaceKey", request.key())
                .addValue("name", request.name())
                .addValue("summary", request.summary())
                .addValue("visibility", request.visibility())
                .addValue("purpose", request.purpose())
                .addValue("classification", request.classification())
                .addValue("iconKey", request.iconKey())
                .addValue("accentToken", request.accentToken())
                .addValue("actorUserId", actorUserId);
        try {
            jdbc.update("""
                    INSERT INTO spc_spaces (
                        space_id, tenant_id, template_id, space_key, name_ko, name_en,
                        summary_ko, summary_en, purpose_type, visibility,
                        data_classification, content_policy, app_policy, ai_policy,
                        icon_key, accent_token, lifecycle_state, activated_at,
                        created_by, updated_by)
                    VALUES (:spaceId, :tenantId, :templateId, :spaceKey, :name, :name,
                        :summary, :summary, :purpose, :visibility, :classification,
                        'OWNER_REVIEW', 'OWNER_REVIEW', 'MEMBER_SCOPED',
                        :iconKey, :accentToken, 'ACTIVE', CURRENT_TIMESTAMP,
                        :actorUserId, :actorUserId)
                    """, params);
            jdbc.update("""
                    INSERT INTO spc_memberships (
                        membership_id, tenant_id, space_id, principal_type,
                        principal_ref, member_role, membership_source,
                        lifecycle_state, approved_by)
                    VALUES (:membershipId, :tenantId, :spaceId, 'USER',
                        :requesterRef, 'OWNER', 'REQUEST', 'ACTIVE', :actorUserId)
                    """, params
                    .addValue("membershipId", UUID.randomUUID())
                    .addValue("requesterRef", request.personPublicId() == null
                            ? Long.toString(request.requesterUserId())
                            : request.personPublicId().toString()));
            jdbc.update("""
                    UPDATE spc_space_requests
                       SET status = 'PROVISIONED', provisioned_space_id = :spaceId,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId AND request_id = :requestId
                    """, params);
        } catch (DuplicateKeyException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The requested Space key is already active.", exception);
        }
    }

    private TemplatePolicy template(long tenantId, UUID templateId) {
        TemplatePolicy result = jdbc.queryForObject("""
                SELECT creation_mode, default_data_classification
                  FROM spc_templates
                 WHERE tenant_id = :tenantId AND template_id = :templateId
                   AND lifecycle_state = 'PUBLISHED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("templateId", templateId), (resultSet, rowNumber) ->
                new TemplatePolicy(
                        resultSet.getString("creation_mode"),
                        resultSet.getString("default_data_classification")));
        if (result == null) throw new BaseException(ErrorCode.NOT_FOUND, "Space template was not found.");
        return result;
    }

    private DecisionPolicy decision(TemplatePolicy template, String visibility) {
        boolean elevated = "RESTRICTED".equals(template.classification())
                || "PRIVATE".equals(visibility)
                || "HIDDEN".equals(visibility);
        boolean auto = "AUTO".equals(template.creationMode()) && !elevated;
        String risk = elevated ? "HIGH"
                : "CONFIDENTIAL".equals(template.classification()) ? "MEDIUM" : "LOW";
        return new DecisionPolicy(template.creationMode(), risk, auto);
    }

    private void recordActivity(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            String activityType,
            String objectType,
            String objectRef,
            String titleKo,
            String titleEn,
            String route) {
        jdbc.update("""
                INSERT INTO spc_activity_events (
                    activity_id, tenant_id, space_id, activity_type, actor_type,
                    actor_ref, actor_name, object_type, object_ref,
                    title_ko, title_en, route)
                VALUES (:activityId, :tenantId, :spaceId, :activityType, 'USER',
                    :actorRef, :actorName, :objectType, :objectRef,
                    :titleKo, :titleEn, :route)
                """, new MapSqlParameterSource()
                .addValue("activityId", UUID.randomUUID())
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("activityType", activityType)
                .addValue("actorRef", Long.toString(subject.userId()))
                .addValue("actorName", subject.displayName())
                .addValue("objectType", objectType)
                .addValue("objectRef", objectRef)
                .addValue("titleKo", titleKo)
                .addValue("titleEn", titleEn)
                .addValue("route", route));
        jdbc.update("""
                UPDATE spc_spaces SET last_activity_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                """, new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Space payload could not be serialized.", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private record TemplatePolicy(String creationMode, String classification) {
    }

    private record DecisionPolicy(String mode, String risk, boolean autoProvision) {
    }

    private record RequestProvisioning(
            UUID templateId,
            long requesterUserId,
            UUID personPublicId,
            String key,
            String name,
            String summary,
            String visibility,
            String purpose,
            String classification,
            String iconKey,
            String accentToken) {
    }

    private record AccessProvisioning(long userId, UUID personPublicId, String role) {
    }
}
