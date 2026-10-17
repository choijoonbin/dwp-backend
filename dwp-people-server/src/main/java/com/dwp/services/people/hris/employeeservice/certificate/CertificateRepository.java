package com.dwp.services.people.hris.employeeservice.certificate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dwp.services.people.hr.HcmPopulationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Tenant- and population-scoped persistence for the exact V58 metadata model. */
@Repository
@ConditionalOnProperty(prefix = Hrm009RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
class CertificateRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    CertificateRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void establishTenant(long tenantId) {
        if (tenantId < 1) throw new IllegalArgumentException("tenantId must be positive");
        String applied = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)",
                new MapSqlParameterSource("tenant", tenantId), String.class);
        if (!Long.toString(tenantId).equals(applied)) {
            throw new IllegalStateException("Certificate tenant scope was not established.");
        }
    }

    Optional<PersonRow> personForMutation(long tenantId, UUID personPublicId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT person.person_id,person.public_id,person.version,person.display_name,
                       worker.worker_id
                  FROM ppl_persons person
                  LEFT JOIN LATERAL (
                       SELECT candidate.worker_id
                         FROM ppl_workers candidate
                        WHERE candidate.tenant_id=person.tenant_id
                          AND candidate.person_id=person.person_id
                        ORDER BY (candidate.worker_status IN ('ACTIVE','LEAVE')) DESC,
                                 candidate.worker_id
                        LIMIT 1
                  ) worker ON TRUE
                 WHERE person.tenant_id=:tenant AND person.public_id=:person
                 FOR UPDATE OF person
                """, new MapSqlParameterSource()
                        .addValue("tenant", tenantId)
                        .addValue("person", personPublicId),
                (result, ignored) -> new PersonRow(
                        result.getLong("person_id"),
                        result.getObject("public_id", UUID.class),
                        result.getLong("version"),
                        result.getString("display_name"),
                        nullableLong(result, "worker_id")))
                .stream().findFirst();
    }

    RequestRow insertRequest(
            long tenantId,
            UUID requestPublicId,
            long requesterPersonId,
            long subjectPersonId,
            String certificateType,
            String locale,
            String purpose,
            UUID templatePublicId,
            int templateVersion,
            UUID correlationId,
            long actorUserId) {
        List<RequestRow> rows = jdbc.query("""
                INSERT INTO ppl_certificate_requests(
                    public_id,tenant_id,requester_person_id,subject_person_id,
                    certificate_type,locale,purpose_code,template_public_id,
                    template_version,correlation_id,created_by,updated_by)
                VALUES(:publicId,:tenant,:requester,:subject,:certificateType,:locale,
                    :purpose,:template,:templateVersion,:correlation,:actor,:actor)
                RETURNING *
                """, params(tenantId)
                .addValue("publicId", requestPublicId)
                .addValue("requester", requesterPersonId)
                .addValue("subject", subjectPersonId)
                .addValue("certificateType", certificateType)
                .addValue("locale", locale)
                .addValue("purpose", purpose)
                .addValue("template", templatePublicId)
                .addValue("templateVersion", templateVersion)
                .addValue("correlation", correlationId)
                .addValue("actor", actorUserId), this::requestRow);
        if (rows.size() != 1) throw new IllegalStateException(
                "Certificate request insert failed.");
        return rows.getFirst();
    }

    RequestRow markValidating(
            long tenantId,
            RequestRow request,
            long actorUserId,
            Instant now) {
        List<RequestRow> rows = jdbc.query("""
                UPDATE ppl_certificate_requests
                   SET lifecycle_state='VALIDATING',version=version+1,
                       updated_at=:now,updated_by=:actor
                 WHERE tenant_id=:tenant AND certificate_request_id=:requestId
                   AND lifecycle_state='REQUESTED' AND version=:version
                RETURNING *
                """, params(tenantId)
                .addValue("requestId", request.requestId())
                .addValue("version", request.version())
                .addValue("actor", actorUserId)
                .addValue("now", Timestamp.from(now)), this::requestRow);
        if (rows.size() != 1) throw new IllegalStateException(
                "Certificate request validation transition failed.");
        return rows.getFirst();
    }

    IssuedRow insertIssued(
            long tenantId,
            UUID certificatePublicId,
            RequestRow request,
            CertificateDtos.ArtifactResult artifact,
            long actorUserId) {
        List<IssuedRow> rows = jdbc.query("""
                INSERT INTO ppl_issued_certificates(
                    public_id,tenant_id,certificate_request_id,object_public_id,
                    object_version,content_sha256,verification_token_hash,
                    issued_at,expires_at,created_by,updated_by)
                VALUES(:publicId,:tenant,:requestId,:objectId,:objectVersion,
                    :contentDigest,:verificationDigest,:issuedAt,:expiresAt,:actor,:actor)
                RETURNING *
                """, params(tenantId)
                .addValue("publicId", certificatePublicId)
                .addValue("requestId", request.requestId())
                .addValue("objectId", artifact.objectPublicId())
                .addValue("objectVersion", artifact.objectVersion())
                .addValue("contentDigest", artifact.contentSha256())
                .addValue("verificationDigest", artifact.verificationTokenHash())
                .addValue("issuedAt", Timestamp.from(artifact.issuedAt()))
                .addValue("expiresAt", artifact.expiresAt() == null
                        ? null : Timestamp.from(artifact.expiresAt()))
                .addValue("actor", actorUserId), this::issuedRow);
        if (rows.size() != 1) throw new IllegalStateException(
                "Issued certificate metadata insert failed.");
        return rows.getFirst();
    }

    RequestRow markIssued(
            long tenantId,
            RequestRow request,
            long actorUserId,
            Instant completedAt) {
        List<RequestRow> rows = jdbc.query("""
                UPDATE ppl_certificate_requests
                   SET lifecycle_state='ISSUED',completed_at=:completed,
                       version=version+1,updated_at=:completed,updated_by=:actor
                 WHERE tenant_id=:tenant AND certificate_request_id=:requestId
                   AND lifecycle_state='VALIDATING' AND version=:version
                RETURNING *
                """, params(tenantId)
                .addValue("requestId", request.requestId())
                .addValue("version", request.version())
                .addValue("actor", actorUserId)
                .addValue("completed", Timestamp.from(completedAt)), this::requestRow);
        if (rows.size() != 1) throw new IllegalStateException(
                "Certificate request issuance transition failed.");
        return rows.getFirst();
    }

    Optional<ReceiptRow> insertReceipt(
            long tenantId,
            UUID receiptId,
            String commandType,
            String idempotencyKey,
            String requestDigest,
            UUID actorPublicId,
            UUID subjectPublicId,
            String populationDigest,
            long fieldRevision,
            String purpose,
            long authorizationRevision,
            String contextDigest,
            UUID requestPublicId,
            long expectedVersion,
            UUID correlationId,
            Instant now) {
        List<ReceiptRow> rows = jdbc.query("""
                INSERT INTO ppl_command_receipts(
                    command_receipt_id,tenant_id,command_type,idempotency_key,
                    request_hash,actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,correlation_id,created_at,updated_at)
                VALUES(:receipt,:tenant,:commandType,:idempotency,:digest,:actor,
                    'REQUEST_CERTIFICATE',:subject,:population,:fieldRevision,:purpose,
                    :authorizationRevision,:contextDigest,'CERTIFICATE_REQUEST',:target,
                    :expectedVersion,'RUNNING',:correlation,:now,:now)
                ON CONFLICT DO NOTHING
                RETURNING *
                """, params(tenantId)
                .addValue("receipt", receiptId)
                .addValue("commandType", commandType)
                .addValue("idempotency", idempotencyKey)
                .addValue("digest", requestDigest)
                .addValue("actor", actorPublicId)
                .addValue("subject", subjectPublicId)
                .addValue("population", populationDigest)
                .addValue("fieldRevision", fieldRevision)
                .addValue("purpose", purpose)
                .addValue("authorizationRevision", authorizationRevision)
                .addValue("contextDigest", contextDigest)
                .addValue("target", requestPublicId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("correlation", correlationId)
                .addValue("now", Timestamp.from(now)), this::receiptRow);
        return rows.stream().findFirst();
    }

    Optional<ReceiptRow> receiptByKey(
            long tenantId,
            String commandType,
            String idempotencyKey) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND command_type=:commandType
                   AND idempotency_key=:idempotency
                 FOR UPDATE
                """, params(tenantId)
                        .addValue("commandType", commandType)
                        .addValue("idempotency", idempotencyKey), this::receiptRow)
                .stream().findFirst();
    }

    ReceiptRow completeReceipt(
            long tenantId,
            UUID receiptId,
            UUID requestPublicId,
            UUID certificatePublicId,
            long requestVersion,
            Instant now) {
        List<ReceiptRow> rows = jdbc.query("""
                UPDATE ppl_command_receipts
                   SET lifecycle_state='SUCCEEDED',result_code='CERTIFICATE_ISSUED',
                       result_reference=CAST(:result AS jsonb),completed_at=:now,
                       updated_at=:now
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                   AND lifecycle_state='RUNNING'
                RETURNING *
                """, params(tenantId)
                .addValue("receipt", receiptId)
                .addValue("result", json(Map.of(
                        "requestPublicId", requestPublicId.toString(),
                        "certificatePublicId", certificatePublicId.toString(),
                        "requestVersion", requestVersion)))
                .addValue("now", Timestamp.from(now)), this::receiptRow);
        if (rows.size() != 1) throw new IllegalStateException(
                "Certificate command receipt completion failed.");
        return rows.getFirst();
    }

    void appendAudit(
            long tenantId,
            long actorUserId,
            UUID requestPublicId,
            UUID correlationId,
            String purpose,
            Instant now) {
        int inserted = jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                VALUES(:event,:tenant,'USER',:actor,'certificate.issued',
                    'CERTIFICATE_REQUEST',:target,'SUCCESS',:correlation,
                    CAST(:snapshot AS jsonb),:now)
                """, params(tenantId)
                .addValue("event", UUID.randomUUID())
                .addValue("actor", Long.toString(actorUserId))
                .addValue("target", requestPublicId.toString())
                .addValue("correlation", correlationId.toString())
                .addValue("snapshot", json(Map.of(
                        "lifecycleState", "ISSUED", "purposeCode", purpose)))
                .addValue("now", Timestamp.from(now)));
        if (inserted != 1) throw new IllegalStateException(
                "Certificate audit insert failed.");
    }

    void appendIssuedEvent(
            long tenantId,
            UUID eventId,
            UUID requestPublicId,
            Map<String, ?> sixFieldPayload,
            UUID correlationId,
            Instant occurredAt) {
        if (!sixFieldPayload.keySet().equals(Set.of(
                "requestPublicId", "certificatePublicId", "subjectPersonPublicId",
                "certificateType", "issuedAt", "expiresAt"))) {
            throw new IllegalArgumentException(
                    "CertificateIssued.v1 payload must contain exactly six fields.");
        }
        int inserted = jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                    payload,correlation_id,occurred_at)
                VALUES(:event,:tenant,'CERTIFICATE_REQUEST',:aggregate,
                    'CertificateIssued.v1',CAST(:payload AS jsonb),:correlation,:now)
                ON CONFLICT (event_id) DO NOTHING
                """, params(tenantId)
                .addValue("event", eventId)
                .addValue("aggregate", requestPublicId.toString())
                .addValue("payload", json(sixFieldPayload))
                .addValue("correlation", correlationId.toString())
                .addValue("now", Timestamp.from(occurredAt)));
        if (inserted != 1) throw new IllegalStateException(
                "CertificateIssued.v1 outbox insert failed.");
    }

    List<ProjectionRow> selfRequests(
            long tenantId,
            UUID requesterPersonPublicId,
            Instant asOf) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT request.public_id,subject.public_id AS subject_public_id,
                       subject.display_name,request.certificate_type,
                       request.lifecycle_state,request.template_public_id,
                       request.template_version,request.version,request.requested_at,
                       receipt.command_receipt_id,receipt.lifecycle_state AS receipt_state,
                       receipt.created_at AS receipt_created_at,
                       receipt.field_policy_revision,
                       NULL::UUID AS certificate_public_id,
                       NULL::UUID AS object_public_id,
                       NULL::INTEGER AS object_version,
                       NULL::CHAR(64) AS content_sha256,
                       NULL::TIMESTAMPTZ AS issued_at,
                       NULL::TIMESTAMPTZ AS expires_at
                  FROM ppl_certificate_requests request
                  JOIN ppl_persons subject
                    ON subject.tenant_id=request.tenant_id
                   AND subject.person_id=request.subject_person_id
                  LEFT JOIN ppl_command_receipts receipt
                    ON receipt.tenant_id=request.tenant_id
                   AND receipt.command_type='CERTIFICATE_REQUEST'
                   AND receipt.target_public_id=request.public_id
                 WHERE request.tenant_id=:tenant
                   AND request.requester_person_id=(
                       SELECT person_id FROM ppl_persons
                        WHERE tenant_id=:tenant AND public_id=:requester)
                   AND request.subject_person_id=(
                       SELECT person_id FROM ppl_persons
                        WHERE tenant_id=:tenant AND public_id=:requester)
                   AND request.purpose_code='EMPLOYEE_SELF_SERVICE'
                   AND request.requested_at<=:asOf
                 ORDER BY request.public_id
                """, params(tenantId)
                .addValue("requester", requesterPersonPublicId)
                .addValue("asOf", Timestamp.from(asOf)), this::projectionRow);
    }

    List<ProjectionRow> operationsCertificates(
            long tenantId,
            Instant asOf,
            HcmPopulationRepository.PopulationScope scope) {
        establishTenant(tenantId);
        Set<UUID> organizations = scope.organizationIds().isEmpty()
                ? Set.of(new UUID(0, 0)) : scope.organizationIds();
        return jdbc.query("""
                SELECT request.public_id,subject.public_id AS subject_public_id,
                       subject.display_name,request.certificate_type,
                       issued.lifecycle_state,request.template_public_id,
                       request.template_version,request.version,request.requested_at,
                       issued.public_id AS certificate_public_id,
                       issued.object_public_id,issued.object_version,
                       issued.content_sha256,issued.issued_at,issued.expires_at,
                       NULL::UUID AS command_receipt_id,NULL::VARCHAR AS receipt_state,
                       NULL::TIMESTAMPTZ AS receipt_created_at,
                       NULL::BIGINT AS field_policy_revision
                  FROM ppl_issued_certificates issued
                  JOIN ppl_certificate_requests request
                    ON request.tenant_id=issued.tenant_id
                   AND request.certificate_request_id=issued.certificate_request_id
                  JOIN ppl_persons subject
                    ON subject.tenant_id=request.tenant_id
                   AND subject.person_id=request.subject_person_id
                  JOIN LATERAL (
                       SELECT worker.worker_id,assignment.manager_assignment_key,
                              organization.public_id AS organization_public_id
                         FROM ppl_workers worker
                         JOIN ppl_work_relationships relationship
                           ON relationship.tenant_id=worker.tenant_id
                          AND relationship.worker_id=worker.worker_id
                          AND relationship.start_date<=CURRENT_DATE
                          AND (relationship.end_date IS NULL
                               OR relationship.end_date>=CURRENT_DATE)
                         JOIN ppl_assignments assignment
                           ON assignment.tenant_id=relationship.tenant_id
                          AND assignment.work_relationship_id=relationship.work_relationship_id
                         LEFT JOIN ppl_organizations organization
                           ON organization.tenant_id=assignment.tenant_id
                          AND organization.organization_id=assignment.organization_id
                        WHERE worker.tenant_id=subject.tenant_id
                          AND worker.person_id=subject.person_id
                          AND worker.worker_status IN ('ACTIVE','LEAVE')
                          AND assignment.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                          AND assignment.effective_start_date<=CURRENT_DATE
                          AND (assignment.effective_end_date IS NULL
                               OR assignment.effective_end_date>=CURRENT_DATE)
                          AND worker.worker_id<>:actorWorker
                          AND (:tenantWide
                               OR assignment.manager_assignment_key=:managerAssignment
                               OR organization.public_id IN (:organizations))
                        ORDER BY assignment.primary_assignment DESC,
                                 assignment.effective_start_date DESC,worker.worker_id
                        LIMIT 1
                  ) workforce ON TRUE
                 WHERE issued.tenant_id=:tenant AND issued.issued_at<=:asOf
                 ORDER BY request.public_id
                """, params(tenantId)
                .addValue("asOf", Timestamp.from(asOf))
                .addValue("actorWorker", scope.actorWorkerId())
                .addValue("tenantWide", scope.tenantWide())
                .addValue("managerAssignment", scope.managerAssignmentKey())
                .addValue("organizations", organizations), this::projectionRow);
    }

    private MapSqlParameterSource params(long tenantId) {
        return new MapSqlParameterSource("tenant", tenantId);
    }

    private RequestRow requestRow(ResultSet result, int ignored) throws SQLException {
        return new RequestRow(
                result.getLong("certificate_request_id"),
                result.getObject("public_id", UUID.class),
                result.getLong("requester_person_id"),
                result.getLong("subject_person_id"),
                result.getString("certificate_type"),
                result.getString("locale"),
                result.getString("purpose_code"),
                result.getString("lifecycle_state"),
                result.getObject("template_public_id", UUID.class),
                result.getInt("template_version"),
                result.getObject("correlation_id", UUID.class),
                result.getLong("version"),
                result.getTimestamp("requested_at").toInstant(),
                instant(result, "completed_at"));
    }

    private IssuedRow issuedRow(ResultSet result, int ignored) throws SQLException {
        return new IssuedRow(
                result.getLong("issued_certificate_id"),
                result.getObject("public_id", UUID.class),
                result.getLong("certificate_request_id"),
                result.getObject("object_public_id", UUID.class),
                result.getInt("object_version"),
                result.getString("content_sha256"),
                result.getString("verification_token_hash"),
                result.getString("lifecycle_state"),
                result.getTimestamp("issued_at").toInstant(),
                instant(result, "expires_at"));
    }

    private ReceiptRow receiptRow(ResultSet result, int ignored) throws SQLException {
        return new ReceiptRow(
                result.getObject("command_receipt_id", UUID.class),
                result.getString("command_type"),
                result.getString("idempotency_key"),
                result.getString("request_hash"),
                result.getObject("actor_public_id", UUID.class),
                result.getString("originating_action"),
                result.getObject("subject_principal_public_id", UUID.class),
                result.getString("population_scope_digest"),
                result.getLong("field_policy_revision"),
                result.getString("purpose_code"),
                result.getLong("authorization_revision"),
                result.getString("originating_context_digest"),
                result.getObject("target_public_id", UUID.class),
                (Long) result.getObject("expected_version"),
                result.getString("lifecycle_state"),
                result.getObject("correlation_id", UUID.class),
                result.getString("result_reference"),
                result.getTimestamp("created_at").toInstant());
    }

    private ProjectionRow projectionRow(ResultSet result, int ignored)
            throws SQLException {
        return new ProjectionRow(
                result.getObject("public_id", UUID.class),
                result.getObject("subject_public_id", UUID.class),
                result.getString("display_name"),
                result.getString("certificate_type"),
                result.getString("lifecycle_state"),
                result.getObject("template_public_id", UUID.class),
                result.getInt("template_version"),
                result.getLong("version"),
                result.getTimestamp("requested_at").toInstant(),
                uuid(result, "command_receipt_id"),
                result.getString("receipt_state"),
                instant(result, "receipt_created_at"),
                nullableLong(result, "field_policy_revision"),
                uuid(result, "certificate_public_id"),
                uuid(result, "object_public_id"),
                nullableInteger(result, "object_version"),
                result.getString("content_sha256"),
                instant(result, "issued_at"),
                instant(result, "expires_at"));
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        Number value = (Number) result.getObject(column);
        return value == null ? null : value.longValue();
    }

    private Integer nullableInteger(ResultSet result, String column) throws SQLException {
        Number value = (Number) result.getObject(column);
        return value == null ? null : value.intValue();
    }

    private UUID uuid(ResultSet result, String column) throws SQLException {
        return result.getObject(column, UUID.class);
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Certificate persistence serialization failed.", exception);
        }
    }

    record PersonRow(
            long personId,
            UUID publicId,
            long version,
            String displayName,
            Long workerId) {
    }

    record RequestRow(
            long requestId,
            UUID publicId,
            long requesterPersonId,
            long subjectPersonId,
            String certificateType,
            String locale,
            String purpose,
            String lifecycleState,
            UUID templatePublicId,
            int templateVersion,
            UUID correlationId,
            long version,
            Instant requestedAt,
            Instant completedAt) {
    }

    record IssuedRow(
            long issuedId,
            UUID publicId,
            long requestId,
            UUID objectPublicId,
            int objectVersion,
            String contentSha256,
            String verificationTokenHash,
            String lifecycleState,
            Instant issuedAt,
            Instant expiresAt) {
    }

    record ReceiptRow(
            UUID receiptId,
            String commandType,
            String idempotencyKey,
            String requestDigest,
            UUID actorPublicId,
            String action,
            UUID subjectPublicId,
            String populationDigest,
            long fieldRevision,
            String purpose,
            long authorizationRevision,
            String contextDigest,
            UUID targetPublicId,
            Long expectedVersion,
            String lifecycleState,
            UUID correlationId,
            String resultReference,
            Instant createdAt) {
    }

    record ProjectionRow(
            UUID requestPublicId,
            UUID subjectPublicId,
            String displayName,
            String certificateType,
            String state,
            UUID templatePublicId,
            int templateVersion,
            long requestVersion,
            Instant requestedAt,
            UUID receiptId,
            String receiptState,
            Instant receiptCreatedAt,
            Long receiptRevision,
            UUID certificatePublicId,
            UUID objectPublicId,
            Integer objectVersion,
            String contentSha256,
            Instant issuedAt,
            Instant expiresAt) {
    }
}
