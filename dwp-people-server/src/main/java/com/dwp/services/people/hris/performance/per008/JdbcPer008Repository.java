package com.dwp.services.people.hris.performance.per008;

import static com.dwp.services.people.hris.performance.per008.Per008CreateResolver.offset;
import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Tenant-bound persistence over the exact V1/V16/V19/V20 seams. */
@Repository
@ConditionalOnProperty(name = "dwp.hris.performance.feedback.enabled", havingValue = "true")
final class JdbcPer008Repository implements Per008Repository {
    interface PrivacyPolicyPort {
        PrivacyPolicy current(long tenantId, UUID cycleVersionId);
    }
    interface AudiencePolicyPort {
        AudiencePolicy frozen(long tenantId, UUID cycleVersionId, UUID templateArtifactPublicId);
    }

    @Override
    public void lockIdempotency(AuthorityEvidence authority, String action, String idempotencyKey) {
        bind(authority.tenantId());
        lock("per008:" + action + ':' + authority.tenantId() + ':'
                + authority.authorizationPrincipalPublicId() + ':' + idempotencyKey);
    }

    @Override
    public ReceiptRecord claimCreateReceipt(AuthorityEvidence authority, String idempotencyKey,
            String clientIntentDigest, UUID requestId, OffsetDateTime now) {
        lockIdempotency(authority, CREATE_ACTION, idempotencyKey);
        Optional<ReceiptRecord> prior = receipt(authority, CREATE_ACTION, idempotencyKey);
        if (prior.isPresent()) {
            if ("SUCCEEDED".equals(prior.get().state())) {
                requireCreateReplayBinding(authority, prior.get());
            }
            return prior.get();
        }
        UUID receiptId = UUID.randomUUID();
        insertReceipt(authority, receiptId, idempotencyKey, "FEEDBACK_REQUEST_CREATE",
                CREATE_ACTION, requestId, 0, clientIntentDigest, "ACCEPTED", null, null, null);
        return new ReceiptRecord(receiptId, clientIntentDigest, requestId, null, 0, null,
                "ACCEPTED");
    }

    private static final String CREATE_ACTION = "PEP-PER-015";
    private static final String SUBMIT_ACTION = "PEP-PER-022";

    private final JdbcTemplate jdbc;
    private final Per008CreateResolver createResolver;
    private final ObjectMapper mapper;
    private final Per008Protection protection;
    private final PrivacyPolicyPort privacyPolicies;
    private final AudiencePolicyPort audiencePolicies;

    JdbcPer008Repository(JdbcTemplate jdbc, ObjectMapper mapper, Per008Protection protection,
            ObjectProvider<PrivacyPolicyPort> privacyPolicies,
            ObjectProvider<AudiencePolicyPort> audiencePolicies) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.protection = protection;
        this.privacyPolicies = privacyPolicies.getIfAvailable();
        this.audiencePolicies = audiencePolicies.getIfAvailable();
        this.createResolver = new Per008CreateResolver(jdbc, mapper, protection,
                this.privacyPolicies == null ? null : this.privacyPolicies::current,
                this.audiencePolicies == null ? null : this.audiencePolicies::frozen, this::bind);
    }

    @Override
    public Optional<ReceiptRecord> receipt(AuthorityEvidence authority, String action,
            String idempotencyKey) {
        bind(authority.tenantId());
        List<ReceiptRecord> rows = jdbc.query("""
                SELECT command_receipt_id, request_hash, aggregate_id, result_ref,
                       applied_aggregate_version, completed_at, receipt_state
                  FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND subject_principal_public_id = ?
                   AND originating_action = ? AND idempotency_key = ?
                """, (rs, ignored) -> new ReceiptRecord(rs.getObject(1, UUID.class),
                rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getLong(5), offset(rs, 6), rs.getString(7)), authority.tenantId(), authority.authorizationPrincipalPublicId(),
                action, idempotencyKey);
        return rows.stream().findFirst();
    }

    @Override
    public WorkforceEvidence subject(AuthorityEvidence authority, UUID cycleVersionId,
            UUID workerPublicId, UUID assignmentPublicId) {
        return createResolver.subject(authority, cycleVersionId, workerPublicId, assignmentPublicId);
    }

    @Override
    public List<TemplateAuthority> eligibleTemplates(AuthorityEvidence authority) {
        return createResolver.eligibleTemplates(authority.tenantId());
    }

    @Override
    public CreateResolution resolveCreate(AuthorityEvidence authority,
            CreateFeedbackRequestCommand command, TemplateAuthority template) {
        return createResolver.resolveCreate(authority, command, template);
    }

    @Override
    public FeedbackRequestReceipt create(AuthorityEvidence authority,
            CreateFeedbackRequestCommand command, CreateResolution resolution,
            ReceiptRecord claimedReceipt, OffsetDateTime now) {
        bind(authority.tenantId());
        if (claimedReceipt == null || !"ACCEPTED".equals(claimedReceipt.state())
                || claimedReceipt.aggregateId() == null || claimedReceipt.resultRef() != null
                || claimedReceipt.appliedVersion() != 0) {
            throw Per008Problem.unavailable("PER008_CREATE_RECEIPT_INVALID");
        }
        UUID requestId = claimedReceipt.aggregateId();
        UUID receiptId = claimedReceipt.receiptId();
        int effectiveThreshold = Math.max(command.minimumReleaseCount(),
                resolution.privacyPolicy().minimumReleaseCount());
        int receiptBound = jdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET population_scope_digest = ?
                 WHERE tenant_id = ? AND command_receipt_id = ?
                   AND originating_action = 'PEP-PER-015'
                   AND subject_principal_public_id = ?
                   AND aggregate_id = ? AND expected_aggregate_version = 0
                   AND applied_aggregate_version IS NULL AND result_ref IS NULL
                   AND request_hash = ? AND receipt_state = 'ACCEPTED'
                   AND completed_at IS NULL AND population_scope_digest = ?
                """, resolution.executionBindingDigest(), authority.tenantId(), receiptId,
                authority.authorizationPrincipalPublicId(), requestId,
                claimedReceipt.clientIntentDigest(), authority.populationScopeDigest());
        if (receiptBound != 1) {
            throw Per008Problem.conflict("PER008_CREATE_RECEIPT_BINDING_CONFLICT");
        }
        jdbc.update("""
                    INSERT INTO hris_performance.prf_feedback_requests (
                        tenant_id, public_id, aggregate_version, cycle_version_id,
                        subject_worker_public_id, subject_assignment_public_id,
                        workforce_projection_head_id, workforce_local_revision,
                        workforce_owner_revision, workforce_snapshot_as_of,
                        workforce_owner_page_digest, workforce_content_hash,
                        template_artifact_public_id, template_configuration_id,
                        template_platform_version, template_schema_key,
                        template_payload_digest, template_scope_digest, template_content_hash,
                        request_title, authorization_principal_public_id,
                        create_command_receipt_id, request_state,
                        anonymity_mode, requested_minimum_release_count,
                        privacy_minimum_release_count, effective_minimum_release_count,
                        privacy_policy_public_id, threshold_policy_revision,
                        threshold_policy_digest, audience_policy_public_id,
                        audience_policy_revision, audience_policy_digest, opens_at, closes_at,
                        audience_scope_digest, execution_binding_digest,
                        scope_decision_revision, authorization_decision_revision,
                        created_at, created_by, updated_at)
                    VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, authority.tenantId(), requestId, command.cycleVersionId(),
                    command.subjectWorkerPublicId(), command.subjectAssignmentPublicId(),
                    resolution.subject().projectionHeadId(), resolution.subject().localRevision(),
                    resolution.subject().ownerRevision(), resolution.subject().snapshotAsOf(),
                    resolution.subject().ownerPageDigest(), resolution.subject().contentHash(),
                    resolution.template().artifactPublicId(), resolution.template().configurationId(),
                    resolution.template().platformVersion(), resolution.template().schemaKey(),
                    resolution.template().payloadDigest(), resolution.template().scopeDigest(),
                    resolution.template().payloadDigest(), resolution.template().title(),
                    authority.authorizationPrincipalPublicId(), receiptId,
                    command.anonymityMode().name(),
                    command.minimumReleaseCount(), resolution.privacyPolicy().minimumReleaseCount(),
                    effectiveThreshold, resolution.privacyPolicy().publicId(),
                    resolution.privacyPolicy().revision(), resolution.privacyPolicy().digest(),
                    resolution.audiencePolicy().publicId(), resolution.audiencePolicy().revision(),
                    resolution.audiencePolicy().digest(),
                    command.opensAt(), command.closesAt(), resolution.audienceScopeDigest(),
                    resolution.executionBindingDigest(), authority.scopeDecisionRevision(),
                    authority.authorizationDecisionRevision(), now,
                    authority.authorizationPrincipalPublicId(), now);
        int ordinal = 0;
        for (AudienceMember member : resolution.audience()) {
            ordinal++;
            jdbc.update("""
                        INSERT INTO hris_performance.prf_feedback_assignments (
                            tenant_id, public_id, feedback_request_public_id,
                            provider_worker_public_id, provider_assignment_public_id,
                            workforce_projection_head_id, workforce_local_revision,
                            relationship_type, assignment_state,
                            request_scoped_mask_label, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ASSIGNED', ?, ?, ?)
                        """, authority.tenantId(), UUID.randomUUID(), requestId,
                        member.workerPublicId(), member.assignmentPublicId(),
                        member.projectionHeadId(), member.localRevision(),
                    member.relationshipType().name(), "Reviewer " + ordinal, now, now);
        }
        completeReceipt(authority.tenantId(), receiptId, 1, requestId, now);
        return new FeedbackRequestReceipt(receiptId, requestId, RequestState.DRAFT,
                1, now, false);
    }

    @Override
    public List<AssignedListItem> assignmentsFor(AuthorityEvidence authority, int limit,
            String cursor, OffsetDateTime now) {
        bind(authority.tenantId());
        UUID after = cursor == null ? new UUID(0, 0) : decodeCursor(cursor);
        return jdbc.query("""
                SELECT assignment.public_id, request.public_id, request.request_title,
                       request.request_state, assignment.assignment_state,
                       request.opens_at, request.closes_at
                  FROM hris_performance.prf_feedback_assignments assignment
                  JOIN hris_performance.prf_feedback_requests request
                    ON request.tenant_id = assignment.tenant_id
                   AND request.public_id = assignment.feedback_request_public_id
                 WHERE assignment.tenant_id = ?
                   AND assignment.provider_worker_public_id = ?
                   AND assignment.provider_assignment_public_id = ?
                   AND request.request_state = 'OPEN'
                   AND request.opens_at <= ? AND request.closes_at > ?
                   AND assignment.public_id > ?
                 ORDER BY assignment.public_id
                 LIMIT ?
                """, (rs, ignored) -> new AssignedListItem(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), RequestState.valueOf(rs.getString(4)),
                AssignmentState.valueOf(rs.getString(5)), offset(rs, 6), offset(rs, 7)),
                authority.tenantId(), authority.actorWorkerPublicId(),
                authority.actorAssignmentPublicId(), now, now, after, limit);
    }

    @Override
    public AssignmentDetailRecord assignment(AuthorityEvidence authority, UUID assignmentId,
            boolean forUpdate, OffsetDateTime now) {
        bind(authority.tenantId());
        String lock = forUpdate ? " FOR UPDATE OF assignment, request" : "";
        String submissionGuard = forUpdate ? """
                   AND assignment.assignment_state = 'ASSIGNED'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM hris_performance.prf_feedback_responses existing_response
                        WHERE existing_response.tenant_id = assignment.tenant_id
                          AND existing_response.feedback_assignment_public_id = assignment.public_id)
                """ : "";
        List<AssignmentJoin> rows = jdbc.query("""
                SELECT request.tenant_id, request.public_id, request.aggregate_version,
                       request.cycle_version_id, request.subject_worker_public_id,
                       request.subject_assignment_public_id,
                       request.authorization_principal_public_id,
                       request.template_artifact_public_id, request.template_content_hash,
                       request.request_title, request.request_state, request.anonymity_mode,
                       request.effective_minimum_release_count,
                       request.privacy_policy_public_id, request.threshold_policy_revision,
                       request.threshold_policy_digest, request.opens_at, request.closes_at,
                       request.created_at, request.aggregated_at, request.released_at,
                       assignment.public_id, assignment.provider_worker_public_id,
                       assignment.provider_assignment_public_id, assignment.relationship_type,
                       assignment.assignment_state, assignment.submitted_at
                  FROM hris_performance.prf_feedback_assignments assignment
                  JOIN hris_performance.prf_feedback_requests request
                    ON request.tenant_id = assignment.tenant_id
                   AND request.public_id = assignment.feedback_request_public_id
                 WHERE assignment.tenant_id = ? AND assignment.public_id = ?
                   AND assignment.provider_worker_public_id = ?
                   AND assignment.provider_assignment_public_id = ?
                   AND request.request_state = 'OPEN'
                   AND request.opens_at <= ? AND request.closes_at > ?
                """ + submissionGuard + lock, this::assignmentJoin,
                authority.tenantId(), assignmentId,
                authority.actorWorkerPublicId(), authority.actorAssignmentPublicId(), now, now);
        if (rows.size() != 1) throw Per008Problem.notFound();
        AssignmentJoin join = rows.getFirst();
        TemplateAuthority template = createResolver.template(authority.tenantId(),
                join.request().templateArtifactPublicId());
        return new AssignmentDetailRecord(join.request(), join.assignment(), template);
    }

    @Override
    public FeedbackSubmissionReceipt submit(AuthorityEvidence authority,
            AssignmentDetailRecord detail, ProtectedContent response,
            String idempotencyKey, String clientIntentDigest, OffsetDateTime now) {
        bind(authority.tenantId());
        lock("per008:submit:" + authority.tenantId() + ':'
                + authority.authorizationPrincipalPublicId() + ':' + idempotencyKey);
        Optional<ReceiptRecord> prior = receipt(authority, SUBMIT_ACTION, idempotencyKey);
        if (prior.isPresent()) {
            requireSucceeded(prior.get());
            requireSameIntent(prior.get(), clientIntentDigest);
            return new FeedbackSubmissionReceipt(prior.get().receiptId(),
                    detail.request().publicId(), prior.get().resultRef(), AssignmentState.SUBMITTED,
                    prior.get().appliedVersion(), prior.get().completedAt(), true);
        }
        UUID receiptId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();
        insertReceipt(authority, receiptId, idempotencyKey, "FEEDBACK_RESPONSE_SUBMIT",
                SUBMIT_ACTION, detail.request().publicId(), detail.request().aggregateVersion(),
                clientIntentDigest, "SUCCEEDED", detail.request().aggregateVersion(),
                detail.assignment().publicId(), now);
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_responses (
                    tenant_id, public_id, feedback_request_public_id,
                    feedback_assignment_public_id, response_schema_key,
                    template_content_hash, submit_command_receipt_id,
                    encryption_envelope_version, encryption_algorithm,
                    encrypted_content, encryption_nonce, authentication_tag,
                    encryption_key_version_public_id, canonical_content_hmac,
                    digest_key_version_public_id, submitted_at, created_at)
                VALUES (?, ?, ?, ?, 'PerformanceFeedbackResponse.v1', ?, ?,
                        1, 'AES_256_GCM', ?, ?, ?, ?, ?, ?, ?, ?)
                """, authority.tenantId(), responseId, detail.request().publicId(),
                detail.assignment().publicId(), detail.request().templateContentHash(), receiptId,
                response.encodedEnvelope(), response.nonce(), response.authenticationTag(),
                response.encryptionKeyVersionPublicId(), response.canonicalContentHmac(),
                response.digestKeyVersionPublicId(), now, now);
        int changed = jdbc.update("""
                UPDATE hris_performance.prf_feedback_assignments
                   SET assignment_state = 'SUBMITTED', submitted_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND public_id = ? AND assignment_state = 'ASSIGNED'
                """, now, now, authority.tenantId(), detail.assignment().publicId());
        if (changed != 1) throw Per008Problem.conflict("PER008_ASSIGNMENT_NOT_ASSIGNED");
        String payload = submissionEventPayload(mapper, detail.request().publicId(),
                detail.assignment().publicId(), now);
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload)
                VALUES (?, ?, ?, 'PERFORMANCE_FEEDBACK_ASSIGNMENT', ?, ?,
                        'PerformanceFeedbackSubmitted.v1', ?::jsonb)
                """, UUID.randomUUID(), authority.tenantId(), receiptId,
                detail.assignment().publicId(), detail.request().aggregateVersion(), payload);
        return new FeedbackSubmissionReceipt(receiptId, detail.request().publicId(),
                detail.assignment().publicId(), AssignmentState.SUBMITTED,
                detail.request().aggregateVersion(), now, false);
    }

    static String submissionEventPayload(ObjectMapper mapper, UUID requestId,
            UUID assignmentId, OffsetDateTime submittedAt) {
        if (mapper == null || requestId == null || assignmentId == null || submittedAt == null) {
            throw Per008Problem.unavailable("PER008_SUBMISSION_EVENT_INVALID");
        }
        try {
            return mapper.writeValueAsString(Map.of(
                    "feedbackRequestId", requestId.toString(),
                    "feedbackAssignmentId", assignmentId.toString(),
                    "submittedAt", submittedAt.toString()));
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public RequestRecord request(AuthorityEvidence authority, UUID requestId, boolean forUpdate) {
        bind(authority.tenantId());
        List<RequestRecord> rows = jdbc.query("""
                SELECT tenant_id, public_id, aggregate_version, cycle_version_id,
                       subject_worker_public_id, subject_assignment_public_id,
                       authorization_principal_public_id, template_artifact_public_id,
                       template_content_hash, request_title, request_state, anonymity_mode,
                       effective_minimum_release_count, privacy_policy_public_id,
                       threshold_policy_revision, threshold_policy_digest,
                       opens_at, closes_at, created_at, aggregated_at, released_at
                  FROM hris_performance.prf_feedback_requests
                 WHERE tenant_id = ? AND public_id = ?
                """ + (forUpdate ? " FOR UPDATE" : ""), this::requestRecord,
                authority.tenantId(), requestId);
        if (rows.size() != 1) throw Per008Problem.notFound();
        RequestRecord request = rows.getFirst();
        if (!releasePermitted(authority, request)) throw Per008Problem.notFound();
        return request;
    }

    @Override
    public TemplateAuthority templateForRequest(AuthorityEvidence authority, UUID requestId) {
        RequestRecord request = request(authority, requestId, false);
        return createResolver.template(authority.tenantId(), request.templateArtifactPublicId());
    }

    @Override
    public Optional<ReleaseSnapshot> releaseSnapshot(AuthorityEvidence authority, UUID requestId) {
        RequestRecord request = request(authority, requestId, false);
        List<ReleaseSnapshot> rows = jdbc.query("""
                SELECT public_id, feedback_request_public_id, encrypted_content,
                       encryption_nonce, authentication_tag,
                       encryption_key_version_public_id, canonical_content_hmac,
                       digest_key_version_public_id, source_set_digest,
                       aggregation_algorithm_version, aggregated_at
                  FROM hris_performance.prf_feedback_release_snapshots
                 WHERE tenant_id = ? AND feedback_request_public_id = ?
                """, (rs, ignored) -> new ReleaseSnapshot(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), protectedContent(rs, 3), rs.getString(9),
                rs.getString(10), offset(rs, 11)), authority.tenantId(), request.publicId());
        return rows.stream().findFirst();
    }

    @Override
    public StoredResponse storedResponseForReplay(AuthorityEvidence authority, UUID receiptId,
            UUID assignmentId) {
        bind(authority.tenantId());
        List<StoredResponse> rows = jdbc.query("""
                SELECT response.public_id, response.feedback_assignment_public_id,
                       response.template_content_hash, response.encrypted_content,
                       response.encryption_nonce, response.authentication_tag,
                       response.encryption_key_version_public_id,
                       response.canonical_content_hmac,
                       response.digest_key_version_public_id, response.submitted_at
                  FROM hris_performance.prf_feedback_responses response
                 WHERE response.tenant_id = ?
                   AND response.submit_command_receipt_id = ?
                   AND response.feedback_assignment_public_id = ?
                """, (rs, ignored) -> new StoredResponse(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), protectedContent(rs, 4),
                offset(rs, 10)), authority.tenantId(), receiptId, assignmentId);
        if (rows.size() != 1) throw Per008Problem.unavailable("PER008_REPLAY_EVIDENCE_UNAVAILABLE");
        return rows.getFirst();
    }

    @Override
    public List<StoredResponse> submittedResponses(AuthorityEvidence authority, UUID requestId) {
        bind(authority.tenantId());
        return jdbc.query("""
                SELECT response.public_id, response.feedback_assignment_public_id,
                       response.template_content_hash, response.encrypted_content,
                       response.encryption_nonce, response.authentication_tag,
                       response.encryption_key_version_public_id,
                       response.canonical_content_hmac,
                       response.digest_key_version_public_id, response.submitted_at
                  FROM hris_performance.prf_feedback_responses response
                  JOIN hris_performance.prf_feedback_assignments assignment
                    ON assignment.tenant_id = response.tenant_id
                   AND assignment.public_id = response.feedback_assignment_public_id
                 WHERE response.tenant_id = ?
                   AND response.feedback_request_public_id = ?
                   AND assignment.assignment_state = 'SUBMITTED'
                 ORDER BY response.public_id
                """, (rs, ignored) -> new StoredResponse(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getString(3), protectedContent(rs, 4),
                offset(rs, 10)),
                authority.tenantId(), requestId);
    }

    @Override
    public UUID createLifecycleReceipt(AuthorityEvidence authority, RequestRecord request,
            String action, String idempotencyKey, String requestDigest, OffsetDateTime now) {
        bind(authority.tenantId());
        UUID id = UUID.randomUUID();
        insertReceipt(authority, id, idempotencyKey, "FEEDBACK_REQUEST_LIFECYCLE", action,
                request.publicId(), request.aggregateVersion(), requestDigest, "SUCCEEDED",
                request.aggregateVersion() + 1, request.publicId(), now);
        return id;
    }

    @Override
    public void transitionRequest(AuthorityEvidence authority, UUID requestId,
            RequestState expected, RequestState target, long expectedVersion,
            UUID lifecycleReceiptId, OffsetDateTime now) {
        bind(authority.tenantId());
        String milestone = switch (target) {
            case AGGREGATED -> ", aggregated_at = ?";
            case RELEASED -> ", released_at = ?";
            case CANCELLED -> ", cancelled_at = ?";
            default -> "";
        };
        String receiptColumn = switch (target) {
            case OPEN -> "open_command_receipt_id";
            case CLOSED -> "close_command_receipt_id";
            case AGGREGATED -> "aggregation_command_receipt_id";
            case RELEASED -> "release_command_receipt_id";
            case CANCELLED -> "cancel_command_receipt_id";
            default -> throw Per008Problem.conflict("PER008_REQUEST_STATE_CONFLICT");
        };
        List<Object> args = new ArrayList<>(List.of(target.name(), now, lifecycleReceiptId));
        if (!milestone.isEmpty()) args.add(now);
        args.add(authority.tenantId());
        args.add(requestId);
        args.add(expected.name());
        args.add(expectedVersion);
        int changed = jdbc.update("UPDATE hris_performance.prf_feedback_requests"
                + " SET request_state = ?, aggregate_version = aggregate_version + 1, updated_at = ?, "
                + receiptColumn + " = ?"
                + milestone + " WHERE tenant_id = ? AND public_id = ? AND request_state = ?"
                + " AND aggregate_version = ?",
                args.toArray());
        if (changed != 1) throw Per008Problem.conflict("PER008_REQUEST_STATE_CONFLICT");
    }

    @Override
    public void cancelOutstandingAssignments(AuthorityEvidence authority, UUID requestId,
            OffsetDateTime now) {
        bind(authority.tenantId());
        jdbc.update("""
                UPDATE hris_performance.prf_feedback_assignments
                   SET assignment_state = 'CANCELLED', cancelled_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND feedback_request_public_id = ?
                   AND assignment_state = 'ASSIGNED'
                """, now, now, authority.tenantId(), requestId);
    }

    @Override
    public void insertReleaseSnapshot(AuthorityEvidence authority, RequestRecord request,
            UUID snapshotId, UUID aggregationReceiptId, ProtectedContent content,
            String sourceSetDigest, String aggregationAlgorithmVersion,
            int submittedCount, OffsetDateTime now) {
        bind(authority.tenantId());
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_release_snapshots (
                    tenant_id, public_id, feedback_request_public_id,
                    aggregate_schema_key, threshold_policy_revision,
                    privacy_policy_public_id, threshold_policy_digest,
                    distinct_submitted_count, source_set_digest,
                    aggregation_algorithm_version, aggregation_command_receipt_id,
                    encryption_envelope_version, encryption_algorithm,
                    encrypted_content, encryption_nonce, authentication_tag,
                    encryption_key_version_public_id, canonical_content_hmac,
                    digest_key_version_public_id, aggregated_at, created_at)
                VALUES (?, ?, ?, 'PerformanceFeedbackAggregate.v1', ?, ?, ?, ?, ?, ?, ?,
                        1, 'AES_256_GCM', ?, ?, ?, ?, ?, ?, ?, ?)
                """, authority.tenantId(), snapshotId, request.publicId(),
                request.thresholdPolicyRevision(), request.privacyPolicyPublicId(),
                request.thresholdPolicyDigest(), submittedCount, sourceSetDigest,
                aggregationAlgorithmVersion, aggregationReceiptId, content.encodedEnvelope(),
                content.nonce(), content.authenticationTag(),
                content.encryptionKeyVersionPublicId(), content.canonicalContentHmac(),
                content.digestKeyVersionPublicId(), now, now);
    }

    List<AudienceMember> deriveAudience(WorkforceEvidence subject, List<Member> members,
            AnonymityMode mode, AudiencePolicy policy) {
        return createResolver.deriveAudience(subject, members, mode, policy);
    }

    private AssignmentJoin assignmentJoin(ResultSet rs, int ignored) throws SQLException {
        RequestRecord request = requestRecord(rs, ignored);
        AssignmentRecord assignment = new AssignmentRecord(rs.getLong(1),
                rs.getObject(22, UUID.class), request.publicId(),
                rs.getObject(23, UUID.class), rs.getObject(24, UUID.class),
                RelationshipType.valueOf(rs.getString(25)), AssignmentState.valueOf(rs.getString(26)),
                offset(rs, 27));
        return new AssignmentJoin(request, assignment);
    }

    private RequestRecord requestRecord(ResultSet rs, int ignored) throws SQLException {
        return new RequestRecord(rs.getLong(1), rs.getObject(2, UUID.class), rs.getLong(3),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
                rs.getObject(8, UUID.class), rs.getString(9), rs.getString(10),
                RequestState.valueOf(rs.getString(11)), AnonymityMode.valueOf(rs.getString(12)),
                rs.getInt(13), rs.getObject(14, UUID.class), rs.getString(15), rs.getString(16),
                offset(rs, 17), offset(rs, 18), offset(rs, 19), offset(rs, 20), offset(rs, 21));
    }

    private boolean releasePermitted(AuthorityEvidence authority, RequestRecord request) {
        return switch (authority.profileKey()) {
            case "self-feedback-release-reader" -> authority.permits(
                    request.subjectWorkerPublicId(), request.subjectAssignmentPublicId());
            case "manager-feedback-release-reader", "operator-feedback-release-reader" ->
                    authority.permits(request.subjectWorkerPublicId(),
                            request.subjectAssignmentPublicId());
            case Per008Service.LIFECYCLE_PRINCIPAL -> true;
            default -> false;
        };
    }

    private ProtectedContent protectedContent(ResultSet rs, int first) throws SQLException {
        return new ProtectedContent(rs.getBytes(first), rs.getBytes(first + 1),
                rs.getBytes(first + 2), rs.getObject(first + 3, UUID.class),
                rs.getString(first + 4), rs.getObject(first + 5, UUID.class));
    }

    private void insertReceipt(AuthorityEvidence authority, UUID id, String key,
            String commandType, String action, UUID aggregate, long expectedVersion,
            String requestHash, String state, Long appliedVersion, UUID resultRef,
            OffsetDateTime completedAt) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, applied_aggregate_version,
                    request_hash, population_scope_digest, field_policy_revision,
                    purpose_code, authorization_revision, receipt_state,
                    result_ref, accepted_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                """, id, authority.tenantId(), key, commandType, action, authority.actorId(),
                authority.authorizationPrincipalPublicId(), aggregate, expectedVersion,
                appliedVersion, requestHash, authority.populationScopeDigest(),
                positiveRevision(authority.fieldDecisions().toString()),
                authority.purposeCode(), positiveRevision(authority.authorizationDecisionRevision()),
                state, resultRef, completedAt);
    }

    private void completeReceipt(long tenantId, UUID receiptId, long version,
            UUID resultRef, OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET receipt_state = 'SUCCEEDED', applied_aggregate_version = ?,
                       result_ref = ?, completed_at = ?
                 WHERE tenant_id = ? AND command_receipt_id = ? AND receipt_state = 'ACCEPTED'
                """, version, resultRef, now, tenantId, receiptId);
        if (changed != 1) throw Per008Problem.conflict("PER008_RECEIPT_STATE_CONFLICT");
    }

    private void requireCreateReplayBinding(AuthorityEvidence authority, ReceiptRecord receipt) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_command_receipts command_receipt
                  JOIN hris_performance.prf_feedback_requests request
                    ON request.tenant_id = command_receipt.tenant_id
                   AND request.create_command_receipt_id = command_receipt.command_receipt_id
                 WHERE command_receipt.tenant_id = ?
                   AND command_receipt.command_receipt_id = ?
                   AND command_receipt.originating_action = 'PEP-PER-015'
                   AND command_receipt.subject_principal_public_id = ?
                   AND command_receipt.receipt_state = 'SUCCEEDED'
                   AND command_receipt.applied_aggregate_version = 1
                   AND command_receipt.aggregate_id = request.public_id
                   AND command_receipt.result_ref = request.public_id
                   AND command_receipt.population_scope_digest = request.execution_binding_digest
                """, Integer.class, authority.tenantId(), receipt.receiptId(),
                authority.authorizationPrincipalPublicId());
        if (count == null || count != 1) {
            throw Per008Problem.unavailable("PER008_CREATE_REPLAY_EVIDENCE_INVALID");
        }
    }

    private void requireSameIntent(ReceiptRecord prior, String digest) {
        if (!prior.clientIntentDigest().equals(digest)) {
            throw Per008Problem.conflict("PER008_IDEMPOTENCY_KEY_REUSED");
        }
    }

    private void requireSucceeded(ReceiptRecord receipt) {
        if (!"SUCCEEDED".equals(receipt.state()) || receipt.aggregateId() == null
                || receipt.resultRef() == null || receipt.appliedVersion() < 1
                || receipt.completedAt() == null) {
            throw Per008Problem.unavailable("PER008_IDEMPOTENCY_OUTCOME_PENDING");
        }
    }

    void bind(long tenantId) {
        if (tenantId < 1) throw Per008Problem.unavailable("PER008_TENANT_UNAVAILABLE");
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw Per008Problem.unavailable("PER008_TRANSACTION_REQUIRED");
        }
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?::text, TRUE)",
                String.class, tenantId);
    }

    private void lock(String identity) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Long.class, identity);
    }

    private long positiveRevision(String value) {
        long result = Long.parseUnsignedLong(protection.sha256(value.getBytes(StandardCharsets.UTF_8))
                .substring(0, 15), 16);
        return result == 0 ? 1 : result;
    }

    static String encodeCursor(UUID value) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private UUID decodeCursor(String cursor) {
        try {
            return UUID.fromString(new String(java.util.Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.US_ASCII));
        } catch (RuntimeException invalid) {
            throw Per008Problem.invalid("PER008_CURSOR_INVALID");
        }
    }

    record Member(UUID workerId, UUID assignmentId, UUID managerAssignmentId,
            String status) implements Per008CreateResolver.AudienceCandidate { }
    private record AssignmentJoin(RequestRecord request, AssignmentRecord assignment) { }
}
