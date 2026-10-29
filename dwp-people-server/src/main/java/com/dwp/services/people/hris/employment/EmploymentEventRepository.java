package com.dwp.services.people.hris.employment;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Tenant-bound persistence boundary for the canonical employment-event ledger. */
@Repository
class EmploymentEventRepository {

    private static final String EVENT_SELECT = EmploymentEventSql.EVENT_SELECT_1;

    private final NamedParameterJdbcTemplate jdbc;
    private final EmploymentEventPersistenceRows rowMapper;
    private final EmploymentEventPublicationEvidence evidence;

    EmploymentEventRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.rowMapper = new EmploymentEventPersistenceRows(mapper);
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
        this.rowMapper = new EmploymentEventPersistenceRows(mapper);
        this.evidence = new EmploymentEventPublicationEvidence(
                jdbc, mapper, recorder, contracts, canonicalPublicationEnabled,
                canonicalPublicationTenants);
    }

    void establishTenant(long tenantId) {
        if (tenantId < 1) throw new IllegalArgumentException("tenantId must be positive");
        String applied = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)",
                rowMapper.params(tenantId), String.class);
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
                    rowMapper.params(tenantId).addValue("key", tenantId + ":" + subject.personId()),
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
        return jdbc.query(EVENT_SELECT + EmploymentEventSql.EVENT_2, rowMapper.params(tenantId).addValue("event", eventId), this::eventRow)
                .stream().findFirst();
    }

    Optional<EventRow> lockEvent(long tenantId, UUID eventId) {
        establishTenant(tenantId);
        List<UUID> ids = jdbc.query(EmploymentEventSql.LOCK_EVENT_3, rowMapper.params(tenantId).addValue("event", eventId),
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
        UUID publicId = jdbc.queryForObject(EmploymentEventSql.CREATE_EVENT_4, rowMapper.params(tenantId)
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
        return jdbc.query(EmploymentEventSql.INSERT_RECEIPT_5, rowMapper.params(tenantId)
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
        return jdbc.query(EmploymentEventSql.RECEIPT_BY_IDENTITY_6, rowMapper.params(tenantId).addValue("subject", subjectPublicId)
                .addValue("action", action).addValue("key", idempotencyKey),
                this::receiptRow).stream().findFirst();
    }

    Optional<ReceiptRow> receipt(long tenantId, UUID receiptId) {
        establishTenant(tenantId);
        return jdbc.query(EmploymentEventSql.RECEIPT_7, rowMapper.params(tenantId).addValue("receipt", receiptId), this::receiptRow)
                .stream().findFirst();
    }

    ReceiptRow completeReceipt(
            long tenantId,
            UUID receiptId,
            EventRow event,
            Instant now) {
        establishTenant(tenantId);
        List<ReceiptRow> rows = jdbc.query(EmploymentEventSql.COMPLETE_RECEIPT_8, rowMapper.params(tenantId)
                .addValue("resultCode", event.lifecycleState())
                .addValue("result", rowMapper.json(Map.of(
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
        return transition(tenantId, eventId, expectedVersion, EmploymentEventSql.VALIDATE_9,
                new MapSqlParameterSource().addValue("impact", impactDigest)
                .addValue("snapshot", impactJson).addValue("actor", actorUserId));
    }

    EventRow submit(
            long tenantId,
            UUID eventId,
            long expectedVersion,
            UUID approvalRequestId,
            long actorUserId) {
        return transition(tenantId, eventId, expectedVersion, EmploymentEventSql.SUBMIT_10,
                new MapSqlParameterSource().addValue("approval", approvalRequestId)
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
        List<ApprovalDecisionRow> rows = jdbc.query(EmploymentEventSql.RECORD_APPROVAL_11, rowMapper.params(tenantId)
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
        return jdbc.query(EmploymentEventSql.APPROVAL_DECISION_12, rowMapper.params(tenantId).addValue("decisionEvent", decisionEventId),
                this::approvalRow).stream().findFirst();
    }

    EventRow applyApproval(
            long tenantId,
            EventRow before,
            EmploymentEventDtos.ApprovalDecision decision) {
        String state = "APPROVED".equals(decision.decision()) ? "APPROVED" : "REJECTED";
        return transition(tenantId, before.publicId(), before.version(), EmploymentEventSql.APPLY_APPROVAL_13,
                new MapSqlParameterSource().addValue("state", state)
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
        return jdbc.query(EmploymentEventSql.ASSIGNMENTS_14 + personFilter + " ORDER BY assignment.public_id",
                rowMapper.params(tenantId).addValue("person", personPublicId).addValue("asOf", date),
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
        List<SubjectRow> rows = jdbc.query(EmploymentEventSql.PERSON_15 + (lock ? " FOR UPDATE" : ""),
                rowMapper.params(tenantId).addValue("publicId", publicId), this::subjectRow);
        if (rows.size() != 1) throw EmploymentEventPersistenceRows.notDisclosed();
        return rows.getFirst();
    }

    private SubjectRow worker(long tenantId, UUID publicId, boolean lock) {
        List<SubjectRow> rows = jdbc.query(EmploymentEventSql.WORKER_16 + (lock ? " FOR UPDATE" : ""),
                rowMapper.params(tenantId).addValue("publicId", publicId), this::subjectRow);
        if (rows.size() != 1) throw EmploymentEventPersistenceRows.notDisclosed();
        return rows.getFirst();
    }

    private ReferenceRow reference(
            long tenantId,
            String type,
            UUID publicId,
            boolean lock) {
        String sql = switch (type) {
            case "LEGAL_EMPLOYER" -> EmploymentEventSql.REFERENCE_17;
            case "ORGANIZATION" -> EmploymentEventSql.REFERENCE_18;
            case "POSITION" -> EmploymentEventSql.REFERENCE_19;
            case "JOB" -> EmploymentEventSql.REFERENCE_20;
            case "LOCATION" -> EmploymentEventSql.REFERENCE_21;
            default -> throw new IllegalArgumentException("Unknown HRM reference: " + type);
        };
        List<ReferenceRow> rows = jdbc.query(sql + (lock ? " FOR SHARE" : ""),
                rowMapper.params(tenantId).addValue("publicId", publicId), this::referenceRow);
        if (rows.size() != 1) throw EmploymentEventPersistenceRows.notDisclosed();
        return rows.getFirst();
    }

    private boolean primaryOverlap(
            long tenantId,
            long personId,
            LocalDate from,
            LocalDate toExclusive) {
        Boolean value = jdbc.queryForObject(EmploymentEventSql.PRIMARY_OVERLAP_22, rowMapper.params(tenantId).addValue("person", personId)
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
        return jdbc.query(EmploymentEventSql.INSERT_WORKER_23, rowMapper.params(tenantId).addValue("person", snapshot.personId())
                .addValue("number", key("HRM-W", eventId)).addValue("type", type)
                .addValue("hireDate", command.relationshipStart())
                .addValue("actor", actor), this::workerRow).getFirst();
    }

    private WorkerRow advanceWorker(
            long tenantId,
            PreflightSnapshot snapshot,
            long expectedVersion,
            long actor) {
        List<WorkerRow> rows = jdbc.query(EmploymentEventSql.ADVANCE_WORKER_24,
                rowMapper.params(tenantId).addValue("worker", snapshot.workerId())
                .addValue("expected", expectedVersion).addValue("actor", actor),
                this::workerRow);
        if (rows.size() != 1) throw EmploymentEventPersistenceRows.stale(snapshot.workerVersion());
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
        return jdbc.query(EmploymentEventSql.INSERT_RELATIONSHIP_25, rowMapper.params(tenantId).addValue("key", key("HRM-REL", eventId))
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
        return jdbc.query(EmploymentEventSql.INSERT_ASSIGNMENT_26, rowMapper.params(tenantId).addValue("key", key("HRM-ASG", eventId))
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
        EventRow result = transition(tenantId, before.publicId(), before.version(), EmploymentEventSql.PUBLISH_EVENT_27,
                new MapSqlParameterSource().addValue("actor", actor)
                .addValue("worker", worker.internalId())
                .addValue("relationship", relationship.internalId())
                .addValue("assignment", assignment.internalId()));
        if (result == null) throw EmploymentEventPersistenceRows.version(before.version());
        return result;
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
        return rowMapper.eventRow(result, EventRow::new);
    }
    private ReceiptRow receiptRow(ResultSet result, int ignored) throws SQLException {
        return rowMapper.receiptRow(result, ReceiptRow::new);
    }

    private ApprovalDecisionRow approvalRow(ResultSet result, int ignored) throws SQLException {
        return new ApprovalDecisionRow(result.getLong("approval_decision_id"),
                result.getObject("approval_request_id", UUID.class),
                result.getObject("decision_event_id", UUID.class), result.getString("decision"),
                result.getObject("decision_actor_public_id", UUID.class),
                result.getLong("approval_policy_revision"),
                result.getLong("expected_event_version"), result.getString("payload_sha256"),
                result.getObject("correlation_id", UUID.class),
                rowMapper.instant(result.getTimestamp("decided_at")));
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
        return rowMapper.assignmentProjection(result, AssignmentProjection::new);
    }

    record SubjectRow(long personId, Long workerId, UUID workerPublicId, long workerVersion) { }

    record ReferenceRow(
            long internalId,
            UUID publicId,
            long version,
            String businessKey,
            String timeZone,
            UUID calendarPublicId)
            implements EmploymentEventPublicationContract.ReferenceView { }

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
            boolean primaryOverlap)
            implements EmploymentEventPublicationContract.PreflightView { }

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
            UUID originatingCorrelationId)
            implements EmploymentEventPublicationContract.EventView {

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

    record WorkerRow(long internalId, UUID publicId, long version)
            implements EmploymentEventPublicationContract.WorkerView { }
    record RelationshipRow(long internalId, UUID publicId, long version)
            implements EmploymentEventPublicationContract.RelationshipView { }
    record AssignmentRow(long internalId, UUID publicId, long version)
            implements EmploymentEventPublicationContract.AssignmentView { }
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

}
