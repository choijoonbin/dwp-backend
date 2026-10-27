package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RetrySelection;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RunRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.ScheduleRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.VersionRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.SignedArtifactVerifierPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationServiceInternals.*;

/** Transactional owner for the closed SYS-006 control-plane operations. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.automation-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class AutomationService {

    private final AutomationServiceOperations operations;

    static final String CREATE_DEFINITION =
            AutomationServiceInternals.CREATE_DEFINITION;
    static final String QUEUE_RUN =
            AutomationServiceInternals.QUEUE_RUN;
    static final String RETRY_RUN =
            AutomationServiceInternals.RETRY_RUN;
    static final String CREATE_VERSION =
            AutomationServiceInternals.CREATE_VERSION;
    static final String VALIDATE_VERSION =
            AutomationServiceInternals.VALIDATE_VERSION;
    static final String SUBMIT_VERSION =
            AutomationServiceInternals.SUBMIT_VERSION;
    static final String APPROVE_VERSION =
            AutomationServiceInternals.APPROVE_VERSION;
    static final String ACTIVATE_VERSION =
            AutomationServiceInternals.ACTIVATE_VERSION;
    static final String CREATE_SCHEDULE =
            AutomationServiceInternals.CREATE_SCHEDULE;
    static final String SUBMIT_SCHEDULE =
            AutomationServiceInternals.SUBMIT_SCHEDULE;
    static final String APPROVE_SCHEDULE =
            AutomationServiceInternals.APPROVE_SCHEDULE;
    static final String ACTIVATE_SCHEDULE =
            AutomationServiceInternals.ACTIVATE_SCHEDULE;
    static final String SUSPEND_SCHEDULE =
            AutomationServiceInternals.SUSPEND_SCHEDULE;
    static final String CANCEL_RUN =
            AutomationServiceInternals.CANCEL_RUN;
    private final AutomationCatalogProjection catalogProjection;

    private final AutomationRepository repository;
    private final AutomationHandlerRegistryPort registry;
    private final SignedArtifactVerifierPort verifier;
    private final AutomationCanonicalJson canonical;
    private final AutomationCursorCodec cursors;
    private final Clock clock;

    AutomationService(
            AutomationRepository repository,
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier,
            AutomationCursorCodec cursors,
            ObjectMapper json) {
        this(repository, registry, verifier, cursors, json, Clock.systemUTC());
    }

    AutomationService(
            AutomationRepository repository,
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier,
            AutomationCursorCodec cursors,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.verifier = verifier;
        this.cursors = cursors;
        this.canonical = new AutomationCanonicalJson(json);
        this.clock = clock;
        this.operations = new OwnerOperations();
        this.catalogProjection = new AutomationCatalogProjection(operations, repository, canonical, cursors);
    }

    @Transactional(readOnly = true)
    CatalogResponse catalog(
            Authority authority,
            OffsetDateTime asOf,
            List<String> fields,
            String freshness,
            int limit,
            String cursor,
            String filter,
            List<String> resourceTypes) {
        return catalogProjection.catalog(
                authority, asOf, fields, freshness, limit, cursor, filter, resourceTypes);
    }

    @Transactional
    CommandReceipt createDefinition(
            Authority authority, CreateDefinitionCommand command) {
        operations.bind(authority);
        CommandSeal seal = seal(authority, "CREATE_AUTOMATION_DEFINITION",
                CREATE_DEFINITION, "AUTOMATION_DEFINITION_COLLECTION", command, null);
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = operations.now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        DefinitionRecord definition = repository.insertDefinition(
                authority.tenantId(), authority.subjectPrincipalPublicId(), command, now);
        return repository.completeCommand(
                seal, accepted.receiptId(), definition.publicId(),
                definition.objectRevision(), AutomationServiceInternals.definitionRef(definition.publicId()), now);
    }

    @Transactional
    CommandReceipt createVersion(
            Authority authority,
            UUID definitionPublicId,
            CreateVersionCommand command) {
        operations.bind(authority);
        DefinitionRecord definition = repository.lockDefinition(
                authority.tenantId(), definitionPublicId);
        requireRevision(definition.objectRevision(), authority.expectedObjectRevision());
        VerifiedArtifact artifact = verifier.verifyDraft(command);
        requireDraftArtifact(command, artifact, operations.now());
        CommandSeal seal = seal(authority, "CREATE_AUTOMATION_VERSION",
                CREATE_VERSION, target(definitionPublicId, null,
                        definition.objectRevision(), artifact.signedManifest().identity()),
                command, definition.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = operations.now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        VersionRecord version = repository.insertVersion(
                definition, authority.subjectPrincipalPublicId(), command, artifact, now);
        return repository.completeCommand(
                seal, accepted.receiptId(), definitionPublicId,
                version.objectRevision(), AutomationServiceInternals.versionRef(definitionPublicId,
                        version.versionNumber()), now);
    }

    @Transactional
    CommandReceipt transitionVersion(
            Authority authority,
            UUID definitionPublicId,
            long versionNumber,
            LifecycleCommand command,
            String operation) {
        operations.bind(authority);
        VersionRecord version = repository.lockVersion(
                authority.tenantId(), definitionPublicId, versionNumber);
        requireRevision(version.objectRevision(), authority.expectedObjectRevision());
        VersionState target = AutomationDomainPolicy.nextVersionState(
                version.state(), operation);
        if (target == VersionState.APPROVED) {
            AutomationDomainPolicy.requireMakerChecker(
                    version.authorActorRef(), authority.subjectPrincipalPublicId());
            requireStepUp(authority);
        }
        VerifiedArtifact current = null;
        if (target == VersionState.VALIDATED || target == VersionState.ACTIVE) {
            current = operations.revalidate(version, operations.now());
        }
        String action = switch (operation) {
            case "VALIDATE" -> VALIDATE_VERSION;
            case "SUBMIT" -> SUBMIT_VERSION;
            case "APPROVE" -> APPROVE_VERSION;
            case "ACTIVATE" -> ACTIVATE_VERSION;
            default -> throw invalid("AUTOMATION_VERSION_OPERATION_INVALID");
        };
        String type = switch (operation) {
            case "VALIDATE" -> "VALIDATE_AUTOMATION_VERSION";
            case "SUBMIT" -> "SUBMIT_AUTOMATION_VERSION";
            case "APPROVE" -> "APPROVE_AUTOMATION_VERSION";
            case "ACTIVATE" -> "ACTIVATE_AUTOMATION_VERSION";
            default -> throw invalid("AUTOMATION_VERSION_OPERATION_INVALID");
        };
        CommandSeal seal = seal(authority, type, action,
                target(definitionPublicId, versionNumber,
                        version.objectRevision(), version.approvedManifest().identity()),
                command, version.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = operations.now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        VersionRecord changed = repository.transitionVersion(
                version, target, authority.subjectPrincipalPublicId(),
                accepted.receiptId(), command.evidenceDigest(),
                authority.stepUpEvidenceDigest(), current, now);
        return repository.completeCommand(
                seal, accepted.receiptId(), definitionPublicId,
                changed.objectRevision(), AutomationServiceInternals.versionRef(
                        definitionPublicId, versionNumber), now);
    }

    @Transactional
    CommandReceipt createSchedule(
            Authority authority,
            UUID definitionPublicId,
            CreateScheduleCommand command) {
        operations.bind(authority);
        AutomationDomainPolicy.requireScheduleDefinition(
                command.expression(), command.timezone(),
                command.timezoneDatabaseRevision());
        DefinitionRecord definition = repository.lockDefinition(
                authority.tenantId(), definitionPublicId);
        requireRevision(definition.objectRevision(), authority.expectedObjectRevision());
        VersionRecord version = repository.lockVersion(
                authority.tenantId(), definitionPublicId,
                command.pinnedVersionNumber());
        CommandSeal seal = seal(authority, "CREATE_AUTOMATION_SCHEDULE",
                CREATE_SCHEDULE, target(definitionPublicId,
                        command.pinnedVersionNumber(), definition.objectRevision(),
                        version.approvedManifest().identity()), command,
                definition.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = operations.now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        ScheduleRecord schedule = repository.insertSchedule(
                definition, version, authority.subjectPrincipalPublicId(), command, now);
        return repository.completeCommand(
                seal, accepted.receiptId(), schedule.publicId(),
                schedule.objectRevision(), AutomationServiceInternals.scheduleRef(schedule.publicId()), now);
    }

    @Transactional
    CommandReceipt transitionSchedule(
            Authority authority,
            UUID schedulePublicId,
            LifecycleCommand command,
            String operation) {
        operations.bind(authority);
        ScheduleRecord schedule = repository.lockSchedule(
                authority.tenantId(), schedulePublicId);
        requireRevision(schedule.objectRevision(), authority.expectedObjectRevision());
        ScheduleState target = AutomationDomainPolicy.nextScheduleState(
                schedule.state(), operation);
        if (target == ScheduleState.APPROVED) {
            AutomationDomainPolicy.requireMakerChecker(
                    schedule.authorActorRef(), authority.subjectPrincipalPublicId());
            requireStepUp(authority);
        }
        if (target == ScheduleState.ACTIVE) {
            VersionRecord version = repository.lockVersion(
                    authority.tenantId(), schedule.definitionPublicId(),
                    schedule.pinnedVersionNumber());
            operations.revalidate(version, operations.now());
        }
        String action = switch (operation) {
            case "SUBMIT" -> SUBMIT_SCHEDULE;
            case "APPROVE" -> APPROVE_SCHEDULE;
            case "ACTIVATE" -> ACTIVATE_SCHEDULE;
            case "SUSPEND" -> SUSPEND_SCHEDULE;
            default -> throw invalid("AUTOMATION_SCHEDULE_OPERATION_INVALID");
        };
        String type = switch (operation) {
            case "SUBMIT" -> "SUBMIT_AUTOMATION_SCHEDULE";
            case "APPROVE" -> "APPROVE_AUTOMATION_SCHEDULE";
            case "ACTIVATE" -> "ACTIVATE_AUTOMATION_SCHEDULE";
            case "SUSPEND" -> "SUSPEND_AUTOMATION_SCHEDULE";
            default -> throw invalid("AUTOMATION_SCHEDULE_OPERATION_INVALID");
        };
        CommandSeal seal = seal(authority, type, action,
                target(schedulePublicId, null, schedule.objectRevision(), null),
                command, schedule.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = operations.now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        ScheduleRecord changed = repository.transitionSchedule(
                schedule, target, authority.subjectPrincipalPublicId(),
                accepted.receiptId(), command.evidenceDigest(),
                authority.stepUpEvidenceDigest(), now);
        return repository.completeCommand(
                seal, accepted.receiptId(), schedulePublicId,
                changed.objectRevision(), AutomationServiceInternals.scheduleRef(schedulePublicId), now);
    }

    @Transactional
    CommandReceipt queueRun(
            Authority authority,
            UUID definitionPublicId,
            QueueRunCommand command) {
        operations.bind(authority);
        DefinitionRecord definition = repository.lockDefinition(
                authority.tenantId(), definitionPublicId);
        requireRevision(definition.objectRevision(), authority.expectedObjectRevision());
        if (!"ACTIVE".equals(definition.lifecycleState())
                || definition.activeVersionNumber() == null) {
            throw conflict("AUTOMATION_DEFINITION_NOT_EXECUTABLE");
        }
        VersionRecord version = repository.lockVersion(
                authority.tenantId(), definitionPublicId,
                definition.activeVersionNumber());
        VerifiedArtifact artifact = operations.revalidate(version, operations.now());
        AutomationHandlerManifestV1 manifest = artifact.signedManifest().manifest();
        if (!manifest.allowedScopeTypes().contains(command.scopeType())) {
            throw forbidden("AUTOMATION_SCOPE_NOT_ALLOWED");
        }
        String concurrencyDigest = AutomationDomainPolicy.renderConcurrencyKey(
                version.manualConcurrencyKeyTemplate(), authority.tenantId(),
                definitionPublicId, command.scopeType(), command.scopeId());
        Map<String, Object> target = target(
                definitionPublicId, version.versionNumber(),
                definition.objectRevision(), artifact.signedManifest().identity());
        target.put("concurrencyKeyDigest", concurrencyDigest);
        CommandSeal seal = seal(authority, "QUEUE_AUTOMATION_RUN",
                QUEUE_RUN, target, command, definition.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = operations.now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        RunRecord run = repository.insertRun(
                definition, version, authority.subjectPrincipalPublicId(),
                accepted.receiptId(), command, concurrencyDigest, now);
        repository.appendRunEventAndAudit(seal, run, "QUEUED", now);
        return repository.completeCommand(
                seal, accepted.receiptId(), run.publicId(), run.objectRevision(),
                AutomationServiceInternals.runRef(run.publicId()), now);
    }

    @Transactional
    CommandReceipt retryRun(
            Authority authority, UUID runPublicId, RetryRunCommand command) {
        operations.bind(authority);
        RunRecord run = repository.lockRun(authority.tenantId(), runPublicId);
        requireRevision(run.objectRevision(), authority.expectedObjectRevision());
        VersionRecord version = repository.lockVersion(
                authority.tenantId(), run.definitionPublicId(), run.versionNumber());
        operations.revalidate(version, operations.now());
        RetrySelection selection = repository.retrySelection(run);
        if (!selection.selectedItemDigest().equals(command.evidenceDigest())) {
            throw conflict("AUTOMATION_RETRY_SELECTION_MISMATCH");
        }
        CommandSeal seal = seal(authority, "RETRY_AUTOMATION_RUN",
                RETRY_RUN, target(runPublicId, null, run.objectRevision(),
                        version.approvedManifest().identity()), command,
                run.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = operations.now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        RunRecord changed = repository.retryRun(
                run, authority.subjectPrincipalPublicId(), accepted.receiptId(),
                selection.mode(), selection.selectedItemCount(),
                selection.selectedItemDigest(), now);
        repository.appendRunEventAndAudit(seal, changed, "RETRY_QUEUED", now);
        return repository.completeCommand(
                seal, accepted.receiptId(), runPublicId, changed.objectRevision(),
                AutomationServiceInternals.runRef(runPublicId), now);
    }

    @Transactional
    CommandReceipt cancelRun(
            Authority authority, UUID runPublicId, CancelRunCommand command) {
        operations.bind(authority);
        RunRecord run = repository.lockRun(authority.tenantId(), runPublicId);
        requireRevision(run.objectRevision(), authority.expectedObjectRevision());
        CommandSeal seal = seal(authority, "CANCEL_AUTOMATION_RUN",
                CANCEL_RUN, target(runPublicId, null, run.objectRevision(), null),
                command, run.objectRevision());
        CommandReceipt replay = replay(seal);
        if (replay != null) return replay;
        OffsetDateTime now = operations.now();
        CommandReceipt accepted = repository.beginCommand(seal, now);
        RunRecord changed = repository.cancelRun(run, command.reason(), now);
        repository.appendRunEventAndAudit(seal, changed, "CANCEL_ACCEPTED", now);
        return repository.completeCommand(
                seal, accepted.receiptId(), runPublicId, changed.objectRevision(),
                AutomationServiceInternals.runRef(runPublicId), now);
    }

    @Transactional(readOnly = true)
    RunDetailResponse runDetail(
            Authority authority,
            UUID runPublicId,
            OffsetDateTime asOf,
            String freshness,
            int limit,
            String attemptCursor,
            String itemCursor,
            String checkpointCursor) {
        return catalogProjection.runDetail(
                authority, runPublicId, asOf, freshness, limit, attemptCursor, itemCursor,
                checkpointCursor);
    }

    private void requireDraftArtifact(
            CreateVersionCommand command,
            VerifiedArtifact artifact,
            OffsetDateTime now) {
        AutomationHandlerManifestV1 manifest = artifact == null
                ? null : artifact.signedManifest().manifest();
        if (artifact == null || !artifact.freshUntil().isAfter(now)
                || !artifact.signedManifest().identity().equals(
                new AutomationHandlerRegistryPort.HandlerIdentity(
                        command.handlerOwnerService(), command.handlerKey(),
                        command.handlerContractVersion()))
                || artifact.signedManifest().manifestRevision()
                != command.handlerManifestRevision()
                || !artifact.signedManifest().signatureKeyId()
                .equals(command.signatureKeyId())
                || !artifact.signedManifest().inputSchemaDigest()
                .equals(command.inputSchemaDigest())
                || !artifact.signedManifest().receiptSchemaDigest()
                .equals(command.receiptSchemaDigest())
                || !artifact.signedManifest().compensationPolicyDigest()
                .equals(command.compensationPolicyDigest())
                || !manifest.inputSchemaRef().equals(command.inputSchemaRef())
                || !manifest.receiptSchemaRef().equals(command.receiptSchemaRef())
                || !manifest.signatureDigest().equals(command.signatureDigest())
                || !manifest.allowedScopeTypes().equals(command.allowedScopeTypes())
                || !manifest.retryMode().equals(command.retryMode().name())
                || manifest.maxAttempts() != command.maxAttempts()
                || !artifact.canonicalDocumentDigest()
                .equals(command.handlerManifestDigest())) {
            throw conflict("AUTOMATION_SIGNED_ARTIFACT_MISMATCH");
        }
    }

    private CommandReceipt replay(CommandSeal seal) {
        return repository.findReplay(seal).orElse(null);
    }

    private CommandSeal seal(
            Authority authority,
            String commandType,
            String originatingAction,
            Object target,
            Object body,
            Long expectedRevision) {
        Map<String, Object> digestMaterial = new LinkedHashMap<>();
        digestMaterial.put("originatingAction", originatingAction);
        digestMaterial.put("routeContractKey", authority.routeContractKey());
        digestMaterial.put("target", target);
        digestMaterial.put("expectedObjectRevision", expectedRevision);
        digestMaterial.put("body", body);
        return new CommandSeal(
                authority.tenantId(), authority.subjectPrincipalPublicId(),
                commandType, originatingAction, authority.routeContractKey(),
                authority.contextKey(), authority.contextScopeKey(),
                authority.decisionRevision(), authority.populationScopeDigest(),
                authority.fieldPolicyRevision(), authority.authorizationRevision(),
                authority.purposeCode(), authority.idempotencyKey(),
                canonical.digest(digestMaterial), authority.correlationId());
    }

    /** Keeps collaboration behind the original service bean API. */
    private final class OwnerOperations implements AutomationServiceOperations {

        @Override
        public VerifiedArtifact revalidate(VersionRecord version, OffsetDateTime now) {
            VerifiedArtifact current = verifier.revalidate(
                    version.approvedManifest(), version.definitionDigest(),
                    version.executionPolicyDigest(), version.compatibilityBaselineDigest());
            AutomationRuntimePorts.requireFresh(
                    registry, current.signedManifest(), current.freshUntil(), now);
            if (!current.signedManifest().equals(version.approvedManifest())) {
                throw conflict("AUTOMATION_HANDLER_EVIDENCE_MISMATCH");
            }
            return current;
        }

        @Override
        public void bind(Authority authority) {
            if (authority == null || !authority.revalidateAt().isAfter(now())) {
                throw forbidden("AUTOMATION_CURRENT_AUTHORITY_REQUIRED");
            }
            repository.bindTenantPrincipal(
                    authority.tenantId(), authority.subjectPrincipalPublicId());
        }

        @Override
        public OffsetDateTime now() {
            return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        }
    }
}
