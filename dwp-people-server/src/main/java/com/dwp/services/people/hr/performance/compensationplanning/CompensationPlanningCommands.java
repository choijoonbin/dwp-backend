package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Population;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PerTypedArtifactQueryPort;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.people.hr.performance.compensationplanning.CompensationPlanningSupport.*;

/** Resolves command authority and receipts and records compensation transition evidence. */
final class CompensationPlanningCommands {

    private final CompensationPlanningRepository repository;
    private final CompensationPlanningCanonicalizer canonicalizer;
    private final AuditOutboxRecorder audit;
    private final Clock clock;
    private final Supplier<CompensationPlanningAuthorityPort> authoritySupplier;
    private final CompensationPlanningRules rules;
    private final CompensationPlanningResults results;

    CompensationPlanningCommands(CompensationPlanningRepository repository,
            CompensationPlanningCanonicalizer canonicalizer, AuditOutboxRecorder audit,
            Clock clock, Supplier<CompensationPlanningAuthorityPort> authoritySupplier,
            CompensationPlanningRules rules, CompensationPlanningResults results) {
        this.repository = repository;
        this.canonicalizer = canonicalizer;
        this.audit = audit;
        this.clock = clock;
        this.authoritySupplier = authoritySupplier;
        this.rules = rules;
        this.results = results;
    }

    CommandContext commandContext(
            String action,
            String operation,
            String capability,
            UUID aggregateId,
            long expectedVersion,
            Object body,
            String idempotencyKey,
            String correlationId,
            boolean stepUp,
            List<String> requiredFields,
            String path,
            String originatingAction) {
        PeopleRequestContext.Actor actor = actor(action);
        UUID subject = CompensationPlanningAuthorization.requireSubject(actor);
        UUID correlation = CompensationPlanningDtos.canonicalCorrelationId(correlationId);
        String key = CompensationPlanningDtos.canonicalIdempotencyKey(idempotencyKey);
        CompensationPlanningAuthorityPort.AuthorityEvidence authority = authority(
                actor, subject, action, operation, capability, aggregateId,
                stepUp, requiredFields);
        repository.bindTenant(actor.tenantId());
        String hash = canonicalizer.commandDigest(
                "POST", path, expectedVersion, actor.tenantId(), subject, body);
        CompensationPlanningDtos.CommandReceipt replay = replay(
                actor.tenantId(), subject, originatingAction, key, operation,
                aggregateId, expectedVersion, hash, correlation, authority).orElse(null);
        return new CommandContext(
                actor, subject, authority, key, correlation, hash,
                operation, originatingAction, databaseNow(), replay);
    }

    QueryContext queryContext(String operation, UUID aggregateId) {
        PeopleRequestContext.Actor actor = actor("VIEW");
        UUID subject = CompensationPlanningAuthorization.requireSubject(actor);
        CompensationPlanningAuthorityPort.AuthorityEvidence authority = authority(
                actor, subject, "VIEW", operation,
                CompensationPlanningAuthorization.VIEW_CAPABILITY,
                aggregateId, false, List.of());
        repository.bindTenant(actor.tenantId());
        return new QueryContext(actor, subject, authority);
    }

    CompensationPlanningAuthorityPort.AuthorityEvidence authority(
            PeopleRequestContext.Actor actor,
            UUID subject,
            String action,
            String operation,
            String capability,
            UUID aggregateId,
            boolean stepUp,
            List<String> requiredFields) {
        CompensationPlanningAuthorityPort port = authoritySupplier.get();
        if (port == null) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Compensation-planning PEP authority is unavailable.");
        }
        CompensationPlanningAuthorityPort.AuthorityRequest request =
                new CompensationPlanningAuthorityPort.AuthorityRequest(
                        actor.tenantId(), actor.userId(), subject,
                        CompensationPlanningAuthorization.APPLICATION,
                        CompensationPlanningAuthorization.resourceForAction(action),
                        action, operation, capability,
                        CompensationPlanningAuthorization.PURPOSE, aggregateId);
        try {
            return CompensationPlanningAuthorization.verify(
                    request, port.authorize(request), stepUp, requiredFields);
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Compensation-planning PEP authority is unavailable.", exception);
        }
    }

    PeopleRequestContext.Actor actor(String action) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.tenantId() == null || actor.userId() == null) {
            throw forbidden("Verified tenant and actor are required.");
        }
        CompensationPlanningAuthorization.requireActorPermission(actor, action);
        return actor;
    }

    Reservation reserve(
            CommandContext context,
            UUID aggregateId,
            long expectedVersion) {
        UUID receiptId = UUID.randomUUID();
        if (repository.reserveReceipt(
                context.actor().tenantId(), context.actor().userId(), context.subject(),
                receiptId, context.idempotencyKey(), context.operation(),
                context.originatingAction(), aggregateId, expectedVersion,
                context.requestHash(), context.authority())) {
            return new Reservation(receiptId, null);
        }
        return new Reservation(null, concurrentReplay(
                context.actor().tenantId(), context.subject(), context.originatingAction(),
                context.idempotencyKey(), context.operation(), aggregateId,
                expectedVersion, context.requestHash(), context.correlationId(),
                context.authority()));
    }

    Optional<CompensationPlanningDtos.CommandReceipt> replay(
            long tenantId,
            UUID subject,
            String originatingAction,
            String key,
            String operation,
            UUID aggregateId,
            long expectedVersion,
            String requestHash,
            UUID currentCorrelationId,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority) {
        Optional<CompensationPlanningRepository.ReceiptRecord> existing = repository
                .receiptByIdempotency(tenantId, subject, originatingAction, key);
        if (existing.isEmpty()) return Optional.empty();
        CompensationPlanningRepository.ReceiptRecord receipt = existing.get();
        if (!operation.equals(receipt.operationId())
                || receipt.expectedVersion() != expectedVersion
                || !requestHash.equals(receipt.requestHash())
                || (aggregateId != null && !aggregateId.equals(receipt.aggregateId()))) {
            throw conflict("Idempotency-Key is bound to a different command.", null);
        }
        if (!Objects.equals(authority.populationScopeDigest(), receipt.populationScopeDigest())
                || authority.fieldPolicyRevision() != receipt.fieldPolicyRevision()
                || !Objects.equals(authority.purposeCode(), receipt.purposeCode())
                || authority.authorizationRevision() != receipt.authorizationRevision()) {
            throw forbidden("Current authority no longer matches the command receipt.");
        }
        if (!"SUCCEEDED".equals(receipt.state())) {
            UUID replayCorrelation = correlation(receipt.outboxPayload());
            return Optional.of(new CompensationPlanningDtos.CommandReceipt(
                    receipt.receiptId(), "RESULT_UNKNOWN", receipt.aggregateId(),
                    receipt.appliedVersion(), replayCorrelation == null
                            ? currentCorrelationId : replayCorrelation,
                    receipt.completedAt() == null ? receipt.acceptedAt() : receipt.completedAt(),
                    requestHash));
        }
        requireReplayOwnerAndPopulation(tenantId, operation, receipt.aggregateId(), authority);
        String payloadDigest = PUBLISH.equals(operation)
                && receipt.outboxPayload() != null
                ? receipt.outboxPayload().path("payloadDigest").asText(requestHash)
                : requestHash;
        UUID responseAggregateId = PUBLISH.equals(operation)
                ? receipt.resultRef() : receipt.aggregateId();
        UUID replayCorrelation = correlation(receipt.outboxPayload());
        if (replayCorrelation == null) {
            throw conflict("Persisted command result evidence is incomplete.", null);
        }
        return Optional.of(results.completed(
                receipt.receiptId(), responseAggregateId, receipt.appliedVersion(),
                replayCorrelation, receipt.completedAt(), payloadDigest));
    }

    CompensationPlanningDtos.CommandReceipt concurrentReplay(
            long tenantId,
            UUID subject,
            String originatingAction,
            String key,
            String operation,
            UUID aggregateId,
            long expectedVersion,
            String hash,
            UUID correlationId,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority) {
        return replay(tenantId, subject, originatingAction, key, operation,
                aggregateId, expectedVersion, hash, correlationId, authority)
                .orElseThrow(() -> conflict(
                        "The concurrent idempotent command winner is unavailable.", null));
    }

    void requireReplayOwnerAndPopulation(
            long tenantId,
            String operation,
            UUID aggregateId,
            CompensationPlanningAuthorityPort.AuthorityEvidence authority) {
        String purpose = APPROVE.equals(operation) || PUBLISH.equals(operation)
                ? PerTypedArtifactQueryPort.APPROVAL_PURPOSE
                : PerTypedArtifactQueryPort.PLANNING_PURPOSE;
        if (UPSERT_PROPOSAL.equals(operation) || SUBMIT_PROPOSAL.equals(operation)) {
            CompensationPlanningRepository.ProposalSelection selection = repository
                    .proposalById(tenantId, aggregateId)
                    .orElseThrow(() -> forbidden(
                            "The replayed proposal is outside current ownership."));
            CompensationPlanningRepository.CycleRow cycle = repository
                    .cycle(tenantId, selection.cycleId(), false)
                    .orElseThrow(() -> forbidden(
                            "The replayed proposal cycle is outside current ownership."));
            Population currentPopulation = rules.population(tenantId, cycle, purpose);
            Proposal content = rules.sealedProposal(
                    tenantId, cycle.publicId(), selection.proposal(), purpose);
            boolean permitted = currentPopulation.lines().stream()
                    .filter(line -> line.workerPublicId().equals(content.workerPublicId()))
                    .filter(line -> line.assignmentPublicId().equals(
                            content.assignmentPublicId()))
                    .anyMatch(line -> authority.targetPopulation()
                            .permitsWorkerAssignment(
                                    line.workerPublicId(), line.assignmentPublicId())
                            && authority.targetPopulation()
                            .permitsOrganization(line.organizationPublicId()));
            if (!permitted) {
                throw forbidden("The replayed proposal is outside current population authority.");
            }
            return;
        }
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(tenantId, aggregateId, false)
                .orElseThrow(() -> forbidden(
                        "The replayed cycle is outside current ownership."));
        Population currentPopulation = rules.population(tenantId, cycle, purpose);
        if (!authority.targetPopulation().tenantWide()
                && currentPopulation.lines().stream().noneMatch(line ->
                authority.targetPopulation().permitsWorkerAssignment(
                        line.workerPublicId(), line.assignmentPublicId())
                        && authority.targetPopulation()
                        .permitsOrganization(line.organizationPublicId()))) {
            throw forbidden("The replayed cycle is outside current population authority.");
        }
    }

    UUID correlation(JsonNode payload) {
        if (payload == null || !payload.hasNonNull("correlationId")) return null;
        return UUID.fromString(payload.path("correlationId").asText());
    }

    CompensationPlanningRepository.CycleRow cycleForUpdate(
            long tenantId,
            UUID cycleId,
            long expectedVersion,
            String... allowedStates) {
        CompensationPlanningRepository.CycleRow cycle = repository
                .cycle(tenantId, cycleId, true)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (cycle.aggregateVersion() != expectedVersion) throw stale();
        if (!Set.of(allowedStates).contains(cycle.status())) {
            throw invalid("Compensation cycle is not in an allowed state.");
        }
        return cycle;
    }

    void closeTransition(
            PeopleRequestContext.Actor actor,
            UUID receiptId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            String fromState,
            String toState,
            UUID correlationId,
            Instant now) {
        closeTransition(actor, receiptId, aggregateType, aggregateId, aggregateVersion,
                eventType, fromState, toState, correlationId, now, aggregateId);
    }

    void closeTransition(
            PeopleRequestContext.Actor actor,
            UUID receiptId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventType,
            String fromState,
            String toState,
            UUID correlationId,
            Instant now,
            UUID resultRef) {
        repository.completeReceipt(
                actor.tenantId(), receiptId, aggregateVersion, resultRef, now);
        repository.insertTransitionOutbox(
                actor.tenantId(), receiptId, UUID.randomUUID(), aggregateType,
                aggregateId, aggregateVersion, eventType, fromState, toState,
                correlationId, now);
    }

    void audit(
            PeopleRequestContext.Actor actor,
            String action,
            UUID targetId,
            UUID receiptId,
            UUID correlationId,
            Map<String, ?> metadata) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("HIGH")
                .riskScore(80)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("hris-performance-compensation")
                .targetType("COMPENSATION_PLANNING")
                .targetId(targetId.toString())
                .correlationId(correlationId.toString())
                .approvalId(receiptId.toString())
                .metadata(Map.copyOf(metadata))
                .retentionClass("EXTENDED")
                .build());
    }

    Instant databaseNow() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
