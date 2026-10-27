package com.dwp.services.people.hris.employment;

import static com.dwp.services.people.hris.employment.EmploymentEventCommandRules.*;
import com.dwp.contracts.hris.xcon.v1.Xcon016WorkplaceAssignmentSnapshotV1;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owner service for the canonical BASE-TFR-HRM-008 employment-event lifecycle. */
@Service
class EmploymentEventService {

    private static final String CREATE_COMMAND = "HRM_ASSIGNMENT_EVENT_CREATE";
    private static final String VALIDATE_COMMAND = "HRM_ASSIGNMENT_EVENT_VALIDATE";
    private static final String SUBMIT_COMMAND = "HRM_ASSIGNMENT_EVENT_SUBMIT";
    private static final String PUBLISH_COMMAND = "HRM_ASSIGNMENT_EVENT_PUBLISH";
    private static final String CREATE_ACTION = "CREATE_ASSIGNMENT_EVENT";
    private static final String VALIDATE_ACTION = "VALIDATE_ASSIGNMENT_EVENT";
    private static final String SUBMIT_ACTION = "SUBMIT_ASSIGNMENT_EVENT";
    private static final String PUBLISH_ACTION = "PUBLISH_ASSIGNMENT_EVENT";
    private static final String CREATE_PATH = "/api/people/v1/hris/assignment-events";
    private static final String EVENT_PATH = "/api/people/v1/hris/assignment-events/";
    private static final Map<String, String> SEALED_RECEIPT_COMMANDS = Map.of(
            CREATE_COMMAND, CREATE_ACTION,
            VALIDATE_COMMAND, VALIDATE_ACTION,
            SUBMIT_COMMAND, SUBMIT_ACTION,
            PUBLISH_COMMAND, PUBLISH_ACTION);

    private final EmploymentEventRepository repository;
    private final EmploymentEventCanonicalizer canonicalizer;
    private final EmploymentEventAuthority authority;
    private final HcmPopulationScopeService populationScopes;
    private final HcmPopulationRepository populations;
    private final HcmHighRiskCommandGuard highRisk;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    EmploymentEventService(
            EmploymentEventRepository repository,
            EmploymentEventCanonicalizer canonicalizer,
            EmploymentEventAuthority authority,
            HcmPopulationScopeService populationScopes,
            HcmPopulationRepository populations,
            HcmHighRiskCommandGuard highRisk,
            ObjectMapper mapper) {
        this(repository, canonicalizer, authority, populationScopes, populations,
                highRisk, mapper, Clock.systemUTC());
    }

    EmploymentEventService(
            EmploymentEventRepository repository,
            EmploymentEventCanonicalizer canonicalizer,
            EmploymentEventAuthority authority,
            HcmPopulationScopeService populationScopes,
            HcmPopulationRepository populations,
            HcmHighRiskCommandGuard highRisk,
            ObjectMapper mapper,
            Clock clock) {
        this.repository = repository;
        this.canonicalizer = canonicalizer;
        this.authority = authority;
        this.populationScopes = populationScopes;
        this.populations = populations;
        this.highRisk = highRisk;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    EmploymentEventDtos.CommandResult create(
            EmploymentEventDtos.CreateCommand request,
            String headerKey,
            String ifMatch,
            UUID correlationId,
            String purposeHeader) {
        String capability = authority.require("CREATE");
        PeopleRequestContext.Actor actor = actor();
        EmploymentEventDtos.NormalizedCommand command = canonicalizer.normalize(request);
        String key = key(headerKey);
        requireEqual(key, command.context().idempotencyKey(), "Idempotency-Key");
        requireEqual(EmploymentEventDtos.PURPOSE, purposeHeader, "X-Purpose-Code");
        long expected = expectedVersion(ifMatch);
        if (expected != command.context().expectedVersion()) {
            throw invalid("If-Match and command expectedVersion must match.");
        }
        UUID correlation = correlation(correlationId);
        PopulationBinding population = population(capability, true);
        String requestDigest = canonicalizer.requestDigest(
                "POST", CREATE_PATH, ifMatch, key, correlation,
                EmploymentEventDtos.PURPOSE, request);
        Claim claim = claim(actor, population, CREATE_COMMAND, CREATE_ACTION, key,
                requestDigest, null, expected, correlation);
        if (!claim.fresh()) return replay(actor.tenantId(), claim.receipt());

        EmploymentEventRepository.PreflightSnapshot snapshot =
                repository.preflight(actor.tenantId(), command, true);
        requirePopulation(actor, population.population(), snapshot, true);
        requireReferences(command, snapshot);
        requireNoOverlap(snapshot);
        EmploymentEventRepository.EventRow event = repository.createEvent(
                actor.tenantId(), command, snapshot,
                canonicalizer.digest(Map.of(
                        "context", command.context(), "eventType", command.eventType(),
                        "payload", command.payload())),
                correlation, actor.userId());
        repository.appendCommandAudit(actor.tenantId(), actor.userId(),
                "people.assignment-event.created", event, correlation);
        EmploymentEventRepository.ReceiptRow receipt = repository.completeReceipt(
                actor.tenantId(), claim.receipt().receiptId(), event, clock.instant());
        return result(receipt, event, false);
    }

    @Transactional
    EmploymentEventDtos.CommandResult validate(
            UUID eventId,
            String headerKey,
            String ifMatch,
            UUID correlationId,
            String purposeHeader) {
        String capability = authority.require("VALIDATE");
        return transition(eventId, headerKey, ifMatch, correlationId, purposeHeader,
                capability, VALIDATE_COMMAND, VALIDATE_ACTION, "validate");
    }

    @Transactional
    EmploymentEventDtos.CommandResult submit(
            UUID eventId,
            String headerKey,
            String ifMatch,
            UUID correlationId,
            String purposeHeader) {
        String capability = authority.require("SUBMIT");
        return transition(eventId, headerKey, ifMatch, correlationId, purposeHeader,
                capability, SUBMIT_COMMAND, SUBMIT_ACTION, "submit");
    }

    private EmploymentEventDtos.CommandResult transition(
            UUID eventId,
            String headerKey,
            String ifMatch,
            UUID correlationId,
            String purposeHeader,
            String capability,
            String commandType,
            String action,
            String operation) {
        PeopleRequestContext.Actor actor = actor();
        String key = key(headerKey);
        requireEqual(EmploymentEventDtos.PURPOSE, purposeHeader, "X-Purpose-Code");
        long expected = expectedVersion(ifMatch);
        UUID correlation = correlation(correlationId);
        PopulationBinding population = population(capability, true);
        String path = EVENT_PATH + eventId + '/' + operation;
        String requestDigest = canonicalizer.requestDigest(
                "POST", path, ifMatch, key, correlation,
                EmploymentEventDtos.PURPOSE, Map.of());
        Claim claim = claim(actor, population, commandType, action, key, requestDigest,
                eventId, expected, correlation);
        if (!claim.fresh()) return replay(actor.tenantId(), claim.receipt());

        EmploymentEventRepository.EventRow before = repository.lockEvent(
                actor.tenantId(), eventId).orElseThrow(EmploymentEventCommandRules::notDisclosed);
        requireVersion(before, expected);
        EmploymentEventDtos.NormalizedCommand command = canonicalizer.normalizeStored(before);
        EmploymentEventRepository.PreflightSnapshot snapshot = repository.preflight(
                actor.tenantId(), command, true);
        requirePopulation(actor, population.population(), snapshot, true);
        requireReferences(command, snapshot);
        requireNoOverlap(snapshot);
        Map<String, Object> impact = impact(command, snapshot);
        String impactDigest = canonicalizer.digest(impact);
        EmploymentEventRepository.EventRow after;
        if ("validate".equals(operation)) {
            requireState(before, "DRAFT");
            after = repository.validate(actor.tenantId(), eventId, expected,
                    impactDigest, canonicalizer.json(impact), actor.userId());
        } else {
            requireState(before, "VALIDATED");
            if (!impactDigest.equals(before.impactDigest())) {
                throw stale(before.targetOwnerVersion());
            }
            UUID approvalRequestId = UUID.randomUUID();
            after = repository.submit(actor.tenantId(), eventId, expected,
                    approvalRequestId, actor.userId());
            if (after != null) {
                repository.appendApprovalRequest(actor.tenantId(), after, correlation,
                        canonicalizer.json(approvalBinding(after)));
            }
        }
        if (after == null) throw version(before.version());
        repository.appendCommandAudit(actor.tenantId(), actor.userId(),
                "people.assignment-event." + operation + 'd', after, correlation);
        EmploymentEventRepository.ReceiptRow receipt = repository.completeReceipt(
                actor.tenantId(), claim.receipt().receiptId(), after, clock.instant());
        return result(receipt, after, false);
    }

    @Transactional
    EmploymentEventDtos.ApprovalResult approval(
            UUID eventId,
            EmploymentEventDtos.ApprovalDecision decision) {
        PeopleRequestContext.Actor actor = actor();
        EmploymentEventRepository.EventRow before = repository.lockEvent(
                actor.tenantId(), eventId).orElseThrow(EmploymentEventCommandRules::notDisclosed);
        EmploymentEventRepository.ApprovalDecisionRow existing = repository
                .approvalDecision(actor.tenantId(), decision.decisionEventId()).orElse(null);
        if (existing != null) {
            requireSameDecision(existing, decision);
            return new EmploymentEventDtos.ApprovalResult(
                    before.publicId(), before.lifecycleState(), before.version(), true);
        }
        requireState(before, "PENDING_APPROVAL");
        if (!Objects.equals(before.approvalRequestId(), decision.approvalRequestId())
                || before.version() != decision.expectedEventVersion()
                || !decision.payloadDigest().equals(
                canonicalizer.digest(approvalBinding(before)))) {
            throw new EmploymentEventProblem(
                    org.springframework.http.HttpStatus.CONFLICT, "STALE_CALLBACK",
                    "The approval callback no longer matches the assignment event.",
                    before.version(), null, false);
        }
        repository.recordApproval(actor.tenantId(), before, decision);
        EmploymentEventRepository.EventRow after = repository.applyApproval(
                actor.tenantId(), before, decision);
        if (after == null) throw version(before.version());
        repository.appendCommandAudit(actor.tenantId(), actor.userId(),
                "people.assignment-event.approval-recorded", after,
                decision.correlationId());
        return new EmploymentEventDtos.ApprovalResult(
                after.publicId(), after.lifecycleState(), after.version(), false);
    }

    @Transactional
    EmploymentEventDtos.CommandResult publish(
            UUID eventId,
            String headerKey,
            String ifMatch,
            UUID correlationId,
            String purposeHeader,
            HcmStepUpHeaders stepUp) {
        String capability = authority.require("PUBLISH");
        PeopleRequestContext.Actor actor = actor();
        String key = key(headerKey);
        requireEqual(EmploymentEventDtos.PURPOSE, purposeHeader, "X-Purpose-Code");
        requireEqual(key, stepUp == null ? null : stepUp.idempotencyKey(),
                "step-up Idempotency-Key");
        long expected = expectedVersion(ifMatch);
        UUID correlation = correlation(correlationId);
        PopulationBinding population = population(capability, true);
        String path = EVENT_PATH + eventId + "/publish";
        String requestDigest = canonicalizer.requestDigest(
                "POST", path, ifMatch, key, correlation,
                EmploymentEventDtos.PURPOSE, Map.of());
        Claim claim = claim(actor, population, PUBLISH_COMMAND, PUBLISH_ACTION, key,
                requestDigest, eventId, expected, correlation);
        if (!claim.fresh()) return replay(actor.tenantId(), claim.receipt());

        EmploymentEventRepository.EventRow before = repository.lockEvent(
                actor.tenantId(), eventId).orElseThrow(EmploymentEventCommandRules::notDisclosed);
        requireVersion(before, expected);
        requireState(before, "APPROVED");
        if (before.makerUserId() == actor.userId()) {
            throw new EmploymentEventProblem(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "MAKER_CHECKER_VIOLATION",
                    "The assignment-event maker cannot publish the same event.",
                    before.version(), claim.receipt().receiptId(), false);
        }
        if (!Objects.equals(before.approvalPayloadDigest(),
                canonicalizer.digest(approvalBinding(before)))) {
            throw stale(before.targetOwnerVersion());
        }
        EmploymentEventDtos.NormalizedCommand command = canonicalizer.normalizeStored(before);
        EmploymentEventRepository.PreflightSnapshot snapshot = repository.preflight(
                actor.tenantId(), command, true);
        requirePopulation(actor, population.population(), snapshot, true);
        requireReferences(command, snapshot);
        requireNoOverlap(snapshot);
        String currentImpact = canonicalizer.digest(impact(command, snapshot));
        if (!currentImpact.equals(before.impactDigest())) throw stale(snapshot.workerVersion());
        highRisk.requireExact(capability, "EMPLOYMENT_EVENT", eventId.toString(),
                before.version(), path, Map.of(), stepUp);

        EmploymentEventRepository.PublishedLedger ledger = repository.publishLedger(
                actor.tenantId(), before, command, snapshot, actor.userId(), correlation);
        EmploymentEventRepository.ReceiptRow receipt = repository.completeReceipt(
                actor.tenantId(), claim.receipt().receiptId(), ledger.event(), clock.instant());
        return result(receipt, ledger.event(), false);
    }

    @Transactional(readOnly = true)
    EmploymentEventDtos.EventView event(UUID eventId) {
        String capability = authority.require("EVENT_DETAIL");
        PeopleRequestContext.Actor actor = actor();
        PopulationBinding population = population(capability, false);
        EmploymentEventRepository.EventRow event = repository.event(
                actor.tenantId(), eventId).orElseThrow(EmploymentEventCommandRules::notDisclosed);
        EmploymentEventDtos.NormalizedCommand command = canonicalizer.normalizeStored(event);
        EmploymentEventRepository.PreflightSnapshot snapshot = repository.preflight(
                actor.tenantId(), command, false);
        requirePopulation(actor, population.population(), snapshot, false);
        return view(event);
    }

    @Transactional(readOnly = true)
    CommandReceiptV1 receipt(UUID receiptId) {
        authority.require("RECEIPT");
        PeopleRequestContext.Actor actor = actor();
        EmploymentEventRepository.ReceiptRow row = repository.receipt(
                actor.tenantId(), receiptId).orElseThrow(EmploymentEventCommandRules::notDisclosed);
        if (!actor.personPublicId().equals(row.subjectPublicId())
                || !Objects.equals(
                        SEALED_RECEIPT_COMMANDS.get(row.commandType()), row.action())
                || !EmploymentEventDtos.PURPOSE.equals(row.purpose())
                || row.requestDigest() == null || !row.requestDigest().matches("[0-9a-f]{64}")
                || row.populationDigest() == null
                || !row.populationDigest().matches("[0-9a-f]{64}")
                || row.contextDigest() == null || !row.contextDigest().matches("[0-9a-f]{64}")
                || row.fieldRevision() < 1 || row.authorizationRevision() < 1
                || row.correlationId() == null) {
            throw notDisclosed();
        }
        return toReceipt(row);
    }

    @Transactional(readOnly = true)
    EmploymentEventDtos.EmploymentDocuments selfDocuments(Instant requestedAsOf) {
        authority.require("SELF_WORKBENCH");
        PeopleRequestContext.Actor actor = actor();
        populationScopes.requireSelfScope();
        Instant asOf = requestedAsOf == null ? clock.instant() : requestedAsOf;
        List<EmploymentEventDtos.EmploymentDocument> documents = repository.assignments(
                actor.tenantId(), actor.personPublicId(), asOf).stream()
                .map(row -> new EmploymentEventDtos.EmploymentDocument(
                        row.workerPublicId(), row.relationshipPublicId(),
                        row.assignmentPublicId(), row.relationshipType(),
                        row.assignmentStart(), exclusive(row.assignmentEnd()),
                        row.assignmentStatus(), row.assignmentVersion()))
                .toList();
        return new EmploymentEventDtos.EmploymentDocuments(asOf, documents);
    }

    @Transactional(readOnly = true)
    EmploymentEventDtos.AssignmentWorkbench operationsAssignments(Instant requestedAsOf) {
        String capability = authority.require("OPERATIONS_WORKBENCH");
        PeopleRequestContext.Actor actor = actor();
        PopulationBinding population = population(capability, false);
        Instant asOf = requestedAsOf == null ? clock.instant() : requestedAsOf;
        List<EmploymentEventDtos.AssignmentView> values = repository.assignments(
                actor.tenantId(), null, asOf).stream()
                .filter(row -> visible(actor, population.population(), row))
                .map(this::assignmentView).toList();
        return new EmploymentEventDtos.AssignmentWorkbench(asOf, values);
    }

    @Transactional(readOnly = true)
    List<Xcon016WorkplaceAssignmentSnapshotV1> workplaceSnapshots(
            UUID workerPublicId,
            Instant requestedAsOf) {
        PeopleRequestContext.Actor actor = actor();
        Instant asOf = requestedAsOf == null ? clock.instant() : requestedAsOf;
        return repository.assignments(actor.tenantId(), null, asOf).stream()
                .filter(row -> row.workerPublicId().equals(workerPublicId))
                .map(row -> new Xcon016WorkplaceAssignmentSnapshotV1(
                        row.assignmentPublicId(), row.calendarPublicId(), row.assignmentStart(),
                        exclusive(row.assignmentEnd()), snapshotDigest(row),
                        UUID.nameUUIDFromBytes(("workplace:" + row.assignmentPublicId() + ':'
                                + row.assignmentVersion()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        Math.max(1, row.assignmentVersion()), Math.max(1, row.assignmentVersion()),
                        zone(row.timeZone()), row.locationCode(), row.workerPublicId(),
                        row.locationPublicId()))
                .toList();
    }

    private Claim claim(
            PeopleRequestContext.Actor actor,
            PopulationBinding evidence,
            String commandType,
            String action,
            String key,
            String requestDigest,
            UUID targetPublicId,
            long expectedVersion,
            UUID correlationId) {
        Instant now = clock.instant();
        EmploymentEventRepository.ReceiptRow inserted = repository.insertReceipt(
                actor.tenantId(), UUID.randomUUID(), commandType, key, requestDigest,
                actor.personPublicId(), action, actor.personPublicId(),
                evidence.populationDigest(), evidence.fieldRevision(),
                EmploymentEventDtos.PURPOSE, evidence.authorizationRevision(),
                evidence.contextDigest(), targetPublicId, expectedVersion,
                correlationId, now).orElse(null);
        if (inserted != null) return new Claim(inserted, true);
        EmploymentEventRepository.ReceiptRow existing = repository.receiptByIdentity(
                actor.tenantId(), actor.personPublicId(), action, key)
                .orElseThrow(() -> reused(null));
        if (!commandType.equals(existing.commandType())
                || !requestDigest.equals(existing.requestDigest())
                || !actor.personPublicId().equals(existing.actorPublicId())
                || !Objects.equals(targetPublicId, existing.targetPublicId())
                || !Objects.equals(expectedVersion, existing.expectedVersion())
                || !correlationId.equals(existing.correlationId())) {
            throw reused(existing.receiptId());
        }
        return new Claim(existing, false);
    }

    private EmploymentEventDtos.CommandResult replay(
            long tenantId,
            EmploymentEventRepository.ReceiptRow receipt) {
        EmploymentEventRepository.EventRow event = resultEventId(receipt)
                .flatMap(id -> repository.event(tenantId, id)).orElse(null);
        return new EmploymentEventDtos.CommandResult(
                toReceipt(receipt), event == null ? null : view(event),
                event == null ? null : impact(event), true);
    }

    private OptionalUuid resultEventId(EmploymentEventRepository.ReceiptRow receipt) {
        if (receipt.resultReference() == null) return new OptionalUuid(null);
        try {
            JsonNode value = mapper.readTree(receipt.resultReference()).get("eventPublicId");
            return new OptionalUuid(value == null ? null : UUID.fromString(value.asText()));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return new OptionalUuid(null);
        }
    }

    private EmploymentEventDtos.CommandResult result(
            EmploymentEventRepository.ReceiptRow receipt,
            EmploymentEventRepository.EventRow event,
            boolean replayed) {
        return new EmploymentEventDtos.CommandResult(
                toReceipt(receipt), view(event), impact(event), replayed);
    }

    private EmploymentEventDtos.AssignmentImpact impact(
            EmploymentEventRepository.EventRow row) {
        return new EmploymentEventDtos.AssignmentImpact(
                row.publicId(), row.effectiveDate(),
                strings(row.impact().get("blockingIssues")),
                strings(row.impact().get("warnings")), row.impactDigest());
    }

    private EmploymentEventDtos.EventView view(EmploymentEventRepository.EventRow row) {
        return new EmploymentEventDtos.EventView(
                row.publicId(), row.eventType(), row.lifecycleState(), row.effectiveDate(),
                row.payload(), strings(row.impact().get("blockingIssues")), row.impactDigest(),
                row.targetOwnerVersion(), row.version(), row.approvalDecisionRevision(),
                row.publishedWorkerPublicId(), row.publishedRelationshipPublicId(),
                row.publishedAssignmentPublicId(), row.validatedAt(), row.submittedAt(),
                row.approvedAt(), row.publishedAt(), row.createdAt(), row.updatedAt());
    }

    private EmploymentEventDtos.AssignmentView assignmentView(
            EmploymentEventRepository.AssignmentProjection row) {
        Instant from = row.assignmentStart().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = exclusive(row.assignmentEnd()) == null ? null
                : exclusive(row.assignmentEnd()).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new EmploymentEventDtos.AssignmentView(
                row.personPublicId(), row.workerPublicId(), row.workerStatus(),
                row.workerVersion(), row.relationshipPublicId(), row.relationshipType(),
                row.relationshipStart(), row.relationshipEnd(), row.relationshipVersion(),
                row.assignmentPublicId(), row.assignmentStatus(), row.assignmentStart(),
                exclusive(row.assignmentEnd()), row.primaryAssignment(),
                row.fte() == null ? null : row.fte().toPlainString(), row.assignmentVersion(),
                ref("HRM.LegalEmployer.v1", row.legalEmployerPublicId(),
                        row.legalEmployerVersion(), from, to),
                ref("HRM.Organization.v1", row.organizationPublicId(),
                        zero(row.organizationVersion()), from, to),
                ref("HRM.Position.v1", row.positionPublicId(),
                        zero(row.positionVersion()), from, to),
                ref("HRM.Job.v1", row.jobPublicId(), zero(row.jobVersion()), from, to),
                ref("HRM.WorkLocation.v1", row.locationPublicId(),
                        zero(row.locationVersion()), from, to));
    }

    private EmploymentEventDtos.OwnerRef ref(
            String schema,
            UUID id,
            long revision,
            Instant from,
            Instant to) {
        return id == null ? null : new EmploymentEventDtos.OwnerRef(
                "HRM", schema, id, revision,
                canonicalizer.ownerRefDigest(schema, id, revision), from, to);
    }

    private Map<String, Object> impact(
            EmploymentEventDtos.NormalizedCommand command,
            EmploymentEventRepository.PreflightSnapshot snapshot) {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("blockingIssues", List.of());
        impact.put("warnings", List.of());
        impact.put("eventType", command.eventType());
        impact.put("effectiveDate", command.relationshipStart());
        impact.put("workerVersion", snapshot.workerVersion());
        impact.put("legalEmployerRevision", snapshot.legalEmployer().version());
        impact.put("organizationRevision", snapshot.organization().version());
        impact.put("positionRevision", snapshot.position().version());
        impact.put("jobRevision", snapshot.job().version());
        impact.put("locationRevision", snapshot.location().version());
        impact.put("primaryOverlap", snapshot.primaryOverlap());
        return impact;
    }

    private Map<String, Object> approvalBinding(EmploymentEventRepository.EventRow event) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("eventPublicId", event.publicId());
        binding.put("eventType", event.eventType());
        long submittedVersion = switch (event.lifecycleState()) {
            case "APPROVED", "REJECTED", "PUBLISHED" -> event.version() - 1;
            default -> event.version();
        };
        binding.put("eventVersion", submittedVersion);
        binding.put("contentDigest", event.contentDigest());
        binding.put("impactDigest", event.impactDigest());
        binding.put("approvalRequestId", event.approvalRequestId());
        return binding;
    }

    private void requireReferences(
            EmploymentEventDtos.NormalizedCommand command,
            EmploymentEventRepository.PreflightSnapshot snapshot) {
        Instant effectiveAt = command.context().effectiveAt();
        requireReference(command.legalEmployerRef(), snapshot.legalEmployer(), effectiveAt);
        requireReference(command.assignment().organizationRef(), snapshot.organization(), effectiveAt);
        requireReference(command.assignment().positionRef(), snapshot.position(), effectiveAt);
        requireReference(command.assignment().jobRef(), snapshot.job(), effectiveAt);
        requireReference(command.assignment().workLocationRef(), snapshot.location(), effectiveAt);
        if ("CONCURRENT_EMPLOYMENT".equals(command.eventType())
                && command.expectedOwnerVersion() != snapshot.workerVersion()) {
            throw stale(snapshot.workerVersion());
        }
    }

    private void requireReference(
            EmploymentEventDtos.OwnerRef expected,
            EmploymentEventRepository.ReferenceRow actual,
            Instant effective) {
        if (expected.revision() != actual.version()
                || !expected.contentHash().equals(canonicalizer.ownerRefDigest(
                expected.schemaId(), expected.publicId(), actual.version()))) {
            throw stale(actual.version());
        }
        if (expected.validFrom() != null && effective.isBefore(expected.validFrom())
                || expected.validToExclusive() != null
                && !effective.isBefore(expected.validToExclusive())) {
            throw stale(actual.version());
        }
    }

    private PopulationBinding population(String capability, boolean lock) {
        HcmPopulationScopeService.ResolvedPopulation population = lock
                ? populationScopes.requireOperationsForMutation("READ")
                : populationScopes.requireOperations("READ");
        populationScopes.requireTrustedScope(population, "hcm.operations",
                "TARGET_POPULATION", "WORKFORCE_TARGET_POPULATION", "ORG_UNIT",
                "LEGAL_ENTITY");
        populationScopes.requireField(population, "EMPLOYMENT");
        HcmPepContext.Evidence pep = HcmPepContext.require();
        String revision = population.targetPopulationRevision();
        String populationDigest = canonicalizer.digest(revision);
        long fieldRevision = canonicalizer.positiveRevision(
                revision + '|' + population.scope().fieldGroups().stream().sorted().toList());
        long authorizationRevision = canonicalizer.positiveRevision(pep.decisionRevision());
        String contextDigest = canonicalizer.digest(Map.of(
                "routeContractKey", pep.authority().routeContractKey(),
                "capabilityContractKey", capability,
                "decisionRevision", pep.decisionRevision(),
                "contextKey", pep.contextKey(), "scopeKey", pep.scopeKey(),
                "purposeCode", EmploymentEventDtos.PURPOSE));
        return new PopulationBinding(population, populationDigest, fieldRevision,
                authorizationRevision, contextDigest);
    }

    private void requirePopulation(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            EmploymentEventRepository.PreflightSnapshot snapshot,
            boolean lock) {
        HcmPopulationRepository.PopulationScope scope = population.scope();
        boolean organization = scope.tenantWide()
                || scope.organizationIds().contains(snapshot.organization().publicId());
        boolean worker = snapshot.workerId() == null || (lock
                ? populations.lockWorkerInPopulation(
                        actor.tenantId(), scope, snapshot.workerId())
                : populations.containsWorker(actor.tenantId(), scope, snapshot.workerId()));
        if (!organization || !worker) throw notDisclosed();
    }

    private boolean visible(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            EmploymentEventRepository.AssignmentProjection row) {
        HcmPopulationRepository.PopulationScope scope = population.scope();
        return (scope.tenantWide() || scope.organizationIds().contains(row.organizationPublicId()))
                && (scope.tenantWide()
                || populations.containsWorker(actor.tenantId(), scope, row.workerId()));
    }

    private CommandReceiptV1 toReceipt(EmploymentEventRepository.ReceiptRow row) {
        return new CommandReceiptV1(
                row.receiptId(), row.commandType(), row.action(), row.subjectPublicId(),
                row.populationDigest(), row.fieldRevision(), row.purpose(),
                row.authorizationRevision(), receiptStatus(row.lifecycleState()),
                row.correlationId(), row.requestDigest(), row.createdAt());
    }

    private String receiptStatus(String state) {
        return switch (state) {
            case "RECEIVED" -> "ACCEPTED";
            case "VALIDATING", "RUNNING" -> "RUNNING";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "REJECTED", "CANCELLED" -> "REJECTED";
            case "FAILED_RETRYABLE", "DEAD" -> "FAILED";
            default -> "RESULT_UNKNOWN";
        };
    }

    private String snapshotDigest(EmploymentEventRepository.AssignmentProjection row) {
        return canonicalizer.digest(Map.of(
                "workerPublicId", row.workerPublicId(),
                "assignmentPublicId", row.assignmentPublicId(),
                "assignmentVersion", row.assignmentVersion(),
                "locationPublicId", row.locationPublicId()));
    }

    private record PopulationBinding(
            HcmPopulationScopeService.ResolvedPopulation population,
            String populationDigest,
            long fieldRevision,
            long authorizationRevision,
            String contextDigest) { }

    private record Claim(EmploymentEventRepository.ReceiptRow receipt, boolean fresh) { }

    private record OptionalUuid(UUID value) {
        java.util.Optional<UUID> flatMapValue() {
            return java.util.Optional.ofNullable(value);
        }

        <T> java.util.Optional<T> flatMap(
                java.util.function.Function<UUID, java.util.Optional<T>> mapper) {
            return flatMapValue().flatMap(mapper);
        }
    }
}
