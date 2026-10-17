package com.dwp.services.people.hris.employeeservice.certificate;

import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Atomic Stage-A metadata request/issue owner service. */
@Service
@ConditionalOnProperty(prefix = Hrm009RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
class CertificateService {

    static final String COMMAND_TYPE = "CERTIFICATE_REQUEST";
    static final String ACTION = "REQUEST_CERTIFICATE";
    static final String COMMAND_SCHEMA_VERSION = "PEP-HRM-012.v1";

    private final Hrm009RolloutGate rollout;
    private final CertificateAuthority authority;
    private final CertificateRepository repository;
    private final HcmPopulationRepository populationRepository;
    private final CertificateProjector projector;
    private final CertificateCursorCodec cursors;
    private final CertificateCanonicalizer canonicalizer;
    private final CertificateArtifactPort artifacts;
    private final HcmHighRiskCommandGuard highRisk;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    CertificateService(
            Hrm009RolloutGate rollout,
            CertificateAuthority authority,
            CertificateRepository repository,
            HcmPopulationRepository populationRepository,
            CertificateProjector projector,
            CertificateCursorCodec cursors,
            CertificateCanonicalizer canonicalizer,
            CertificateArtifactPort artifacts,
            HcmHighRiskCommandGuard highRisk,
            ObjectMapper mapper) {
        this(rollout, authority, repository, populationRepository, projector,
                cursors, canonicalizer, artifacts, highRisk, mapper,
                Clock.systemUTC());
    }

    CertificateService(
            Hrm009RolloutGate rollout,
            CertificateAuthority authority,
            CertificateRepository repository,
            HcmPopulationRepository populationRepository,
            CertificateProjector projector,
            CertificateCursorCodec cursors,
            CertificateCanonicalizer canonicalizer,
            CertificateArtifactPort artifacts,
            HcmHighRiskCommandGuard highRisk,
            ObjectMapper mapper,
            Clock clock) {
        this.rollout = Objects.requireNonNull(rollout);
        this.authority = Objects.requireNonNull(authority);
        this.repository = Objects.requireNonNull(repository);
        this.populationRepository = Objects.requireNonNull(populationRepository);
        this.projector = Objects.requireNonNull(projector);
        this.cursors = Objects.requireNonNull(cursors);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.highRisk = Objects.requireNonNull(highRisk);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public QueryResult query(
            CertificateQueryDefinition definition,
            CertificateDtos.QueryRequest request) {
        PeopleRequestContext.Actor actor = trustedActor();
        // Enrollment is the first runtime gate. A non-enrolled tenant performs
        // no owner authority, population, cursor, repository, or artifact work.
        rollout.requireEnrolled(actor.tenantId());
        CertificateProjector.PreparedQuery prepared =
                projector.prepare(definition, request, clock.instant());
        CertificateAuthority.QueryDecision decision =
                authority.authorizeQuery(definition);
        if (!actor.tenantId().equals(decision.actor().tenantId())) {
            throw CertificateException.denied("HRM-009 tenant authority changed.");
        }

        CertificateCursorCodec.Binding expected = binding(prepared, decision, null);
        String lastItemPublicId = null;
        if (prepared.cursor() != null) {
            if (!prepared.explicitAsOf()) {
                expected = new CertificateCursorCodec.Binding(
                        expected.tenantId(), expected.queryId(),
                        expected.targetPopulation(), expected.scopeDecisionRevision(),
                        expected.authorizationDecisionRevision(), null, expected.sort(),
                        expected.pageLimit(), null, expected.filterDigest(),
                        expected.locale(), expected.timeZone());
            }
            CertificateCursorCodec.Binding verified =
                    cursors.verify(prepared.cursor(), expected);
            lastItemPublicId = verified.lastItemPublicId();
            if (!prepared.explicitAsOf()) prepared = prepared.withAsOf(verified.asOf());
        }

        CertificateDtos.Projection projection;
        try {
            List<CertificateRepository.ProjectionRow> rows =
                    definition == CertificateQueryDefinition.SELF_REQUESTS
                            ? repository.selfRequests(
                            actor.tenantId(), actor.personPublicId(), prepared.asOf())
                            : repository.operationsCertificates(
                            actor.tenantId(), prepared.asOf(),
                            decision.population().scope());
            CertificateProjector.Page page = projector.project(
                    prepared, rows, decision, lastItemPublicId);
            String nextCursor = page.hasMore()
                    ? cursors.issue(binding(
                    prepared, decision, page.lastItemPublicId()))
                    : null;
            projection = projector.projection(prepared, decision, page, nextCursor);
        } catch (DataAccessException exception) {
            projection = projector.unavailable(prepared, decision);
        }
        String etag = '"' + canonicalizer.requestDigest(projection).substring(0, 32) + '"';
        return new QueryResult(projection, decision.authorizationRevision(), etag);
    }

    @Transactional
    public CommandResult requestCertificate(
            CertificateDtos.RequestCertificate request,
            String idempotencyKey,
            String ifMatch,
            UUID correlationId,
            String headerPurpose,
            HcmStepUpHeaders stepUp) {
        PeopleRequestContext.Actor actor = trustedActor();
        rollout.requireEnrolled(actor.tenantId());
        validateRequest(request, correlationId);
        String key = idempotencyKey(idempotencyKey);
        long expectedVersion = expectedVersion(ifMatch);
        CertificateAuthority.CommandDecision authorized = authority.authorizeCommand(
                request.personPublicId(), request.purposeCode(), headerPurpose);
        if (authorized.actor() == null
                || !actor.tenantId().equals(authorized.actor().tenantId())
                || !actor.userId().equals(authorized.actor().userId())
                || !actor.personPublicId().equals(
                authorized.actor().personPublicId())) {
            throw CertificateException.denied(
                    "HRM-009 command authority changed.");
        }
        requireStructuralStepUp(stepUp, key, expectedVersion,
                authorized.authorizationRevision());

        UUID requestPublicId = deterministicUuid(
                "certificate-request", actor.tenantId(), request.personPublicId(), key);
        UUID templatePublicId = deterministicUuid(
                "certificate-template-v1", actor.tenantId(),
                request.personPublicId(), request.certificateType());
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("schemaVersion", COMMAND_SCHEMA_VERSION);
        binding.put("method", "POST");
        binding.put("path", CertificateAuthority.ACTION_BROWSER_PATH);
        binding.put("targetPublicId", request.personPublicId());
        binding.put("idempotencyKey", key);
        binding.put("ifMatch", ifMatch);
        binding.put("correlationId", correlationId);
        binding.put("purposeCode", request.purposeCode());
        binding.put("body", request);
        String requestDigest = canonicalizer.requestDigest(binding);

        CertificateRepository.ReceiptRow existing = repository.receiptByKey(
                actor.tenantId(), COMMAND_TYPE, key).orElse(null);
        if (existing != null) {
            requireReplayMatch(
                    existing, requestDigest, actor, request, requestPublicId,
                    expectedVersion, correlationId, authorized.purpose());
            return replay(existing);
        }

        java.util.Optional<CertificateRepository.PersonRow> lockedSubject =
                repository.personForMutation(
                        actor.tenantId(), request.personPublicId());

        // The target row lock is the visibility barrier for identical
        // same-subject retries. A winner can commit while this transaction is
        // waiting for that lock, so replay before consulting any mutable
        // requester, population, snapshot, or version state and before
        // consuming the one-time HIGH challenge.
        CertificateRepository.ReceiptRow winnerAfterLock = repository.receiptByKey(
                actor.tenantId(), COMMAND_TYPE, key).orElse(null);
        if (winnerAfterLock != null) {
            requireReplayMatch(
                    winnerAfterLock, requestDigest, actor, request, requestPublicId,
                    expectedVersion, correlationId, authorized.purpose());
            return replay(winnerAfterLock);
        }

        CertificateRepository.PersonRow subject = lockedSubject.orElseThrow(
                () -> CertificateException.denied(
                        "The certificate subject is unavailable."));
        CertificateAuthority.CommandDecision decision =
                authority.requireMutationScope(authorized);
        CertificateRepository.PersonRow requester = decision.self()
                ? subject : repository.personForMutation(
                actor.tenantId(), actor.personPublicId())
                .orElseThrow(() -> CertificateException.denied(
                        "The certificate requester is unavailable."));
        if (subject.displayName() == null || subject.displayName().isBlank()
                || subject.displayName().length() > 512) {
            throw CertificateException.denied(
                    "The certificate subject snapshot is unavailable.");
        }
        if (!decision.self()) {
            if (subject.workerId() == null
                    || !populationRepository.lockWorkerInPopulation(
                    actor.tenantId(), decision.population().scope(), subject.workerId())) {
                throw CertificateException.denied(
                        "The certificate subject is outside the locked population.");
            }
        }
        if (subject.version() != expectedVersion) {
            throw CertificateException.conflict(
                    "OBJECT_VERSION_CONFLICT",
                    "The certificate subject version is stale.");
        }

        Map<String, Object> stepUpPayload = new LinkedHashMap<>();
        stepUpPayload.put("personPublicId", request.personPublicId());
        stepUpPayload.put("certificateType", request.certificateType());
        stepUpPayload.put("purposeCode", request.purposeCode());
        stepUpPayload.put("locale", request.locale());
        stepUpPayload.put("asOf", request.asOf());
        String populationDigest = canonicalizer.digestText(
                decision.self() ? "SELF:" + actor.personPublicId()
                        : decision.scopeRevision());
        long fieldRevision = canonicalizer.positiveRevision(
                decision.fieldGroups().stream().sorted().toList().toString());
        long authorizationRevision = canonicalizer.positiveRevision(
                decision.authorizationRevision());
        String contextDigest = canonicalizer.requestDigest(Map.of(
                "tenantId", actor.tenantId(),
                "actorPersonPublicId", actor.personPublicId(),
                "subjectPersonPublicId", request.personPublicId(),
                "profile", decision.profile(),
                "capability", decision.capability(),
                "purpose", decision.purpose(),
                "scopeRevision", decision.scopeRevision(),
                "authorizationRevision", decision.authorizationRevision()));
        Instant now = clock.instant();
        UUID receiptId = UUID.randomUUID();
        // Consumes the governed HIGH challenge only for a fresh command and
        // immediately before the first durable reservation. Durable replays
        // above never re-run or re-consume the original command.
        highRisk.requireExact(
                decision.capability(), CertificateAuthority.TARGET_TYPE,
                request.personPublicId().toString(), expectedVersion,
                CertificateAuthority.ACTION_BROWSER_PATH, stepUpPayload, stepUp);
        CertificateRepository.ReceiptRow claimed = repository.insertReceipt(
                actor.tenantId(), receiptId, COMMAND_TYPE, key, requestDigest,
                actor.personPublicId(), request.personPublicId(), populationDigest,
                fieldRevision, decision.purpose(), authorizationRevision,
                contextDigest, requestPublicId, expectedVersion, correlationId, now)
                .orElse(null);
        if (claimed == null) {
            CertificateRepository.ReceiptRow concurrent = repository.receiptByKey(
                    actor.tenantId(), COMMAND_TYPE, key)
                    .orElseThrow(() -> CertificateException.conflict(
                            "IDEMPOTENCY_RESERVATION_CONFLICT",
                            "Concurrent certificate reservation is unavailable."));
            requireReplayMatch(
                    concurrent, requestDigest, actor, request, requestPublicId,
                    expectedVersion, correlationId, decision.purpose());
            return replay(concurrent);
        }

        CertificateRepository.RequestRow created = repository.insertRequest(
                actor.tenantId(), requestPublicId, requester.personId(),
                subject.personId(), request.certificateType(), request.locale(),
                decision.purpose(), templatePublicId, 1, correlationId, actor.userId());
        CertificateRepository.RequestRow validating = repository.markValidating(
                actor.tenantId(), created, actor.userId(), now);
        String subjectSnapshotDigest = canonicalizer.requestDigest(Map.of(
                "personPublicId", subject.publicId(),
                "expectedVersion", subject.version(),
                "displayName", subject.displayName()));
        UUID sealPublicId = deterministicUuid(
                "certificate-seal-v1", actor.tenantId(),
                request.personPublicId(), request.certificateType());
        CertificateDtos.ArtifactResult artifact = artifacts.issue(
                new CertificateDtos.ArtifactRequest(
                        requestPublicId, request.personPublicId(),
                        subject.version(), subjectSnapshotDigest,
                        request.certificateType(), request.locale(), request.asOf(),
                        templatePublicId, 1, sealPublicId, 1));
        validateArtifact(artifact, request.asOf());
        UUID certificatePublicId = deterministicUuid(
                "issued-certificate", actor.tenantId(), requestPublicId,
                artifact.objectPublicId().toString());
        CertificateRepository.IssuedRow issued = repository.insertIssued(
                actor.tenantId(), certificatePublicId, validating, artifact,
                actor.userId());
        CertificateRepository.RequestRow completed = repository.markIssued(
                actor.tenantId(), validating, actor.userId(), issued.issuedAt());
        CertificateRepository.ReceiptRow receipt = repository.completeReceipt(
                actor.tenantId(), claimed.receiptId(), requestPublicId,
                certificatePublicId, completed.version(), issued.issuedAt());
        repository.appendAudit(
                actor.tenantId(), actor.userId(), requestPublicId,
                correlationId, decision.purpose(), issued.issuedAt());

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("requestPublicId", requestPublicId);
        event.put("certificatePublicId", certificatePublicId);
        event.put("subjectPersonPublicId", request.personPublicId());
        event.put("certificateType", request.certificateType());
        event.put("issuedAt", issued.issuedAt());
        event.put("expiresAt", issued.expiresAt());
        repository.appendIssuedEvent(
                actor.tenantId(), deterministicUuid(
                        "certificate-issued-event", actor.tenantId(),
                        requestPublicId, "CertificateIssued.v1"),
                requestPublicId, event, correlationId, issued.issuedAt());
        return new CommandResult(toReceipt(receipt), completed.version());
    }

    private CertificateCursorCodec.Binding binding(
            CertificateProjector.PreparedQuery query,
            CertificateAuthority.QueryDecision authority,
            String lastItemPublicId) {
        return new CertificateCursorCodec.Binding(
                authority.actor().tenantId(), query.definition().queryId(),
                authority.populationKind(), authority.scopeRevision(),
                authority.authorizationRevision(), query.asOf(), query.sort(),
                query.limit(), lastItemPublicId, query.filterDigest(),
                query.locale(), query.timeZone());
    }

    private void validateRequest(
            CertificateDtos.RequestCertificate request,
            UUID correlationId) {
        if (request == null || request.personPublicId() == null
                || request.certificateType() == null
                || request.certificateType().isBlank()
                || request.certificateType().length() > 64
                || request.purposeCode() == null
                || !Set.of(
                        CertificateAuthority.SELF_PURPOSE,
                        CertificateAuthority.DEFERRED_TEAM_PURPOSE,
                        CertificateAuthority.OPERATIONS_PURPOSE)
                        .contains(request.purposeCode())
                || request.locale() == null
                || !request.locale().matches("[a-z]{2}(?:-[A-Z]{2})?")
                || request.asOf() == null || correlationId == null
                || request.asOf().isAfter(clock.instant().plusSeconds(300))) {
            throw CertificateException.invalid(
                    "The certificate request body is invalid.");
        }
    }

    private String idempotencyKey(String value) {
        if (value == null || value.length() < 16 || value.length() > 128) {
            throw CertificateException.invalid(
                    "An Idempotency-Key between 16 and 128 characters is required.");
        }
        return value;
    }

    private void requireStructuralStepUp(
            HcmStepUpHeaders stepUp,
            String key,
            long expectedVersion,
            String authorizationRevision) {
        if (stepUp == null || stepUp.challenge() == null
                || stepUp.challenge().isBlank()
                || !key.equals(stepUp.idempotencyKey())
                || !authorizationRevision.equals(stepUp.decisionRevision())
                || !Objects.equals(expectedVersion, stepUp.expectedObjectVersion())) {
            throw CertificateException.conflict(
                    "STEP_UP_CHALLENGE_MISMATCH",
                    "Step-up evidence is not bound to this certificate request.");
        }
    }

    private long expectedVersion(String value) {
        if (value == null || !value.matches("(?:W/)?\"(?:v:)?[0-9]+\"")) {
            throw CertificateException.invalid(
                    "If-Match must be a quoted governed version token.");
        }
        try {
            return Long.parseLong(value.replaceFirst("^(?:W/)?\"(?:v:)?", "")
                    .replace("\"", ""));
        } catch (RuntimeException exception) {
            throw CertificateException.invalid("If-Match version is invalid.");
        }
    }

    private void validateArtifact(
            CertificateDtos.ArtifactResult artifact,
            Instant asOf) {
        if (artifact == null || artifact.objectPublicId() == null
                || artifact.objectVersion() < 1
                || artifact.contentSha256() == null
                || !artifact.contentSha256().matches("[0-9a-f]{64}")
                || artifact.verificationTokenHash() == null
                || !artifact.verificationTokenHash().matches("[0-9a-f]{64}")
                || artifact.issuedAt() == null
                || artifact.issuedAt().isBefore(asOf)
                || artifact.issuedAt().isAfter(clock.instant().plusSeconds(300))
                || artifact.expiresAt() != null
                && !artifact.expiresAt().isAfter(artifact.issuedAt())) {
            throw CertificateException.unavailable(
                    "Certificate artifact metadata is invalid.");
        }
    }

    private void requireReplayMatch(
            CertificateRepository.ReceiptRow receipt,
            String requestDigest,
            PeopleRequestContext.Actor actor,
            CertificateDtos.RequestCertificate request,
            UUID requestPublicId,
            long expectedVersion,
            UUID correlationId,
            String purpose) {
        boolean matches = COMMAND_TYPE.equals(receipt.commandType())
                && requestDigest.equals(receipt.requestDigest())
                && actor.personPublicId().equals(receipt.actorPublicId())
                && ACTION.equals(receipt.action())
                && request.personPublicId().equals(receipt.subjectPublicId())
                && purpose.equals(receipt.purpose())
                && requestPublicId.equals(receipt.targetPublicId())
                && Objects.equals(expectedVersion, receipt.expectedVersion())
                && correlationId.equals(receipt.correlationId());
        if (!matches) {
            throw CertificateException.conflict(
                    "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key is bound to another certificate request.");
        }
    }

    private CommandResult replay(CertificateRepository.ReceiptRow receipt) {
        if (!"SUCCEEDED".equals(receipt.lifecycleState())) {
            long version = receipt.expectedVersion() == null
                    ? 0 : Math.max(0, receipt.expectedVersion());
            return new CommandResult(toReceipt(receipt), version);
        }
        try {
            JsonNode result = mapper.readTree(receipt.resultReference());
            JsonNode version = result.get("requestVersion");
            if (version == null || !version.canConvertToLong()
                    || version.longValue() < 1) throw new IllegalArgumentException();
            return new CommandResult(toReceipt(receipt), version.longValue());
        } catch (Exception exception) {
            throw CertificateException.conflict(
                    "IDEMPOTENCY_RESULT_UNAVAILABLE",
                    "The completed certificate receipt is corrupt.");
        }
    }

    private CommandReceiptV1 toReceipt(CertificateRepository.ReceiptRow row) {
        return new CommandReceiptV1(
                row.receiptId(), row.commandType(), row.action(),
                row.subjectPublicId(), row.populationDigest(), row.fieldRevision(),
                row.purpose(), row.authorizationRevision(),
                receiptStatus(row.lifecycleState()),
                row.correlationId(), row.requestDigest(), row.createdAt());
    }

    private String receiptStatus(String lifecycle) {
        return switch (lifecycle) {
            case "RECEIVED" -> "ACCEPTED";
            case "VALIDATING", "RUNNING" -> "RUNNING";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "REJECTED", "CANCELLED" -> "REJECTED";
            case "FAILED_RETRYABLE", "DEAD" -> "FAILED";
            default -> "RESULT_UNKNOWN";
        };
    }

    private PeopleRequestContext.Actor trustedActor() {
        try {
            PeopleRequestContext.Actor actor = PeopleRequestContext.require();
            if (actor.userId() == null || actor.userId() < 1
                    || actor.tenantId() == null || actor.tenantId() < 1
                    || actor.personPublicId() == null) {
                throw new IllegalStateException();
            }
            return actor;
        } catch (RuntimeException exception) {
            throw CertificateException.denied(
                    "A trusted tenant-bound People identity is required.");
        }
    }

    private UUID deterministicUuid(
            String namespace,
            long tenantId,
            UUID subject,
            String discriminator) {
        String value = namespace + '\n' + tenantId + '\n'
                + subject + '\n' + discriminator;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    record QueryResult(
            CertificateDtos.Projection projection,
            String authorizationRevision,
            String etag) {
    }

    record CommandResult(CommandReceiptV1 receipt, long entityVersion) {
    }
}
