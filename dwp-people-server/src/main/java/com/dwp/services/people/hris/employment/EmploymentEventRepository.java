package com.dwp.services.people.hris.employment;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Tenant-bound persistence boundary for the canonical employment-event ledger. */
@Repository
class EmploymentEventRepository {

    private static final String EVENT_SELECT = """
            SELECT event.assignment_event_id,event.public_id,event.tenant_id,
                   event.subject_person_id,event.target_worker_id,event.event_type,
                   event.effective_date,event.command_context::text,event.event_payload::text,
                   event.lifecycle_state,event.impact_snapshot::text,
                   event.input_snapshot_hash,event.impact_snapshot_hash,
                   event.expected_owner_version,event.version,event.created_by,
                   event.correlation_id AS originating_correlation_id,
                   event.approval_request_id,event.approval_decision_event_id,
                   event.approval_decision_revision,event.approval_payload_hash,
                   event.validated_at,event.submitted_at,event.approved_at,event.published_at,
                   event.created_at,event.updated_at,
                   worker.public_id AS published_worker_public_id,
                   relationship.public_id AS published_relationship_public_id,
                   assignment.public_id AS published_assignment_public_id
              FROM ppl_assignment_events event
              LEFT JOIN ppl_workers worker
                ON worker.tenant_id=event.tenant_id
               AND worker.worker_id=event.target_worker_id
              LEFT JOIN ppl_work_relationships relationship
                ON relationship.tenant_id=event.tenant_id
               AND relationship.work_relationship_id=event.published_work_relationship_id
              LEFT JOIN ppl_assignments assignment
                ON assignment.tenant_id=event.tenant_id
               AND assignment.assignment_id=event.published_assignment_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EmploymentEventPublicationEvidence evidence;

    EmploymentEventRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        DomainEventRecorder recorder = new DomainEventRecorder(
                new DomainEventOutboxRepository(jdbc, mapper), contracts, mapper);
        this.evidence = new EmploymentEventPublicationEvidence(
                jdbc, mapper, recorder, contracts);
    }

    @Autowired
    EmploymentEventRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            @Value("${dwp.hris.tim003.employment-changed-publication.enabled:false}")
            boolean canonicalPublicationEnabled,
            @Value("${dwp.hris.tim003.employment-changed-publication.enrolled-tenants:}")
            String canonicalPublicationTenants) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.evidence = new EmploymentEventPublicationEvidence(
                jdbc, mapper, recorder, contracts, canonicalPublicationEnabled,
                canonicalPublicationTenants);
    }

    void establishTenant(long tenantId) {
        if (tenantId < 1) throw new IllegalArgumentException("tenantId must be positive");
        String applied = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)",
                params(tenantId), String.class);
        if (!Long.toString(tenantId).equals(applied)) {
            throw new IllegalStateException("Employment tenant scope was not established.");
        }
    }

    PreflightSnapshot preflight(
            long tenantId,
            EmploymentEventDtos.NormalizedCommand command,
            boolean lock) {
        establishTenant(tenantId);
        SubjectRow subject = "HIRE".equals(command.eventType())
                ? person(tenantId, command.personPublicId(), lock)
                : worker(tenantId, command.workerPublicId(), lock);
        if (lock) {
            jdbc.queryForObject(
                    "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) held",
                    params(tenantId).addValue("key", tenantId + ":" + subject.personId()),
                    Integer.class);
        }
        ReferenceRow legalEmployer = reference(tenantId, "LEGAL_EMPLOYER",
                command.legalEmployerRef().publicId(), lock);
        ReferenceRow organization = reference(tenantId, "ORGANIZATION",
                command.assignment().organizationRef().publicId(), lock);
        ReferenceRow position = reference(tenantId, "POSITION",
                command.assignment().positionRef().publicId(), lock);
        ReferenceRow job = reference(tenantId, "JOB",
                command.assignment().jobRef().publicId(), lock);
        ReferenceRow location = reference(tenantId, "LOCATION",
                command.assignment().workLocationRef().publicId(), lock);
        boolean overlap = command.assignment().primary() && primaryOverlap(
                tenantId, subject.personId(), command.assignment().validFrom(),
                command.assignment().validToExclusive());
        return new PreflightSnapshot(
                subject.personId(), subject.workerId(), subject.workerPublicId(),
                subject.workerVersion(), legalEmployer, organization, position,
                job, location, overlap);
    }

    Optional<EventRow> event(long tenantId, UUID eventId) {
        establishTenant(tenantId);
        return jdbc.query(EVENT_SELECT + """
                 WHERE event.tenant_id=:tenant AND event.public_id=:event
                """, params(tenantId).addValue("event", eventId), this::eventRow)
                .stream().findFirst();
    }

    Optional<EventRow> lockEvent(long tenantId, UUID eventId) {
        establishTenant(tenantId);
        List<UUID> ids = jdbc.query("""
                SELECT public_id FROM ppl_assignment_events
                 WHERE tenant_id=:tenant AND public_id=:event
                 FOR UPDATE
                """, params(tenantId).addValue("event", eventId),
                (result, ignored) -> result.getObject("public_id", UUID.class));
        return ids.isEmpty() ? Optional.empty() : event(tenantId, ids.getFirst());
    }

    EventRow createEvent(
            long tenantId,
            EmploymentEventDtos.NormalizedCommand command,
            PreflightSnapshot snapshot,
            String contentDigest,
            UUID correlationId,
            long actorUserId) {
        establishTenant(tenantId);
        UUID publicId = jdbc.queryForObject("""
                INSERT INTO ppl_assignment_events(
                    tenant_id,subject_person_id,target_worker_id,event_type,
                    effective_date,command_context,event_payload,input_snapshot_hash,
                    expected_owner_version,correlation_id,created_by,updated_by)
                VALUES(:tenant,:person,:worker,:eventType,:effectiveDate,
                    CAST(:context AS jsonb),CAST(:payload AS jsonb),:contentDigest,
                    :ownerVersion,:correlation,:actor,:actor)
                RETURNING public_id
                """, params(tenantId)
                .addValue("person", snapshot.personId())
                .addValue("worker", snapshot.workerId())
                .addValue("eventType", command.eventType())
                .addValue("effectiveDate", command.relationshipStart())
                .addValue("context", command.contextJson())
                .addValue("payload", command.payloadJson())
                .addValue("contentDigest", contentDigest)
                .addValue("ownerVersion", command.expectedOwnerVersion())
                .addValue("correlation", correlationId)
                .addValue("actor", actorUserId), UUID.class);
        if (publicId == null) throw new IllegalStateException(
                "Employment event insert did not return a public id.");
        return event(tenantId, publicId).orElseThrow();
    }

    Optional<ReceiptRow> insertReceipt(
            long tenantId,
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
            UUID correlationId,
            Instant now) {
        establishTenant(tenantId);
        return jdbc.query("""
                INSERT INTO ppl_command_receipts(
                    command_receipt_id,tenant_id,command_type,idempotency_key,
                    request_hash,actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,correlation_id,created_at,updated_at)
                VALUES(:receipt,:tenant,:commandType,:key,:digest,:actor,:action,
                    :subject,:population,:fieldRevision,:purpose,:authorizationRevision,
                    :contextDigest,'EMPLOYMENT_EVENT',:target,:expectedVersion,
                    'RUNNING',:correlation,:now,:now)
                ON CONFLICT DO NOTHING
                RETURNING *
                """, params(tenantId)
                .addValue("receipt", receiptId)
                .addValue("commandType", commandType)
                .addValue("key", idempotencyKey)
                .addValue("digest", requestDigest)
                .addValue("actor", actorPublicId)
                .addValue("action", action)
                .addValue("subject", subjectPublicId)
                .addValue("population", populationDigest)
                .addValue("fieldRevision", fieldRevision)
                .addValue("purpose", purpose)
                .addValue("authorizationRevision", authorizationRevision)
                .addValue("contextDigest", contextDigest)
                .addValue("target", targetPublicId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("correlation", correlationId)
                .addValue("now", Timestamp.from(now)), this::receiptRow)
                .stream().findFirst();
    }

    Optional<ReceiptRow> receiptByIdentity(
            long tenantId,
            UUID subjectPublicId,
            String action,
            String idempotencyKey) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND subject_principal_public_id=:subject
                   AND originating_action=:action AND idempotency_key=:key
                 FOR UPDATE
                """, params(tenantId).addValue("subject", subjectPublicId)
                .addValue("action", action).addValue("key", idempotencyKey),
                this::receiptRow).stream().findFirst();
    }

    Optional<ReceiptRow> receipt(long tenantId, UUID receiptId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT * FROM ppl_command_receipts
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                """, params(tenantId).addValue("receipt", receiptId), this::receiptRow)
                .stream().findFirst();
    }

    ReceiptRow completeReceipt(
            long tenantId,
            UUID receiptId,
            EventRow event,
            Instant now) {
        establishTenant(tenantId);
        List<ReceiptRow> rows = jdbc.query("""
                UPDATE ppl_command_receipts
                   SET lifecycle_state='SUCCEEDED',result_code=:resultCode,
                       result_reference=CAST(:result AS jsonb),completed_at=:now,updated_at=:now
                 WHERE tenant_id=:tenant AND command_receipt_id=:receipt
                   AND lifecycle_state='RUNNING'
                RETURNING *
                """, params(tenantId)
                .addValue("resultCode", event.lifecycleState())
                .addValue("result", json(Map.of(
                        "eventPublicId", event.publicId().toString(),
                        "lifecycleState", event.lifecycleState(),
                        "eventVersion", event.version())))
                .addValue("now", Timestamp.from(now))
                .addValue("receipt", receiptId), this::receiptRow);
        if (rows.size() != 1) throw new IllegalStateException(
                "Employment command receipt completion failed.");
        return rows.getFirst();
    }

    EventRow validate(
            long tenantId,
            UUID eventId,
            long expectedVersion,
            String impactDigest,
            String impactJson,
            long actorUserId) {
        return transition(tenantId, eventId, expectedVersion, """
                   SET lifecycle_state='VALIDATED',impact_snapshot_hash=:impact,
                       impact_snapshot=CAST(:snapshot AS jsonb),
                       validated_at=clock_timestamp(),validated_by=:actor,
                       version=version+1,updated_by=:actor
                 WHERE tenant_id=:tenant AND public_id=:event
                   AND lifecycle_state='DRAFT' AND version=:expected
                """, new MapSqlParameterSource().addValue("impact", impactDigest)
                .addValue("snapshot", impactJson).addValue("actor", actorUserId));
    }

    EventRow submit(
            long tenantId,
            UUID eventId,
            long expectedVersion,
            UUID approvalRequestId,
            long actorUserId) {
        return transition(tenantId, eventId, expectedVersion, """
                   SET lifecycle_state='PENDING_APPROVAL',approval_request_id=:approval,
                       submitted_at=clock_timestamp(),submitted_by=:actor,
                       version=version+1,updated_by=:actor
                 WHERE tenant_id=:tenant AND public_id=:event
                   AND lifecycle_state='VALIDATED' AND version=:expected
                """, new MapSqlParameterSource().addValue("approval", approvalRequestId)
                .addValue("actor", actorUserId));
    }

    void appendApprovalRequest(
            long tenantId,
            EventRow event,
            UUID correlationId,
            String payloadJson) {
        evidence.appendApprovalRequest(tenantId, event, correlationId, payloadJson);
    }

    ApprovalDecisionRow recordApproval(
            long tenantId,
            EventRow event,
            EmploymentEventDtos.ApprovalDecision decision) {
        establishTenant(tenantId);
        List<ApprovalDecisionRow> rows = jdbc.query("""
                INSERT INTO ppl_assignment_event_approval_decisions(
                    tenant_id,assignment_event_id,approval_request_id,
                    decision_event_id,decision,decision_actor_public_id,
                    approval_policy_revision,expected_event_version,payload_sha256,
                    correlation_id,decided_at)
                VALUES(:tenant,:eventInternal,:request,:decisionEvent,:decision,:actor,
                    :revision,:expected,:digest,:correlation,:decidedAt)
                ON CONFLICT DO NOTHING
                RETURNING *
                """, params(tenantId)
                .addValue("eventInternal", event.internalId())
                .addValue("request", decision.approvalRequestId())
                .addValue("decisionEvent", decision.decisionEventId())
                .addValue("decision", decision.decision())
                .addValue("actor", decision.decisionActorPublicId())
                .addValue("revision", decision.approvalPolicyRevision())
                .addValue("expected", decision.expectedEventVersion())
                .addValue("digest", decision.payloadDigest())
                .addValue("correlation", decision.correlationId())
                .addValue("decidedAt", Timestamp.from(decision.decidedAt())),
                this::approvalRow);
        if (!rows.isEmpty()) return rows.getFirst();
        return approvalDecision(tenantId, decision.decisionEventId()).orElseThrow();
    }

    Optional<ApprovalDecisionRow> approvalDecision(long tenantId, UUID decisionEventId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT * FROM ppl_assignment_event_approval_decisions
                 WHERE tenant_id=:tenant AND decision_event_id=:decisionEvent
                """, params(tenantId).addValue("decisionEvent", decisionEventId),
                this::approvalRow).stream().findFirst();
    }

    EventRow applyApproval(
            long tenantId,
            EventRow before,
            EmploymentEventDtos.ApprovalDecision decision) {
        String state = "APPROVED".equals(decision.decision()) ? "APPROVED" : "REJECTED";
        return transition(tenantId, before.publicId(), before.version(), """
                   SET lifecycle_state=:state,approval_decision_event_id=:decisionEvent,
                       approval_decision_revision=:revision,
                       approval_payload_hash=:digest,approved_at=:decidedAt,
                       approved_by_principal=:actor,version=version+1
                 WHERE tenant_id=:tenant AND public_id=:event
                   AND lifecycle_state='PENDING_APPROVAL'
                   AND approval_request_id=:request AND version=:expected
                """, new MapSqlParameterSource().addValue("state", state)
                .addValue("request", decision.approvalRequestId())
                .addValue("decisionEvent", decision.decisionEventId())
                .addValue("revision", decision.approvalPolicyRevision())
                .addValue("digest", decision.payloadDigest())
                .addValue("decidedAt", Timestamp.from(decision.decidedAt()))
                .addValue("actor", decision.decisionActorPublicId()));
    }

    PublishedLedger publishLedger(
            long tenantId,
            EventRow event,
            EmploymentEventDtos.NormalizedCommand command,
            PreflightSnapshot snapshot,
            long publisherUserId,
            UUID correlationId) {
        establishTenant(tenantId);
        WorkerRow worker = "HIRE".equals(event.eventType())
                ? insertWorker(tenantId, event.publicId(), command, snapshot, publisherUserId)
                : advanceWorker(tenantId, snapshot, command.expectedOwnerVersion(), publisherUserId);
        RelationshipRow relationship = insertRelationship(
                tenantId, event.publicId(), worker.internalId(), command, snapshot,
                publisherUserId);
        AssignmentRow assignment = insertAssignment(
                tenantId, event.publicId(), relationship.internalId(), command, snapshot,
                publisherUserId);
        EventRow published = publishEvent(
                tenantId, event, worker, relationship, assignment, publisherUserId);
        evidence.appendPublication(tenantId, publisherUserId, published, command, snapshot,
                worker, relationship, assignment, correlationId);
        return new PublishedLedger(published, worker, relationship, assignment);
    }

    void appendCommandAudit(
            long tenantId,
            long actor,
            String action,
            EventRow event,
            UUID correlationId) {
        establishTenant(tenantId);
        evidence.appendCommandAudit(tenantId, actor, action, event, correlationId);
    }

    List<AssignmentProjection> assignments(long tenantId, UUID personPublicId, Instant asOf) {
        establishTenant(tenantId);
        LocalDate date = asOf.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        String personFilter = personPublicId == null ? "" : " AND person.public_id=:person";
        return jdbc.query("""
                SELECT person.public_id AS person_public_id,worker.worker_id,
                       worker.public_id AS worker_public_id,
                       worker.worker_status,worker.version AS worker_version,
                       relationship.public_id AS relationship_public_id,
                       relationship.relationship_type,relationship.start_date,
                       relationship.end_date,relationship.version AS relationship_version,
                       assignment.public_id AS assignment_public_id,
                       assignment.assignment_status,assignment.effective_start_date,
                       assignment.effective_end_date,assignment.primary_assignment,
                       assignment.full_time_equivalent,assignment.version AS assignment_version,
                       employer.public_id AS employer_public_id,
                       employer.version AS employer_version,
                       organization.public_id AS organization_public_id,
                       organization.version AS organization_version,
                       position.public_id AS position_public_id,
                       position.version AS position_version,
                       job.public_id AS job_public_id,job.version AS job_version,
                       location.public_id AS location_public_id,
                       location.version AS location_version,
                       location.calendar_public_id,location.location_key,location.time_zone
                  FROM ppl_assignments assignment
                  JOIN ppl_work_relationships relationship
                    ON relationship.tenant_id=assignment.tenant_id
                   AND relationship.work_relationship_id=assignment.work_relationship_id
                  JOIN ppl_workers worker ON worker.tenant_id=relationship.tenant_id
                   AND worker.worker_id=relationship.worker_id
                  JOIN ppl_persons person ON person.tenant_id=worker.tenant_id
                   AND person.person_id=worker.person_id
                  JOIN ppl_legal_employers employer
                    ON employer.tenant_id=relationship.tenant_id
                   AND employer.legal_employer_id=relationship.legal_employer_id
                  LEFT JOIN ppl_organizations organization
                    ON organization.tenant_id=assignment.tenant_id
                   AND organization.organization_id=assignment.organization_id
                  LEFT JOIN ppl_positions position ON position.tenant_id=assignment.tenant_id
                   AND position.position_id=assignment.position_id
                  LEFT JOIN ppl_job_profiles job ON job.tenant_id=assignment.tenant_id
                   AND job.job_profile_id=assignment.job_profile_id
                  LEFT JOIN ppl_locations location ON location.tenant_id=assignment.tenant_id
                   AND location.location_id=assignment.location_id
                 WHERE assignment.tenant_id=:tenant
                   AND relationship.start_date<=:asOf
                   AND (relationship.end_date IS NULL OR relationship.end_date>=:asOf)
                   AND assignment.effective_start_date<=:asOf
                   AND (assignment.effective_end_date IS NULL
                        OR assignment.effective_end_date>=:asOf)
                """ + personFilter + " ORDER BY assignment.public_id",
                params(tenantId).addValue("person", personPublicId).addValue("asOf", date),
                this::assignmentProjection);
    }

    private EventRow transition(
            long tenantId,
            UUID eventId,
            long expectedVersion,
            String update,
            MapSqlParameterSource extra) {
        establishTenant(tenantId);
        List<UUID> ids = jdbc.query("UPDATE ppl_assignment_events" + update
                        + " RETURNING public_id",
                extra.addValue("tenant", tenantId).addValue("event", eventId)
                        .addValue("expected", expectedVersion),
                (result, ignored) -> result.getObject("public_id", UUID.class));
        return ids.size() == 1 ? event(tenantId, ids.getFirst()).orElseThrow() : null;
    }

    private SubjectRow person(long tenantId, UUID publicId, boolean lock) {
        List<SubjectRow> rows = jdbc.query("""
                SELECT person.person_id,NULL::BIGINT AS worker_id,
                       NULL::UUID AS worker_public_id,0::BIGINT AS worker_version
                  FROM ppl_persons person
                 WHERE person.tenant_id=:tenant AND person.public_id=:publicId
                   AND person.lifecycle_state='ACTIVE'
                """ + (lock ? " FOR UPDATE" : ""),
                params(tenantId).addValue("publicId", publicId), this::subjectRow);
        if (rows.size() != 1) throw EmploymentRepositoryProblem.notDisclosed();
        return rows.getFirst();
    }

    private SubjectRow worker(long tenantId, UUID publicId, boolean lock) {
        List<SubjectRow> rows = jdbc.query("""
                SELECT worker.person_id,worker.worker_id,
                       worker.public_id AS worker_public_id,worker.version AS worker_version
                  FROM ppl_workers worker
                 WHERE worker.tenant_id=:tenant AND worker.public_id=:publicId
                   AND worker.worker_status IN ('ACTIVE','LEAVE','PENDING')
                """ + (lock ? " FOR UPDATE" : ""),
                params(tenantId).addValue("publicId", publicId), this::subjectRow);
        if (rows.size() != 1) throw EmploymentRepositoryProblem.notDisclosed();
        return rows.getFirst();
    }

    private ReferenceRow reference(
            long tenantId,
            String type,
            UUID publicId,
            boolean lock) {
        String sql = switch (type) {
            case "LEGAL_EMPLOYER" -> """
                    SELECT legal_employer_id AS internal_id,public_id,version,
                           employer_key AS business_key,NULL::VARCHAR AS time_zone,
                           NULL::UUID AS calendar_public_id
                      FROM ppl_legal_employers
                     WHERE tenant_id=:tenant AND public_id=:publicId
                       AND lifecycle_state='ACTIVE'
                    """;
            case "ORGANIZATION" -> """
                    SELECT organization_id AS internal_id,public_id,version,
                           organization_key AS business_key,NULL::VARCHAR AS time_zone,
                           NULL::UUID AS calendar_public_id
                      FROM ppl_organizations
                     WHERE tenant_id=:tenant AND public_id=:publicId
                       AND lifecycle_state='ACTIVE'
                    """;
            case "POSITION" -> """
                    SELECT position_id AS internal_id,public_id,version,
                           position_key AS business_key,NULL::VARCHAR AS time_zone,
                           NULL::UUID AS calendar_public_id
                      FROM ppl_positions
                     WHERE tenant_id=:tenant AND public_id=:publicId
                       AND position_status IN ('OPEN','FILLED')
                    """;
            case "JOB" -> """
                    SELECT job_profile_id AS internal_id,public_id,version,
                           job_key AS business_key,NULL::VARCHAR AS time_zone,
                           NULL::UUID AS calendar_public_id
                      FROM ppl_job_profiles
                     WHERE tenant_id=:tenant AND public_id=:publicId
                       AND lifecycle_state='ACTIVE'
                    """;
            case "LOCATION" -> """
                    SELECT location_id AS internal_id,public_id,version,
                           location_key AS business_key,time_zone,calendar_public_id
                      FROM ppl_locations
                     WHERE tenant_id=:tenant AND public_id=:publicId
                       AND lifecycle_state='ACTIVE'
                    """;
            default -> throw new IllegalArgumentException("Unknown HRM reference: " + type);
        };
        List<ReferenceRow> rows = jdbc.query(sql + (lock ? " FOR SHARE" : ""),
                params(tenantId).addValue("publicId", publicId), this::referenceRow);
        if (rows.size() != 1) throw EmploymentRepositoryProblem.notDisclosed();
        return rows.getFirst();
    }

    private boolean primaryOverlap(
            long tenantId,
            long personId,
            LocalDate from,
            LocalDate toExclusive) {
        Boolean value = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM ppl_assignments assignment
                    JOIN ppl_work_relationships relationship
                      ON relationship.tenant_id=assignment.tenant_id
                     AND relationship.work_relationship_id=assignment.work_relationship_id
                    JOIN ppl_workers worker ON worker.tenant_id=relationship.tenant_id
                     AND worker.worker_id=relationship.worker_id
                    WHERE assignment.tenant_id=:tenant AND worker.person_id=:person
                      AND assignment.primary_assignment=TRUE
                      AND assignment.assignment_status IN ('ACTIVE','SUSPENDED','PENDING')
                      AND (CAST(:toExclusive AS DATE) IS NULL
                           OR assignment.effective_start_date < CAST(:toExclusive AS DATE))
                      AND (assignment.effective_end_date IS NULL
                           OR assignment.effective_end_date >= :from))
                """, params(tenantId).addValue("person", personId)
                .addValue("from", from).addValue("toExclusive", toExclusive), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    private WorkerRow insertWorker(
            long tenantId,
            UUID eventId,
            EmploymentEventDtos.NormalizedCommand command,
            PreflightSnapshot snapshot,
            long actor) {
        String type = "CONTINGENT".equals(command.employmentType())
                ? "CONTINGENT" : "EMPLOYEE";
        return jdbc.query("""
                INSERT INTO ppl_workers(
                    tenant_id,person_id,worker_number,worker_type,worker_status,
                    original_hire_date,version,created_by,updated_by)
                VALUES(:tenant,:person,:number,:type,'ACTIVE',:hireDate,1,:actor,:actor)
                RETURNING worker_id,public_id,version
                """, params(tenantId).addValue("person", snapshot.personId())
                .addValue("number", key("HRM-W", eventId)).addValue("type", type)
                .addValue("hireDate", command.relationshipStart())
                .addValue("actor", actor), this::workerRow).getFirst();
    }

    private WorkerRow advanceWorker(
            long tenantId,
            PreflightSnapshot snapshot,
            long expectedVersion,
            long actor) {
        List<WorkerRow> rows = jdbc.query("""
                UPDATE ppl_workers SET version=version+1,
                       updated_at=clock_timestamp(),updated_by=:actor
                 WHERE tenant_id=:tenant AND worker_id=:worker AND version=:expected
                RETURNING worker_id,public_id,version
                """, params(tenantId).addValue("worker", snapshot.workerId())
                .addValue("expected", expectedVersion).addValue("actor", actor),
                this::workerRow);
        if (rows.size() != 1) throw EmploymentRepositoryProblem.stale(snapshot.workerVersion());
        return rows.getFirst();
    }

    private RelationshipRow insertRelationship(
            long tenantId,
            UUID eventId,
            long workerId,
            EmploymentEventDtos.NormalizedCommand command,
            PreflightSnapshot snapshot,
            long actor) {
        LocalDate end = command.assignment().validToExclusive() == null
                ? null : command.assignment().validToExclusive().minusDays(1);
        String type = "CONTINGENT".equals(command.employmentType())
                ? "CONTINGENT" : "EMPLOYEE";
        return jdbc.query("""
                INSERT INTO ppl_work_relationships(
                    tenant_id,relationship_key,worker_id,legal_employer_id,
                    relationship_type,primary_relationship,start_date,end_date,
                    version,created_by,updated_by)
                VALUES(:tenant,:key,:worker,:employer,:type,:primary,:start,:end,
                    1,:actor,:actor)
                RETURNING work_relationship_id,public_id,version
                """, params(tenantId).addValue("key", key("HRM-REL", eventId))
                .addValue("worker", workerId)
                .addValue("employer", snapshot.legalEmployer().internalId())
                .addValue("type", type).addValue("primary", "HIRE".equals(command.eventType()))
                .addValue("start", command.relationshipStart()).addValue("end", end)
                .addValue("actor", actor), this::relationshipRow).getFirst();
    }

    private AssignmentRow insertAssignment(
            long tenantId,
            UUID eventId,
            long relationshipId,
            EmploymentEventDtos.NormalizedCommand command,
            PreflightSnapshot snapshot,
            long actor) {
        LocalDate end = command.assignment().validToExclusive() == null
                ? null : command.assignment().validToExclusive().minusDays(1);
        return jdbc.query("""
                INSERT INTO ppl_assignments(
                    tenant_id,assignment_key,work_relationship_id,effective_start_date,
                    effective_end_date,effective_sequence,assignment_status,
                    primary_assignment,position_id,job_profile_id,organization_id,
                    location_id,business_title,full_time_equivalent,version,
                    created_by,updated_by)
                VALUES(:tenant,:key,:relationship,:starts,:ends,1,'ACTIVE',:primary,
                    :position,:job,:organization,:location,:title,:fte,1,:actor,:actor)
                RETURNING assignment_id,public_id,version
                """, params(tenantId).addValue("key", key("HRM-ASG", eventId))
                .addValue("relationship", relationshipId)
                .addValue("starts", command.assignment().validFrom()).addValue("ends", end)
                .addValue("primary", command.assignment().primary())
                .addValue("position", snapshot.position().internalId())
                .addValue("job", snapshot.job().internalId())
                .addValue("organization", snapshot.organization().internalId())
                .addValue("location", snapshot.location().internalId())
                .addValue("title", snapshot.position().businessKey())
                .addValue("fte", command.assignment().fte()).addValue("actor", actor),
                this::assignmentRow).getFirst();
    }

    private EventRow publishEvent(
            long tenantId,
            EventRow before,
            WorkerRow worker,
            RelationshipRow relationship,
            AssignmentRow assignment,
            long actor) {
        EventRow result = transition(tenantId, before.publicId(), before.version(), """
                   SET lifecycle_state='PUBLISHED',published_at=clock_timestamp(),
                       published_by=:actor,target_worker_id=:worker,
                       published_work_relationship_id=:relationship,
                       published_assignment_id=:assignment,version=version+1,updated_by=:actor
                 WHERE tenant_id=:tenant AND public_id=:event
                   AND lifecycle_state='APPROVED' AND version=:expected
                """, new MapSqlParameterSource().addValue("actor", actor)
                .addValue("worker", worker.internalId())
                .addValue("relationship", relationship.internalId())
                .addValue("assignment", assignment.internalId()));
        if (result == null) throw EmploymentRepositoryProblem.version(before.version());
        return result;
    }

    private MapSqlParameterSource params(long tenantId) {
        return new MapSqlParameterSource("tenant", tenantId);
    }

    private String key(String prefix, UUID eventId) {
        return prefix + '-' + eventId.toString().replace("-", "");
    }

    private SubjectRow subjectRow(ResultSet result, int ignored) throws SQLException {
        return new SubjectRow(result.getLong("person_id"),
                result.getObject("worker_id", Long.class),
                result.getObject("worker_public_id", UUID.class),
                result.getLong("worker_version"));
    }

    private ReferenceRow referenceRow(ResultSet result, int ignored) throws SQLException {
        return new ReferenceRow(result.getLong("internal_id"),
                result.getObject("public_id", UUID.class), result.getLong("version"),
                result.getString("business_key"), result.getString("time_zone"),
                result.getObject("calendar_public_id", UUID.class));
    }

    private EventRow eventRow(ResultSet result, int ignored) throws SQLException {
        return new EventRow(result.getLong("assignment_event_id"),
                result.getObject("public_id", UUID.class), result.getLong("tenant_id"),
                result.getLong("subject_person_id"),
                result.getObject("target_worker_id", Long.class),
                result.getString("event_type"), result.getObject("effective_date", LocalDate.class),
                jsonMap(result.getString("command_context")),
                jsonMap(result.getString("event_payload")),
                result.getString("lifecycle_state"),
                jsonMap(result.getString("impact_snapshot")),
                result.getString("input_snapshot_hash"),
                result.getString("impact_snapshot_hash"),
                result.getLong("expected_owner_version"), result.getLong("version"),
                result.getLong("created_by"),
                result.getObject("approval_request_id", UUID.class),
                result.getObject("approval_decision_event_id", UUID.class),
                result.getObject("approval_decision_revision", Long.class),
                result.getString("approval_payload_hash"),
                result.getObject("published_worker_public_id", UUID.class),
                result.getObject("published_relationship_public_id", UUID.class),
                result.getObject("published_assignment_public_id", UUID.class),
                instant(result.getTimestamp("validated_at")),
                instant(result.getTimestamp("submitted_at")),
                instant(result.getTimestamp("approved_at")),
                instant(result.getTimestamp("published_at")),
                instant(result.getTimestamp("created_at")),
                instant(result.getTimestamp("updated_at")),
                result.getObject("originating_correlation_id", UUID.class));
    }

    private ReceiptRow receiptRow(ResultSet result, int ignored) throws SQLException {
        return new ReceiptRow(result.getObject("command_receipt_id", UUID.class),
                result.getLong("tenant_id"), result.getString("command_type"),
                result.getString("idempotency_key"), result.getString("request_hash"),
                result.getObject("actor_public_id", UUID.class),
                result.getString("originating_action"),
                result.getObject("subject_principal_public_id", UUID.class),
                result.getString("population_scope_digest"),
                result.getLong("field_policy_revision"), result.getString("purpose_code"),
                result.getLong("authorization_revision"),
                result.getString("originating_context_digest"),
                result.getObject("target_public_id", UUID.class),
                result.getObject("expected_version", Long.class),
                result.getString("lifecycle_state"), result.getString("result_code"),
                result.getString("result_reference"),
                result.getObject("correlation_id", UUID.class),
                instant(result.getTimestamp("created_at")));
    }

    private ApprovalDecisionRow approvalRow(ResultSet result, int ignored) throws SQLException {
        return new ApprovalDecisionRow(result.getLong("approval_decision_id"),
                result.getObject("approval_request_id", UUID.class),
                result.getObject("decision_event_id", UUID.class), result.getString("decision"),
                result.getObject("decision_actor_public_id", UUID.class),
                result.getLong("approval_policy_revision"),
                result.getLong("expected_event_version"), result.getString("payload_sha256"),
                result.getObject("correlation_id", UUID.class),
                instant(result.getTimestamp("decided_at")));
    }

    private WorkerRow workerRow(ResultSet result, int ignored) throws SQLException {
        return new WorkerRow(result.getLong("worker_id"),
                result.getObject("public_id", UUID.class), result.getLong("version"));
    }

    private RelationshipRow relationshipRow(ResultSet result, int ignored) throws SQLException {
        return new RelationshipRow(result.getLong("work_relationship_id"),
                result.getObject("public_id", UUID.class), result.getLong("version"));
    }

    private AssignmentRow assignmentRow(ResultSet result, int ignored) throws SQLException {
        return new AssignmentRow(result.getLong("assignment_id"),
                result.getObject("public_id", UUID.class), result.getLong("version"));
    }

    private AssignmentProjection assignmentProjection(ResultSet result, int ignored)
            throws SQLException {
        return new AssignmentProjection(
                result.getObject("person_public_id", UUID.class),
                result.getLong("worker_id"),
                result.getObject("worker_public_id", UUID.class),
                result.getString("worker_status"), result.getLong("worker_version"),
                result.getObject("relationship_public_id", UUID.class),
                result.getString("relationship_type"),
                result.getObject("start_date", LocalDate.class),
                result.getObject("end_date", LocalDate.class),
                result.getLong("relationship_version"),
                result.getObject("assignment_public_id", UUID.class),
                result.getString("assignment_status"),
                result.getObject("effective_start_date", LocalDate.class),
                result.getObject("effective_end_date", LocalDate.class),
                result.getBoolean("primary_assignment"),
                result.getBigDecimal("full_time_equivalent"),
                result.getLong("assignment_version"),
                result.getObject("employer_public_id", UUID.class),
                result.getLong("employer_version"),
                result.getObject("organization_public_id", UUID.class),
                result.getObject("organization_version", Long.class),
                result.getObject("position_public_id", UUID.class),
                result.getObject("position_version", Long.class),
                result.getObject("job_public_id", UUID.class),
                result.getObject("job_version", Long.class),
                result.getObject("location_public_id", UUID.class),
                result.getObject("location_version", Long.class),
                result.getObject("calendar_public_id", UUID.class),
                result.getString("location_key"), result.getString("time_zone"));
    }

    private Map<String, Object> jsonMap(String value) {
        if (value == null) return Map.of();
        try {
            return Map.copyOf(mapper.readValue(
                    value, new TypeReference<LinkedHashMap<String, Object>>() { }));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored employment JSON is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Employment event JSON encoding failed.", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    record SubjectRow(long personId, Long workerId, UUID workerPublicId, long workerVersion) { }

    record ReferenceRow(
            long internalId,
            UUID publicId,
            long version,
            String businessKey,
            String timeZone,
            UUID calendarPublicId) { }

    record PreflightSnapshot(
            long personId,
            Long workerId,
            UUID workerPublicId,
            long workerVersion,
            ReferenceRow legalEmployer,
            ReferenceRow organization,
            ReferenceRow position,
            ReferenceRow job,
            ReferenceRow location,
            boolean primaryOverlap) { }

    record EventRow(
            long internalId,
            UUID publicId,
            long tenantId,
            long subjectPersonId,
            Long targetWorkerId,
            String eventType,
            LocalDate effectiveDate,
            Map<String, Object> context,
            Map<String, Object> payload,
            String lifecycleState,
            Map<String, Object> impact,
            String contentDigest,
            String impactDigest,
            long targetOwnerVersion,
            long version,
            long makerUserId,
            UUID approvalRequestId,
            UUID approvalDecisionEventId,
            Long approvalDecisionRevision,
            String approvalPayloadDigest,
            UUID publishedWorkerPublicId,
            UUID publishedRelationshipPublicId,
            UUID publishedAssignmentPublicId,
            Instant validatedAt,
            Instant submittedAt,
            Instant approvedAt,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            UUID originatingCorrelationId) {

        EventRow(
                long internalId,
                UUID publicId,
                long tenantId,
                long subjectPersonId,
                Long targetWorkerId,
                String eventType,
                LocalDate effectiveDate,
                Map<String, Object> context,
                Map<String, Object> payload,
                String lifecycleState,
                Map<String, Object> impact,
                String contentDigest,
                String impactDigest,
                long targetOwnerVersion,
                long version,
                long makerUserId,
                UUID approvalRequestId,
                UUID approvalDecisionEventId,
                Long approvalDecisionRevision,
                String approvalPayloadDigest,
                UUID publishedWorkerPublicId,
                UUID publishedRelationshipPublicId,
                UUID publishedAssignmentPublicId,
                Instant validatedAt,
                Instant submittedAt,
                Instant approvedAt,
                Instant publishedAt,
                Instant createdAt,
                Instant updatedAt) {
            this(internalId, publicId, tenantId, subjectPersonId, targetWorkerId,
                    eventType, effectiveDate, context, payload, lifecycleState, impact,
                    contentDigest, impactDigest, targetOwnerVersion, version, makerUserId,
                    approvalRequestId, approvalDecisionEventId, approvalDecisionRevision,
                    approvalPayloadDigest, publishedWorkerPublicId,
                    publishedRelationshipPublicId, publishedAssignmentPublicId,
                    validatedAt, submittedAt, approvedAt, publishedAt, createdAt, updatedAt,
                    null);
        }
    }

    record ReceiptRow(
            UUID receiptId,
            long tenantId,
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
            String resultCode,
            String resultReference,
            UUID correlationId,
            Instant createdAt) { }

    record ApprovalDecisionRow(
            long internalId,
            UUID approvalRequestId,
            UUID decisionEventId,
            String decision,
            UUID decisionActorPublicId,
            long approvalPolicyRevision,
            long expectedEventVersion,
            String payloadDigest,
            UUID correlationId,
            Instant decidedAt) { }

    record WorkerRow(long internalId, UUID publicId, long version) { }
    record RelationshipRow(long internalId, UUID publicId, long version) { }
    record AssignmentRow(long internalId, UUID publicId, long version) { }
    record PublishedLedger(EventRow event, WorkerRow worker,
                           RelationshipRow relationship, AssignmentRow assignment) { }

    record AssignmentProjection(
            UUID personPublicId,
            long workerId,
            UUID workerPublicId,
            String workerStatus,
            long workerVersion,
            UUID relationshipPublicId,
            String relationshipType,
            LocalDate relationshipStart,
            LocalDate relationshipEnd,
            long relationshipVersion,
            UUID assignmentPublicId,
            String assignmentStatus,
            LocalDate assignmentStart,
            LocalDate assignmentEnd,
            boolean primaryAssignment,
            java.math.BigDecimal fte,
            long assignmentVersion,
            UUID legalEmployerPublicId,
            long legalEmployerVersion,
            UUID organizationPublicId,
            Long organizationVersion,
            UUID positionPublicId,
            Long positionVersion,
            UUID jobPublicId,
            Long jobVersion,
            UUID locationPublicId,
            Long locationVersion,
            UUID calendarPublicId,
            String locationCode,
            String timeZone) { }

    private static final class EmploymentRepositoryProblem {
        private EmploymentRepositoryProblem() { }

        static EmploymentEventProblem notDisclosed() {
            return new EmploymentEventProblem(
                    org.springframework.http.HttpStatus.NOT_FOUND, "TARGET_NOT_DISCLOSED",
                    "The requested employment target is not available.", null, null, false);
        }

        static EmploymentEventProblem stale(long current) {
            return new EmploymentEventProblem(
                    org.springframework.http.HttpStatus.CONFLICT, "STALE_OWNER_VERSION",
                    "The workforce owner version changed.", current, null, false);
        }

        static EmploymentEventProblem version(long current) {
            return new EmploymentEventProblem(
                    org.springframework.http.HttpStatus.CONFLICT, "OBJECT_VERSION_CONFLICT",
                    "The employment event version changed.", current, null, false);
        }
    }
}
