package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;
import com.dwp.services.people.hris.contracts.hrm011.Hrm011EventOutbox;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Ordered, idempotent, atomic PEP-HRM-011 command owner. */
@Service
@ConditionalOnProperty(prefix = EmploymentContractRolloutGate.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
public class EmploymentContractService {
    static final String COMMAND = "EMPLOYMENT_CONTRACT_ISSUE";
    static final String ACTION = "ISSUE_EMPLOYMENT_CONTRACT";

    private final EmploymentContractRolloutGate rollout;
    private final EmploymentContractAuthorization authorization;
    private final EmploymentContractStore store;
    private final EmploymentContractTemplateQueryPort templates;
    private final EmploymentContractArtifactPort artifacts;
    private final EmploymentContractCanonicalizer canonicalizer;
    private final HcmHighRiskCommandGuard highRisk;
    private final Hrm011EventOutbox events;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public EmploymentContractService(
            EmploymentContractRolloutGate rollout,
            EmploymentContractAuthorization authorization,
            EmploymentContractStore store,
            EmploymentContractTemplateQueryPort templates,
            EmploymentContractArtifactPort artifacts,
            EmploymentContractCanonicalizer canonicalizer,
            HcmHighRiskCommandGuard highRisk,
            Hrm011EventOutbox events,
            ObjectMapper mapper) {
        this(rollout, authorization, store, templates, artifacts, canonicalizer,
                highRisk, events, mapper, Clock.systemUTC());
    }

    EmploymentContractService(
            EmploymentContractRolloutGate rollout,
            EmploymentContractAuthorization authorization,
            EmploymentContractStore store,
            EmploymentContractTemplateQueryPort templates,
            EmploymentContractArtifactPort artifacts,
            EmploymentContractCanonicalizer canonicalizer,
            HcmHighRiskCommandGuard highRisk,
            Hrm011EventOutbox events,
            ObjectMapper mapper,
            Clock clock) {
        this.rollout = Objects.requireNonNull(rollout);
        this.authorization = Objects.requireNonNull(authorization);
        this.store = Objects.requireNonNull(store);
        this.templates = Objects.requireNonNull(templates);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
        this.highRisk = Objects.requireNonNull(highRisk);
        this.events = Objects.requireNonNull(events);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public CommandResult issue(
            EmploymentContractDtos.IssueRequest request,
            String idempotencyKey,
            String ifMatch,
            long expectedHeaderVersion,
            UUID correlationId,
            String optionalPurpose,
            HcmStepUpHeaders stepUp) {
        PeopleRequestContext.Actor actor = actor();
        rollout.requireEnrolled(actor.tenantId());
        validateRequest(request, correlationId);
        String key = idempotencyKey(idempotencyKey);
        long expectedVersion = strongVersion(ifMatch);
        if (expectedVersion != expectedHeaderVersion) {
            throw EmploymentContractException.conflict(
                    "EMPLOYMENT_CONTRACT_VERSION_HEADER_MISMATCH",
                    "If-Match and X-DWP-Expected-Object-Version must be equal.");
        }
        EmploymentContractAuthorization.Decision initial =
                authorization.authorize(optionalPurpose);
        if (!actor.tenantId().equals(initial.actor().tenantId())
                || !actor.userId().equals(initial.actor().userId())
                || !actor.personPublicId().equals(initial.actor().personPublicId())) {
            throw EmploymentContractException.denied(
                    "The PEP-HRM-011 actor changed during evaluation.");
        }
        requireStructuralStepUp(stepUp, key, expectedVersion,
                initial.authorizationRevision());
        Map<String, Object> digestInput = new LinkedHashMap<>();
        digestInput.put("schemaVersion", "PEP-HRM-011.v1");
        digestInput.put("method", "POST");
        digestInput.put("path", EmploymentContractAuthorization.BROWSER_PATH);
        digestInput.put("body", request);
        digestInput.put("ifMatch", ifMatch);
        digestInput.put("expectedObjectVersion", expectedHeaderVersion);
        digestInput.put("trustedPurpose", EmploymentContractAuthorization.PURPOSE);
        String requestDigest = canonicalizer.digest(digestInput);

        EmploymentContractStore.Receipt existing =
                store.receipt(actor.tenantId(), key).orElse(null);
        if (existing != null) return replay(requireReplay(
                existing, requestDigest, actor, request, expectedVersion, correlationId));

        Instant now = clock.instant();
        UUID receiptId = canonicalizer.deterministicUuid(
                "receipt", actor.tenantId(), COMMAND, key);
        String populationDigest = canonicalizer.digest(Map.of(
                "scopeKey", initial.scopeKey(),
                "actor", actor.personPublicId(),
                "purpose", EmploymentContractAuthorization.INTERNAL_PURPOSE));
        long fieldRevision = canonicalizer.positiveRevision(
                EmploymentContractAuthorization.FIELDS.stream().sorted().toList().toString());
        long authorizationRevision = canonicalizer.positiveRevision(
                initial.authorizationRevision());
        String contextDigest = canonicalizer.digest(Map.of(
                "tenantId", actor.tenantId(),
                "profile", EmploymentContractAuthorization.PROFILE,
                "capability", EmploymentContractAuthorization.CAPABILITY,
                "authorizationRevision", initial.authorizationRevision()));
        EmploymentContractStore.Reservation reservation =
                new EmploymentContractStore.Reservation(
                        actor.tenantId(), receiptId, key, requestDigest,
                        actor.personPublicId(), request.relationshipPublicId(),
                        populationDigest, fieldRevision, authorizationRevision,
                        contextDigest, expectedVersion, correlationId, now);
        EmploymentContractStore.Receipt claimed = store.reserve(reservation).orElse(null);
        if (claimed == null) {
            EmploymentContractStore.Receipt concurrent = store.receipt(
                    actor.tenantId(), key).orElseThrow(() ->
                    EmploymentContractException.conflict(
                            "IDEMPOTENCY_RESERVATION_CONFLICT",
                            "The concurrent employment-contract reservation is unavailable."));
            return replay(requireReplay(
                    concurrent, requestDigest, actor, request,
                    expectedVersion, correlationId));
        }

        // Reservation precedes all provider calls. The relationship lock serializes
        // version checking and ensures exactly one assignment/basis authority.
        List<EmploymentContractStore.Aggregate> candidates = store.lockAggregate(
                actor.tenantId(), request.relationshipPublicId(), request.effectiveFrom());
        if (candidates.isEmpty()) {
            throw EmploymentContractException.denied(
                    "The effective work relationship is unavailable.");
        }
        if (candidates.size() != 1) {
            throw EmploymentContractException.conflict(
                    "EMPLOYMENT_CONTRACT_SNAPSHOT_AMBIGUOUS",
                    "Assignment or compensation-basis authority is ambiguous.");
        }
        EmploymentContractStore.Aggregate aggregate = candidates.getFirst();
        if (aggregate.relationship().resourceVersion() != expectedVersion) {
            throw EmploymentContractException.stale(
                    aggregate.relationship().resourceVersion());
        }
        EmploymentContractTemplateQueryPort.TemplateQuery templateQuery =
                new EmploymentContractTemplateQueryPort.TemplateQuery(
                        actor.tenantId(), request.relationshipPublicId(),
                        aggregate.relationship().legalEmployerPublicId(),
                        request.templatePublicId(), request.templateVersion(),
                        request.locale(), request.effectiveFrom());
        EmploymentContractTemplateSnapshotV1 template =
                templates.requirePublished(templateQuery);
        EmploymentContractTemplateQueryPort.requireTrustedScope(templateQuery, template);
        requireTemplate(actor.tenantId(), request, template, now);
        EmploymentContractDtos.GenerationSnapshot generation =
                new EmploymentContractDtos.GenerationSnapshot(
                        aggregate.relationship(), aggregate.assignment(),
                        aggregate.compensationBasis(), template, request.locale());
        String generationDigest = canonicalizer.generationDigest(generation);
        if (!generationDigest.equals(request.snapshotDigest())) {
            throw EmploymentContractException.conflict(
                    "EMPLOYMENT_CONTRACT_SNAPSHOT_STALE",
                    "The generation snapshot digest is stale.");
        }
        EmploymentContractAuthorization.Decision completed =
                authorization.requireMutationPopulation(
                        initial, aggregate.workerId(), aggregate.subjectPersonPublicId());
        UUID contractPublicId = canonicalizer.deterministicUuid(
                "employment-contract", actor.tenantId(), receiptId,
                request.relationshipPublicId());
        UUID artifactRequestId = canonicalizer.deterministicUuid(
                "employment-contract-artifact", actor.tenantId(), receiptId,
                contractPublicId);
        Map<String, Object> stepPayload = new LinkedHashMap<>();
        stepPayload.put("relationshipPublicId", request.relationshipPublicId());
        stepPayload.put("templatePublicId", request.templatePublicId());
        stepPayload.put("templateVersion", request.templateVersion());
        stepPayload.put("locale", request.locale());
        stepPayload.put("effectiveFrom", request.effectiveFrom());
        stepPayload.put("effectiveTo", request.effectiveTo());
        stepPayload.put("snapshotDigest", request.snapshotDigest());
        highRisk.requireExact(
                EmploymentContractAuthorization.CAPABILITY,
                EmploymentContractAuthorization.TARGET_TYPE,
                request.relationshipPublicId().toString(), expectedVersion,
                EmploymentContractAuthorization.BROWSER_PATH, stepPayload, stepUp);

        EmploymentContractDtos.ArtifactRequest artifactRequest =
                new EmploymentContractDtos.ArtifactRequest(
                        artifactRequestId, contractPublicId, actor.tenantId(),
                        correlationId, aggregate.relationship(), aggregate.assignment(),
                        aggregate.compensationBasis(), template, request.locale(),
                        request.effectiveFrom(), request.effectiveTo(), generationDigest);
        EmploymentContractDtos.ArtifactResult artifact = artifacts.issue(artifactRequest);
        requireArtifact(artifactRequest, artifact);
        EmploymentContractStore.IssuedContract issued = store.insertIssued(
                new EmploymentContractStore.Issue(
                        actor.tenantId(), contractPublicId, aggregate, template,
                        request.locale(), generationDigest, artifact,
                        request.effectiveFrom(), request.effectiveTo(), correlationId,
                        actor.userId()));
        if (issued.version() < 1 || !contractPublicId.equals(issued.publicId())) {
            throw new IllegalStateException("The persisted employment contract is invalid.");
        }
        EmploymentContractStore.Receipt completedReceipt = store.complete(
                actor.tenantId(), claimed.receiptId(), contractPublicId,
                issued.version(), artifact.issuedAt());
        store.appendSuccessAudit(actor.tenantId(), actor.userId(), contractPublicId,
                correlationId, artifact.issuedAt());
        DomainEventEnvelope event = events.employmentContract(
                new Hrm011EventOutbox.ContractIssued(
                        actor.tenantId(), contractPublicId,
                        aggregate.relationship().workerPublicId(), template.contractType(),
                        request.effectiveFrom(), request.effectiveTo(),
                        artifact.documentReference(), issued.version(), correlationId,
                        claimed.receiptId(), artifact.issuedAt()));
        store.appendOutbox(actor.tenantId(), event,
                event.data().get("payloadDigest").textValue());
        return new CommandResult(toReceipt(completedReceipt), issued.version());
    }

    private EmploymentContractStore.Receipt requireReplay(
            EmploymentContractStore.Receipt receipt,
            String requestDigest,
            PeopleRequestContext.Actor actor,
            EmploymentContractDtos.IssueRequest request,
            long expectedVersion,
            UUID correlationId) {
        if (!requestDigest.equals(receipt.requestDigest())
                || !actor.personPublicId().equals(receipt.actorPublicId())
                || !request.relationshipPublicId().equals(receipt.subjectPublicId())
                || !EmploymentContractAuthorization.PURPOSE.equals(receipt.purpose())
                || receipt.expectedVersion() != expectedVersion) {
            throw EmploymentContractException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key is bound to a different request.");
        }
        return receipt;
    }

    private CommandResult replay(EmploymentContractStore.Receipt receipt) {
        long version = receipt.expectedVersion();
        if ("SUCCEEDED".equals(receipt.state())) {
            try {
                JsonNode result = mapper.readTree(receipt.resultReference());
                JsonNode stored = result.get("contractVersion");
                if (stored == null || !stored.canConvertToLong() || stored.longValue() < 1) {
                    throw new IllegalArgumentException();
                }
                version = stored.longValue();
            } catch (Exception exception) {
                throw EmploymentContractException.conflict(
                        "IDEMPOTENCY_RESULT_UNAVAILABLE",
                        "The completed employment-contract receipt is corrupt.");
            }
        }
        return new CommandResult(toReceipt(receipt), version);
    }

    private CommandReceiptV1 toReceipt(EmploymentContractStore.Receipt value) {
        return new CommandReceiptV1(
                value.receiptId(), COMMAND, ACTION, value.subjectPublicId(),
                value.populationDigest(), value.fieldRevision(),
                EmploymentContractAuthorization.PURPOSE,
                value.authorizationRevision(), receiptStatus(value.state()),
                value.correlationId(), value.requestDigest(), value.createdAt());
    }

    private String receiptStatus(String value) {
        return switch (value) {
            case "RUNNING", "VALIDATING" -> "RUNNING";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "REJECTED", "CANCELLED" -> "REJECTED";
            case "FAILED_RETRYABLE", "DEAD" -> "FAILED";
            default -> "RESULT_UNKNOWN";
        };
    }

    private void requireTemplate(
            long tenantId, EmploymentContractDtos.IssueRequest request,
            EmploymentContractTemplateSnapshotV1 template, Instant now) {
        if (template == null || template.tenantId() != tenantId
                || !request.templatePublicId().equals(template.templatePublicId())
                || request.templateVersion() != template.templateVersion()
                || !"EMPLOYMENT_CONTRACT".equals(template.templateType())
                || !"PUBLISHED".equals(template.state())
                || !request.locale().equals(template.locale())
                || template.publishedAt().isAfter(now)
                || request.effectiveFrom().isBefore(template.effectiveFrom())
                || template.effectiveTo() != null
                && !request.effectiveFrom().isBefore(template.effectiveTo())) {
            throw EmploymentContractException.unavailable(
                    "The authoritative PDX-015 template is unavailable or mismatched.");
        }
    }

    private void requireArtifact(
            EmploymentContractDtos.ArtifactRequest request,
            EmploymentContractDtos.ArtifactResult result) {
        if (result == null
                || !request.artifactRequestId().equals(result.artifactRequestId())
                || !request.contractPublicId().equals(result.contractPublicId())
                || !request.templateSnapshot().templatePublicId()
                .equals(result.templatePublicId())
                || request.templateSnapshot().templateVersion() != result.templateVersion()
                || !request.generationSnapshotDigest()
                .equals(result.generationSnapshotDigest())
                || result.issuedAt().isAfter(clock.instant().plusSeconds(300))) {
            throw EmploymentContractException.unavailable(
                    "The employment-contract artifact result is null or mismatched.");
        }
    }

    private void validateRequest(
            EmploymentContractDtos.IssueRequest request, UUID correlationId) {
        if (request == null || request.relationshipPublicId() == null
                || request.templatePublicId() == null || request.templateVersion() < 1
                || request.locale() == null || request.effectiveFrom() == null
                || request.snapshotDigest() == null
                || !request.snapshotDigest().matches("[0-9a-f]{64}")
                || correlationId == null) {
            throw EmploymentContractException.invalid(
                    "The employment-contract request is incomplete.");
        }
    }

    private String idempotencyKey(String value) {
        if (value == null || value.length() < 16 || value.length() > 128
                || !value.equals(value.trim())) {
            throw EmploymentContractException.invalid(
                    "An Idempotency-Key between 16 and 128 characters is required.");
        }
        return value;
    }

    private long strongVersion(String value) {
        if (value == null || !value.matches("\"(?:0|[1-9][0-9]*)\"")) {
            throw EmploymentContractException.invalid(
                    "If-Match must be a strong quoted canonical nonnegative version.");
        }
        try {
            return Long.parseLong(value.substring(1, value.length() - 1));
        } catch (RuntimeException exception) {
            throw EmploymentContractException.invalid("If-Match is invalid.");
        }
    }

    private void requireStructuralStepUp(
            HcmStepUpHeaders value, String idempotency, long expectedVersion,
            String authorizationRevision) {
        if (value == null || value.challenge() == null || value.challenge().isBlank()
                || !idempotency.equals(value.idempotencyKey())
                || !authorizationRevision.equals(value.decisionRevision())
                || !Objects.equals(expectedVersion, value.expectedObjectVersion())) {
            throw EmploymentContractException.conflict(
                    "STEP_UP_CHALLENGE_MISMATCH",
                    "HIGH step-up evidence is not bound to this command.");
        }
    }

    private PeopleRequestContext.Actor actor() {
        try {
            PeopleRequestContext.Actor actor = PeopleRequestContext.require();
            if (actor.userId() == null || actor.userId() < 1
                    || actor.tenantId() == null || actor.tenantId() < 1
                    || actor.personPublicId() == null) throw new IllegalStateException();
            return actor;
        } catch (RuntimeException exception) {
            throw EmploymentContractException.denied(
                    "A trusted tenant-bound People identity is required.");
        }
    }

    public record CommandResult(CommandReceiptV1 receipt, long entityVersion) {
    }
}
