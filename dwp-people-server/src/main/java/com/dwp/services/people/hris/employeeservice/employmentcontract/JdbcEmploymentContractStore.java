package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.services.people.hris.contracts.hrm011.Hrm011EventOutbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** RLS-scoped V59 persistence for PEP-HRM-011. */
@Repository
@ConditionalOnProperty(prefix = EmploymentContractRolloutGate.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class JdbcEmploymentContractStore implements EmploymentContractStore {

    private static final String COMMAND = "EMPLOYMENT_CONTRACT_ISSUE";
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    JdbcEmploymentContractStore(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<Receipt> receipt(long tenantId, String idempotencyKey) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND command_type=:command
                   AND idempotency_key=:idempotency
                 FOR UPDATE
                """, params(tenantId)
                .addValue("command", COMMAND)
                .addValue("idempotency", idempotencyKey), this::receiptRow)
                .stream().findFirst();
    }

    @Override
    public Optional<Receipt> reserve(Reservation value) {
        establishTenant(value.tenantId());
        return jdbc.query("""
                INSERT INTO ppl_command_receipts(
                    command_receipt_id,tenant_id,command_type,idempotency_key,
                    request_hash,actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,correlation_id,created_at,updated_at)
                VALUES(:receipt,:tenant,:command,:idempotency,:digest,:actor,
                    'ISSUE_EMPLOYMENT_CONTRACT',:subject,:population,:fieldRevision,
                    'HR_LIFECYCLE',:authorizationRevision,:contextDigest,
                    'WORK_RELATIONSHIP',:subject,:expectedVersion,'RUNNING',
                    :correlation,:now,:now)
                ON CONFLICT DO NOTHING
                RETURNING *
                """, params(value.tenantId())
                .addValue("receipt", value.receiptId())
                .addValue("command", COMMAND)
                .addValue("idempotency", value.idempotencyKey())
                .addValue("digest", value.requestDigest())
                .addValue("actor", value.actorPublicId())
                .addValue("subject", value.subjectPublicId())
                .addValue("population", value.populationDigest())
                .addValue("fieldRevision", value.fieldRevision())
                .addValue("authorizationRevision", value.authorizationRevision())
                .addValue("contextDigest", value.contextDigest())
                .addValue("expectedVersion", value.expectedVersion())
                .addValue("correlation", value.correlationId())
                .addValue("now", Timestamp.from(value.createdAt())), this::receiptRow)
                .stream().findFirst();
    }

    @Override
    public List<Aggregate> lockAggregate(
            long tenantId, UUID relationshipPublicId, LocalDate effectiveOn) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT relationship.work_relationship_id,
                       assignment.assignment_id, basis.compensation_basis_id,
                       worker.worker_id, person.public_id AS subject_person_public_id,
                       relationship.public_id AS relationship_public_id,
                       relationship.version AS relationship_version,
                       worker.public_id AS worker_public_id,
                       employer.public_id AS employer_public_id,
                       relationship.start_date AS relationship_from,
                       relationship.end_date AS relationship_to,
                       assignment.public_id AS assignment_public_id,
                       assignment.version AS assignment_version,
                       assignment.effective_start_date AS assignment_from,
                       assignment.effective_end_date AS assignment_to,
                       basis.public_id AS basis_public_id,
                       basis.version AS basis_version,basis.basis_type,basis.amount,
                       basis.currency_code,basis.frequency_code,
                       basis.valid_from AS basis_from,basis.valid_to AS basis_to
                  FROM ppl_work_relationships relationship
                  JOIN ppl_workers worker
                    ON worker.tenant_id=relationship.tenant_id
                   AND worker.worker_id=relationship.worker_id
                  JOIN ppl_persons person
                    ON person.tenant_id=worker.tenant_id
                   AND person.person_id=worker.person_id
                  JOIN ppl_legal_employers employer
                    ON employer.tenant_id=relationship.tenant_id
                   AND employer.legal_employer_id=relationship.legal_employer_id
                  JOIN ppl_assignments assignment
                    ON assignment.tenant_id=relationship.tenant_id
                   AND assignment.work_relationship_id=relationship.work_relationship_id
                   AND assignment.primary_assignment=TRUE
                   AND assignment.assignment_status='ACTIVE'
                   AND assignment.effective_start_date<=:effectiveOn
                   AND (assignment.effective_end_date IS NULL
                        OR assignment.effective_end_date>:effectiveOn)
                  JOIN ppl_compensation_basis basis
                    ON basis.tenant_id=assignment.tenant_id
                   AND basis.assignment_id=assignment.assignment_id
                   AND basis.valid_from<=:effectiveOn
                   AND (basis.valid_to IS NULL OR basis.valid_to>:effectiveOn)
                 WHERE relationship.tenant_id=:tenant
                   AND relationship.public_id=:relationship
                   AND relationship.start_date<=:effectiveOn
                   AND (relationship.end_date IS NULL
                        OR relationship.end_date>:effectiveOn)
                 FOR UPDATE OF relationship,assignment,basis
                """, params(tenantId)
                .addValue("relationship", relationshipPublicId)
                .addValue("effectiveOn", effectiveOn), this::aggregateRow);
    }

    @Override
    public IssuedContract insertIssued(Issue value) {
        var aggregate = value.aggregate();
        var relationship = aggregate.relationship();
        var assignment = aggregate.assignment();
        var basis = aggregate.compensationBasis();
        var template = value.template();
        var artifact = value.artifact();
        List<IssuedContract> rows = jdbc.query("""
                INSERT INTO ppl_employment_contracts(
                    public_id,tenant_id,work_relationship_id,relationship_version,
                    assignment_id,assignment_version,compensation_basis_id,
                    compensation_basis_version,contract_type,lifecycle_state,locale,
                    template_public_id,template_version,template_content_schema_version,
                    template_source_revision,template_published_at,template_payload_digest,
                    template_content_object_public_id,template_content_object_version,
                    template_content_sha256,template_approval_receipt_id,
                    snapshot_basis_type,snapshot_amount,snapshot_currency_code,
                    snapshot_frequency_code,generation_snapshot_digest,
                    artifact_request_id,artifact_provider_receipt_id,
                    document_object_public_id,document_object_version,
                    document_content_sha256,valid_from,valid_to,correlation_id,
                    version,issued_at,created_by,updated_by)
                VALUES(:publicId,:tenant,:relationshipId,:relationshipVersion,
                    :assignmentId,:assignmentVersion,:basisId,:basisVersion,
                    :contractType,'ISSUED',:locale,:templateId,:templateVersion,
                    :schemaVersion,:sourceRevision,:publishedAt,:templateDigest,
                    :contentId,:contentVersion,:contentDigest,:approvalReceipt,
                    :basisType,:amount,:currency,:frequency,:snapshotDigest,
                    :artifactRequest,:providerReceipt,:documentId,:documentVersion,
                    :documentDigest,:validFrom,:validTo,:correlation,1,:issuedAt,
                    :actor,:actor)
                RETURNING public_id,version,issued_at
                """, params(value.tenantId())
                .addValue("publicId", value.contractPublicId())
                .addValue("relationshipId", aggregate.workRelationshipId())
                .addValue("relationshipVersion", relationship.resourceVersion())
                .addValue("assignmentId", aggregate.assignmentId())
                .addValue("assignmentVersion", assignment.assignmentVersion())
                .addValue("basisId", aggregate.compensationBasisId())
                .addValue("basisVersion", basis.compensationBasisVersion())
                .addValue("contractType", template.contractType())
                .addValue("locale", value.locale())
                .addValue("templateId", template.templatePublicId())
                .addValue("templateVersion", template.templateVersion())
                .addValue("schemaVersion", template.contentSchemaVersion())
                .addValue("sourceRevision", template.sourceRevision())
                .addValue("publishedAt", Timestamp.from(template.publishedAt()))
                .addValue("templateDigest", template.payloadDigest())
                .addValue("contentId", template.templateContentReference().objectPublicId())
                .addValue("contentVersion", template.templateContentReference().objectVersion())
                .addValue("contentDigest", template.templateContentReference().contentSha256())
                .addValue("approvalReceipt", template.approvalReceiptId())
                .addValue("basisType", basis.basisType())
                .addValue("amount", new BigDecimal(basis.amountCanonicalDecimal()))
                .addValue("currency", basis.currency())
                .addValue("frequency", basis.frequency())
                .addValue("snapshotDigest", value.generationSnapshotDigest())
                .addValue("artifactRequest", artifact.artifactRequestId())
                .addValue("providerReceipt", artifact.providerReceiptId())
                .addValue("documentId", artifact.documentReference().objectPublicId())
                .addValue("documentVersion", artifact.documentReference().objectVersion())
                .addValue("documentDigest", artifact.documentReference().contentSha256())
                .addValue("validFrom", value.validFrom())
                .addValue("validTo", value.validTo())
                .addValue("correlation", value.correlationId())
                .addValue("issuedAt", Timestamp.from(artifact.issuedAt()))
                .addValue("actor", value.actorUserId()),
                (result, ignored) -> new IssuedContract(
                        result.getObject("public_id", UUID.class),
                        result.getLong("version"),
                        result.getTimestamp("issued_at").toInstant()));
        if (rows.size() != 1) throw new IllegalStateException(
                "Employment contract insert failed.");
        return rows.getFirst();
    }

    @Override
    public Receipt complete(
            long tenantId, UUID receiptId, UUID contractPublicId,
            long contractVersion, Instant completedAt) {
        List<Receipt> rows = jdbc.query("""
                UPDATE ppl_command_receipts
                   SET lifecycle_state='SUCCEEDED',
                       result_code='EMPLOYMENT_CONTRACT_ISSUED',
                       result_reference=CAST(:result AS jsonb),completed_at=:now,
                       updated_at=:now
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                   AND lifecycle_state='RUNNING'
                RETURNING *
                """, params(tenantId)
                .addValue("receipt", receiptId)
                .addValue("result", json(Map.of(
                        "contractPublicId", contractPublicId.toString(),
                        "contractVersion", contractVersion)))
                .addValue("now", Timestamp.from(completedAt)), this::receiptRow);
        if (rows.size() != 1) throw new IllegalStateException(
                "Employment-contract receipt completion failed.");
        return rows.getFirst();
    }

    @Override
    public void appendSuccessAudit(
            long tenantId, long actorUserId, UUID contractPublicId,
            UUID correlationId, Instant occurredAt) {
        int count = jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                VALUES(:event,:tenant,'USER',:actor,'employment-contract.issued',
                    'EMPLOYMENT_CONTRACT',:target,'SUCCESS',:correlation,
                    CAST(:snapshot AS jsonb),:occurredAt)
                """, params(tenantId)
                .addValue("event", UUID.randomUUID())
                .addValue("actor", Long.toString(actorUserId))
                .addValue("target", contractPublicId.toString())
                .addValue("correlation", correlationId.toString())
                .addValue("snapshot", json(Map.of("lifecycleState", "ISSUED")))
                .addValue("occurredAt", Timestamp.from(occurredAt)));
        if (count != 1) throw new IllegalStateException(
                "Employment-contract audit insert failed.");
    }

    @Override
    public void appendOutbox(
            long tenantId, DomainEventEnvelope envelope, String payloadDigest) {
        Hrm011EventOutbox.requireEquality(envelope);
        if (payloadDigest == null || !payloadDigest.matches("^[0-9a-f]{64}$")
                || envelope.data() == null
                || !payloadDigest.equals(envelope.data().path("payloadDigest").asText(null))) {
            throw EmploymentContractException.conflict(
                    "EMPLOYMENT_CONTRACT_OUTBOX_DIGEST_MISMATCH",
                    "The XCON-026 payload digest is missing or mismatched.");
        }
        String payload = json(envelope.data());
        int count = jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                    payload,correlation_id,occurred_at)
                VALUES(:event,:tenant,:aggregateType,:aggregateId,:eventType,
                    CAST(:payload AS jsonb),:correlation,:occurredAt)
                ON CONFLICT (event_id) DO NOTHING
                """, params(tenantId)
                .addValue("event", envelope.id())
                .addValue("aggregateType", envelope.aggregateType())
                .addValue("aggregateId", envelope.aggregateId())
                .addValue("eventType", envelope.type())
                .addValue("payload", payload)
                .addValue("correlation", envelope.correlationId())
                .addValue("occurredAt", Timestamp.from(envelope.time())));
        if (count == 1) return;
        Integer exact = jdbc.queryForObject("""
                SELECT count(*) FROM sys_people_outbox_events
                 WHERE event_id=:event AND tenant_id=:tenant
                   AND aggregate_type=:aggregateType AND aggregate_id=:aggregateId
                   AND event_type=:eventType AND correlation_id=:correlation
                   AND occurred_at=:occurredAt
                   AND payload=CAST(:payload AS jsonb)
                   AND payload->>'payloadDigest'=:payloadDigest
                """, params(tenantId)
                .addValue("event", envelope.id())
                .addValue("aggregateType", envelope.aggregateType())
                .addValue("aggregateId", envelope.aggregateId())
                .addValue("eventType", envelope.type())
                .addValue("payload", payload)
                .addValue("payloadDigest", payloadDigest)
                .addValue("correlation", envelope.correlationId())
                .addValue("occurredAt", Timestamp.from(envelope.time())), Integer.class);
        if (exact == null || exact != 1) {
            throw EmploymentContractException.conflict(
                    "EMPLOYMENT_CONTRACT_OUTBOX_CONFLICT",
                    "XCON-026 outbox event conflicts with an existing event.");
        }
    }

    private Aggregate aggregateRow(ResultSet result, int ignored) throws SQLException {
        UUID assignmentPublicId = result.getObject("assignment_public_id", UUID.class);
        UUID basisPublicId = result.getObject("basis_public_id", UUID.class);
        BigDecimal amount = result.getBigDecimal("amount");
        String basisType = result.getString("basis_type");
        String currency = result.getString("currency_code");
        String frequency = result.getString("frequency_code");
        LocalDate assignmentFrom = result.getObject("assignment_from", LocalDate.class);
        LocalDate basisFrom = result.getObject("basis_from", LocalDate.class);
        long assignmentVersion = result.getLong("assignment_version");
        long basisVersion = result.getLong("basis_version");
        if (assignmentPublicId == null || assignmentFrom == null || assignmentVersion < 1
                || basisPublicId == null || amount == null || amount.signum() < 0
                || basisType == null || currency == null || frequency == null
                || basisFrom == null || basisVersion < 1) {
            throw EmploymentContractException.unavailable(
                    "The authoritative assignment or compensation basis is incomplete.");
        }
        EmploymentContractDtos.AssignmentSnapshot assignment;
        EmploymentContractDtos.CompensationBasisSnapshot basis;
        try {
            assignment = new EmploymentContractDtos.AssignmentSnapshot(
                    assignmentPublicId, assignmentVersion, assignmentFrom,
                    result.getObject("assignment_to", LocalDate.class));
            basis = new EmploymentContractDtos.CompensationBasisSnapshot(
                    basisPublicId, basisVersion, basisType,
                    amount.signum() == 0 ? "0" : amount.stripTrailingZeros().toPlainString(),
                    currency, frequency, basisFrom,
                    result.getObject("basis_to", LocalDate.class));
        } catch (RuntimeException exception) {
            throw EmploymentContractException.unavailable(
                    "The authoritative assignment or compensation basis is malformed.");
        }
        return new Aggregate(
                result.getLong("work_relationship_id"),
                result.getLong("assignment_id"),
                result.getLong("compensation_basis_id"),
                result.getLong("worker_id"),
                result.getObject("subject_person_public_id", UUID.class),
                new EmploymentContractDtos.RelationshipSnapshot(
                        result.getObject("relationship_public_id", UUID.class),
                        result.getLong("relationship_version"),
                        result.getObject("worker_public_id", UUID.class),
                        result.getObject("employer_public_id", UUID.class),
                        result.getObject("relationship_from", LocalDate.class),
                        result.getObject("relationship_to", LocalDate.class)),
                assignment, basis);
    }

    private Receipt receiptRow(ResultSet result, int ignored) throws SQLException {
        return new Receipt(
                result.getObject("command_receipt_id", UUID.class),
                result.getString("request_hash"),
                result.getObject("actor_public_id", UUID.class),
                result.getObject("subject_principal_public_id", UUID.class),
                result.getString("purpose_code"),
                result.getLong("expected_version"),
                result.getObject("correlation_id", UUID.class),
                result.getString("lifecycle_state"),
                result.getString("result_reference"),
                result.getString("population_scope_digest"),
                result.getLong("field_policy_revision"),
                result.getLong("authorization_revision"),
                result.getTimestamp("created_at").toInstant());
    }

    private void establishTenant(long tenantId) {
        if (tenantId < 1) throw new IllegalArgumentException("tenantId must be positive");
        String applied = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id',CAST(:tenant AS VARCHAR),TRUE)",
                new MapSqlParameterSource("tenant", tenantId), String.class);
        if (!Long.toString(tenantId).equals(applied)) {
            throw new IllegalStateException("HRM-011 tenant scope was not established.");
        }
    }

    private MapSqlParameterSource params(long tenantId) {
        establishTenant(tenantId);
        return new MapSqlParameterSource("tenant", tenantId);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRM-011 JSON serialization failed.", exception);
        }
    }
}
