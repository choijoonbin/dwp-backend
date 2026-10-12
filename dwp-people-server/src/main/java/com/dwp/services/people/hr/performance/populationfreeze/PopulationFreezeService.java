package com.dwp.services.people.hr.performance.populationfreeze;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class PopulationFreezeService {

    static final String ORIGINATING_ACTION = "performance.population.freeze";
    static final String APPLICATION_ENTITLEMENT = "APP.HRIS";
    static final String RESOURCE = "DATA.HR_TALENT";
    static final String AUTHORITY_ACTION = "APPROVE";
    static final String AUTHORITY_PURPOSE = "PERFORMANCE_CONFIGURATION";
    private static final String COMMAND_TYPE = "FREEZE_POPULATION";
    private static final String FREEZE_TARGET_TYPE = "PERFORMANCE_CYCLE";
    private static final String FREEZE_PUBLIC_PATH_PREFIX =
            "/api/people/v1/hris/performance/cycles/";

    private final PopulationFreezeRepository repository;
    private final PopulationFreezeCanonicalizer canonicalizer;
    private final AuditOutboxRecorder audit;
    private final HcmHighRiskCommandGuard highRisk;
    private final Clock clock;
    private final Supplier<PopulationFreezeAuthorityPort> authoritySupplier;

    @org.springframework.beans.factory.annotation.Autowired
    public PopulationFreezeService(
            PopulationFreezeRepository repository,
            ObjectMapper objectMapper,
            AuditOutboxRecorder audit,
            HcmHighRiskCommandGuard highRisk,
            ObjectProvider<PopulationFreezeAuthorityPort> authorityProvider) {
        this(repository, new PopulationFreezeCanonicalizer(objectMapper), audit,
                Clock.systemUTC(), authorityProvider::getIfAvailable, highRisk);
    }

    PopulationFreezeService(
            PopulationFreezeRepository repository,
            PopulationFreezeCanonicalizer canonicalizer,
            AuditOutboxRecorder audit,
            Clock clock,
            Supplier<PopulationFreezeAuthorityPort> authoritySupplier,
            HcmHighRiskCommandGuard highRisk) {
        this.repository = Objects.requireNonNull(repository);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
        this.authoritySupplier = Objects.requireNonNull(authoritySupplier);
        this.highRisk = Objects.requireNonNull(highRisk);
    }

    @Transactional
    public PopulationFreezeDtos.FreezeCommandResult freeze(
            UUID cycleId,
            long expectedCycleRevision,
            PopulationFreezeDtos.FreezePopulationRequest request,
            String idempotencyKey,
            String correlationId,
            HcmStepUpHeaders stepUpHeaders) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePermission(actor);
        UUID subjectId = requireSubject(actor);
        String key = canonicalIdempotencyKey(idempotencyKey);
        PopulationFreezeDtos.FreezePopulationRequest normalized = normalize(request);
        PopulationFreezeAuthorityPort.AuthorityEvidence authority = requireAuthority(
                actor, subjectId, cycleId);
        repository.bindTenant(actor.tenantId());
        String requestHash = canonicalizer.commandHash(
                normalized, expectedCycleRevision, actor.tenantId(), actor.userId(),
                subjectId, cycleId);

        Optional<PopulationFreezeDtos.FreezeCommandResult> replay = replay(
                actor.tenantId(), subjectId, key, cycleId, expectedCycleRevision,
                normalized.commandId(), requestHash);
        if (replay.isPresent()) return replay.get();
        Optional<PopulationFreezeRepository.ReceiptRecord> commandReceipt =
                repository.receiptById(actor.tenantId(), normalized.commandId());
        if (commandReceipt.isPresent()) {
            throw conflict(
                    "The command identifier is already bound to a different idempotency key.",
                    null);
        }

        PopulationFreezeRepository.FreezeCandidate candidate = repository.candidate(
                actor.tenantId(), cycleId, normalized.populationPreviewId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        validateCandidate(candidate, normalized, expectedCycleRevision, authority);

        if (!repository.reserveReceipt(
                actor.tenantId(), actor.userId(), subjectId, normalized.commandId(), key,
                cycleId, expectedCycleRevision, requestHash, authority)) {
            Optional<PopulationFreezeDtos.FreezeCommandResult> winner = replay(
                    actor.tenantId(), subjectId, key, cycleId,
                    expectedCycleRevision, normalized.commandId(), requestHash);
            if (winner.isPresent()) return winner.get();
            if (repository.receiptById(actor.tenantId(), normalized.commandId()).isPresent()) {
                throw conflict(
                        "The command identifier is already bound to a different idempotency key.",
                        null);
            }
            throw conflict("The population-freeze command winner is unavailable.", null);
        }
        highRisk.requireExact(
                HcmPopulationFreezeAuthorityAdapter.FREEZE_CAPABILITY_CONTRACT,
                FREEZE_TARGET_TYPE,
                cycleId.toString(),
                candidate.cycleAggregateVersion(),
                FREEZE_PUBLIC_PATH_PREFIX + cycleId + "/population-freezes",
                normalized,
                stepUpHeaders);

        PopulationFreezeDtos.PopulationFreeze freeze;
        try {
            freeze = repository.createFreeze(
                    actor.tenantId(), actor.userId(), normalized.commandId(),
                    UUID.randomUUID(), candidate);
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "This cycle version or preview already has an immutable population freeze.",
                    exception);
        }
        PopulationFreezeDtos.CommandReceipt receipt = repository.receiptById(
                actor.tenantId(), normalized.commandId()).orElseThrow().view();
        audit(actor, cycleId, correlationId, normalized.reason(), receipt.receiptId(), freeze);
        return new PopulationFreezeDtos.FreezeCommandResult(freeze, receipt);
    }

    private Optional<PopulationFreezeDtos.FreezeCommandResult> replay(
            long tenantId,
            UUID subjectId,
            String key,
            UUID cycleId,
            long expectedCycleRevision,
            UUID commandId,
            String requestHash) {
        Optional<PopulationFreezeRepository.ReceiptRecord> existing =
                repository.receiptByIdempotency(
                        tenantId, subjectId, ORIGINATING_ACTION, key);
        if (existing.isEmpty()) return Optional.empty();
        PopulationFreezeRepository.ReceiptRecord receipt = existing.get();
        if (!receipt.view().receiptId().equals(commandId)
                || !COMMAND_TYPE.equals(receipt.view().commandType())
                || !ORIGINATING_ACTION.equals(receipt.view().originatingAction())
                || !cycleId.equals(receipt.view().aggregateId())
                || receipt.view().expectedAggregateVersion() != expectedCycleRevision
                || !requestHash.equals(receipt.requestHash())) {
            throw conflict(
                    "The idempotency key is already bound to a different freeze command.", null);
        }
        PopulationFreezeDtos.PopulationFreeze freeze = receipt.view().resultRef() == null
                ? null
                : repository.freeze(tenantId, receipt.view().resultRef()).orElse(null);
        if ("SUCCEEDED".equals(receipt.view().state()) && freeze == null) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The immutable population-freeze receipt is incomplete.");
        }
        return Optional.of(new PopulationFreezeDtos.FreezeCommandResult(
                freeze, receipt.view()));
    }

    private void validateCandidate(
            PopulationFreezeRepository.FreezeCandidate candidate,
            PopulationFreezeDtos.FreezePopulationRequest request,
            long expectedCycleRevision,
            PopulationFreezeAuthorityPort.AuthorityEvidence authority) {
        if (!"VALIDATED".equals(candidate.lifecycleState())
                || candidate.activeVersionNo() != candidate.cycleVersionNo()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only the active validated cycle version can freeze a population.");
        }
        if (candidate.cycleAggregateVersion() != expectedCycleRevision
                || candidate.sourceCycleAggregateVersion() != expectedCycleRevision) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The cycle or preview revision changed; generate a fresh preview.");
        }
        if (!"READY".equals(candidate.previewState())
                || candidate.expiresAt() == null
                || !clock.instant().isBefore(candidate.expiresAt())) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The population preview is stale or unavailable.");
        }
        if (!request.populationPreviewId().equals(candidate.latestReadyPreviewId())) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "Only the latest current READY preview can be frozen.");
        }
        if (candidate.members().stream().anyMatch(member ->
                !authority.targetPopulation().permits(
                        member.organizationRef(), member.reviewerAssignmentRef()))) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The preview contains members outside the current target population.");
        }
        if (!request.populationRuleId().equals(candidate.populationRuleVersionId())
                || !request.workforceSnapshotId().equals(candidate.workforceSnapshotId())
                || request.workforceSnapshotRevision()
                != candidate.workforceSnapshotRevision()) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The requested rule or workforce snapshot does not match the preview.");
        }

        int participants = (int) candidate.members().stream()
                .filter(member -> !"EXCLUDED".equals(member.eligibilityCode()))
                .count();
        long reviewers = candidate.members().stream()
                .filter(member -> !"EXCLUDED".equals(member.eligibilityCode()))
                .map(PopulationFreezeRepository.PreviewMember::reviewerAssignmentRef)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        String recalculatedHash = canonicalizer.previewContentHash(candidate.members());
        if (participants != candidate.participantCount()
                || reviewers != candidate.reviewerAssignmentCount()
                || !recalculatedHash.equals(candidate.contentHash())
                || !request.previewContentHash().equals(candidate.contentHash())) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The preview evidence no longer matches its canonical members.");
        }
    }

    private PopulationFreezeAuthorityPort.AuthorityEvidence requireAuthority(
            PeopleRequestContext.Actor actor,
            UUID subjectId,
            UUID cycleId) {
        PopulationFreezeAuthorityPort port = authoritySupplier.get();
        if (port == null) throw authorityUnavailable(null);
        PopulationFreezeAuthorityPort.AuthorityEvidence evidence;
        try {
            evidence = port.authorize(new PopulationFreezeAuthorityPort.AuthorityRequest(
                    actor.tenantId(), actor.userId(), subjectId,
                    APPLICATION_ENTITLEMENT, RESOURCE, AUTHORITY_ACTION,
                    ORIGINATING_ACTION, AUTHORITY_PURPOSE, cycleId));
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw authorityUnavailable(exception);
        }
        if (evidence == null
                || evidence.tenantId() != actor.tenantId()
                || evidence.actorId() != actor.userId()
                || !subjectId.equals(evidence.subjectPrincipalPublicId())
                || !APPLICATION_ENTITLEMENT.equals(evidence.applicationEntitlement())
                || !RESOURCE.equals(evidence.resource())
                || !AUTHORITY_ACTION.equals(evidence.action())
                || !ORIGINATING_ACTION.equals(evidence.operation())
                || !AUTHORITY_PURPOSE.equals(evidence.purposeCode())
                || evidence.populationScopeDigest() == null
                || !evidence.populationScopeDigest().matches("[0-9a-f]{64}")
                || evidence.targetPopulation() == null
                || (!evidence.targetPopulation().tenantWide()
                && evidence.targetPopulation().managerAssignmentRef() == null
                && evidence.targetPopulation().organizationRefs().isEmpty())
                || evidence.fieldDecisions() == null
                || !evidence.fieldDecisions().keySet().containsAll(List.of(
                "REVIEW_ASSIGNMENT", "WORK_QUEUE", "DUE_STATUS"))
                || evidence.fieldPolicyRevision() <= 0
                || evidence.authorizationRevision() <= 0) {
            throw authorityUnavailable(null);
        }
        return evidence;
    }

    private PopulationFreezeDtos.FreezePopulationRequest normalize(
            PopulationFreezeDtos.FreezePopulationRequest request) {
        if (request == null
                || request.commandId() == null
                || request.populationPreviewId() == null
                || request.populationRuleId() == null
                || request.workforceSnapshotId() == null
                || request.workforceSnapshotRevision() <= 0
                || request.previewContentHash() == null
                || !request.previewContentHash().matches("[0-9a-f]{64}")
                || request.reason() == null
                || request.reason().trim().length() < 10
                || request.reason().trim().length() > 500) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A complete canonical population-freeze command is required.");
        }
        return new PopulationFreezeDtos.FreezePopulationRequest(
                request.commandId(), request.populationPreviewId(), request.populationRuleId(),
                request.workforceSnapshotId(), request.workforceSnapshotRevision(),
                request.previewContentHash(), request.reason().trim());
    }

    private String canonicalIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200
                || !value.equals(value.trim()) || containsControl(value)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A canonical Idempotency-Key of at most 200 characters is required.");
        }
        return value;
    }

    private boolean containsControl(String value) {
        return value.codePoints().anyMatch(point -> point <= 0x1f || point == 0x7f);
    }

    private void requirePermission(PeopleRequestContext.Actor actor) {
        if (actor.tenantId() == null || actor.userId() == null
                || !actor.hasPermission(RESOURCE, AUTHORITY_ACTION, "MANAGE")) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The required population-freeze permission is not granted.");
        }
    }

    private UUID requireSubject(PeopleRequestContext.Actor actor) {
        if (actor.personPublicId() == null) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "A governed subject principal is required for population freeze.");
        }
        return actor.personPublicId();
    }

    private BaseException authorityUnavailable(Throwable cause) {
        String message = "Canonical PEP-PER-009 authority is unavailable; access is denied.";
        return cause == null
                ? new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message)
                : new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message, cause);
    }

    private BaseException conflict(String message, Throwable cause) {
        return cause == null
                ? new BaseException(ErrorCode.RESOURCE_CONFLICT, message)
                : new BaseException(ErrorCode.RESOURCE_CONFLICT, message, cause);
    }

    private void audit(
            PeopleRequestContext.Actor actor,
            UUID cycleId,
            String correlationId,
            String reason,
            UUID receiptId,
            PopulationFreezeDtos.PopulationFreeze freeze) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action("performance.population.frozen")
                .outcome("SUCCESS")
                .severity("HIGH")
                .riskScore(75)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("hris-performance-population")
                .targetType("PERFORMANCE_POPULATION_FREEZE")
                .targetId(freeze.populationFreezeId().toString())
                .reason(reason)
                .correlationId(correlationId)
                .approvalId(receiptId.toString())
                .metadata(Map.of(
                        "cycleId", cycleId,
                        "cycleVersionId", freeze.cycleVersionId(),
                        "populationPreviewId", freeze.populationPreviewId(),
                        "workforceSnapshotRevision", freeze.workforceSnapshotRevision(),
                        "participantCount", freeze.participantCount(),
                        "reviewerAssignmentCount", freeze.reviewerAssignmentCount(),
                        "contentHash", freeze.contentHash()))
                .retentionClass("EXTENDED")
                .build());
    }
}
