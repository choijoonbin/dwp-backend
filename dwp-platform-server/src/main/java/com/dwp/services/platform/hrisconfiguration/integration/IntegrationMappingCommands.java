package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.MappingRecord;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolvedReference;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationServiceInternals.*;

/** Internal integration mapping commands operations shared by the owner facade. */
final class IntegrationMappingCommands {

    private final IntegrationServiceOperations owner;
    private final IntegrationRepository repository;
    private final IntegrationCanonicalJson canonical;

    IntegrationMappingCommands(
            IntegrationServiceOperations owner,
            IntegrationRepository repository,
            IntegrationCanonicalJson canonical) {
        this.owner = owner;
        this.repository = repository;
        this.canonical = canonical;
    }


    public CommandReceipt createMapping(
            Authority authority,
            UUID connectorId,
            MappingCommand command) {
        owner.bind(authority);
        IntegrationServiceInternals.requireAuthor(authority);
        ReservedCommand reserved = owner.reserve(authority, "CREATE_MAPPING",
                CREATE_MAPPING, connectorId.toString(), command);
        if (!reserved.owner()) return reserved.receipt();
        DefinitionRecord definition = repository.lockDefinition(
                authority.tenantId(), connectorId);
        var resolved = owner.boundaryPort().resolveMapping(
                authority.tenantId(), connectorId, 0,
                authority.authorizationRevision(), authority.purposeCode());
        if (!IntegrationServiceInternals.constant(resolved.mappingRef(), command.mappingRef())
                || !IntegrationServiceInternals.constant(resolved.mappingDigest(), command.mappingDigest())) {
            throw IntegrationServiceInternals.notFound("SYS008_MAPPING_REFERENCE_NOT_AVAILABLE");
        }
        ResolvedReference mappingReference = owner.resolveReference(
                authority, connectorId, connectorId.toString(),
                ReferenceType.MAPPING_REF, "sys008.mapping",
                command.mappingRef(), command.mappingDigest());
        if (!mappingReference.providerHandle().equals(resolved.mappingHandle())) {
            throw IntegrationServiceInternals.notFound("SYS008_MAPPING_REFERENCE_NOT_AVAILABLE");
        }
        MappingRecord mapping = repository.insertMapping(
                definition, authority.actorId(), authority.populationScopeDigest(),
                command, resolved.direction(), resolved.sourceSchemaDigest(),
                resolved.targetSchemaDigest(), reserved.now());
        repository.appendControlAudit(reserved.seal(), "CONNECTOR_MAPPING",
                connectorId, mapping.objectRevision(),
                "MAPPING_CREATED", mapping, reserved.now());
        return repository.finishCommand(reserved.seal(), reserved.receipt().receiptId(),
                ReceiptStatus.SUCCEEDED, connectorId, mapping.objectRevision(),
                IntegrationServiceInternals.mappingRef(connectorId, mapping.version()), reserved.now());
    }

    public CommandReceipt validateMapping(
            Authority authority,
            UUID connectorId,
            long version,
            MappingValidationCommand command) {
        owner.bind(authority);
        IntegrationServiceInternals.requireAuthor(authority);
        ReservedCommand reserved = owner.reserve(authority, "VALIDATE_MAPPING",
                VALIDATE_MAPPING,
                IntegrationServiceInternals.mappingTarget(connectorId, version, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        MappingRecord current = repository.lockMapping(
                authority.tenantId(), connectorId, version);
        IntegrationServiceInternals.requireRevision(current.objectRevision(), authority);
        if (!IntegrationServiceInternals.constant(current.mappingDigest(), command.mappingDigest())) {
            throw IntegrationServiceInternals.conflict("SYS008_MAPPING_DIGEST_CHANGED");
        }
        var resolved = owner.resolveFrozenMappingBoundary(authority, current);
        if (!IntegrationServiceInternals.constant(current.sourceSchemaDigest(), command.sourceSchemaDigest())
                || !IntegrationServiceInternals.constant(current.targetSchemaDigest(), command.targetSchemaDigest())
                || !IntegrationServiceInternals.constant(resolved.sourceSchemaDigest(), command.sourceSchemaDigest())
                || !IntegrationServiceInternals.constant(resolved.targetSchemaDigest(), command.targetSchemaDigest())) {
            throw IntegrationServiceInternals.conflict("SYS008_MAPPING_SCHEMA_DIGEST_CHANGED");
        }
        return transitionMapping(authority, current, reserved,
                MappingState.VALIDATED, null,
                canonical.digest(command.mappingDigest(),
                        command.sourceSchemaDigest(), command.targetSchemaDigest()));
    }

    public CommandReceipt dryRunMapping(
            Authority authority,
            UUID connectorId,
            long version,
            MappingDryRunCommand command) {
        IntegrationServiceInternals.requireTrustedAuthority(authority);
        IntegrationServiceInternals.requireAuthor(authority);
        if (!"TENANT".equals(authority.populationKind())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_DRY_RUN_REQUIRES_TENANT_POPULATION");
        }
        MappingEffectClaim claim = owner.inTransaction(authority, () -> {
            ReservedCommand reserved = owner.reserve(authority, "DRY_RUN_MAPPING",
                    DRY_RUN_MAPPING,
                    IntegrationServiceInternals.mappingTarget(connectorId, version, authority), command);
            if (!owner.canRunEffect(reserved)) {
                return MappingEffectClaim.replay(reserved.receipt());
            }
            MappingRecord current = repository.lockMapping(
                    authority.tenantId(), connectorId, version);
            IntegrationServiceInternals.requireRevision(current.objectRevision(), authority);
            if (current.state() != MappingState.VALIDATED) {
                throw IntegrationServiceInternals.conflict("SYS008_DRY_RUN_REQUIRES_VALIDATED_MAPPING");
            }
            ReservedCommand claimed = owner.claimEffect(
                    reserved, connectorId, current.objectRevision(),
                    IntegrationServiceInternals.mappingRef(connectorId, version));
            if (!claimed.owner()) {
                return MappingEffectClaim.replay(claimed.receipt());
            }
            return new MappingEffectClaim(claimed, current, null, false);
        });
        if (claim.replay()) return claim.receipt();
        MappingRecord current = claim.current();
        IntegrationRuntimePorts.ResolvedMappingBoundary mappingBoundary;
        ResolvedReference sampleReference;
        try {
            mappingBoundary = owner.withoutTransaction(() ->
                    owner.resolveFrozenMappingBoundary(authority, current));
            sampleReference = owner.withoutTransaction(() -> owner.resolveReference(
                    authority, connectorId, current.mappingId().toString(),
                    ReferenceType.SAMPLE_REF, "sys008.mapping-sample",
                    command.sampleRef(), command.sampleDigest()));
        } catch (BaseException rejected) {
            return owner.finishOrRethrowEffectFailure(
                    authority, claim.reserved(), connectorId,
                    current.objectRevision(), IntegrationServiceInternals.mappingRef(connectorId, version),
                    rejected);
        }
        ConnectorExecutionRequestV1 dryRun = owner.dryRunArtifact(
                authority, current, command, claim.reserved().now());
        IntegrationRuntimePorts.DryRunEvidence result;
        try {
            result = owner.withoutTransaction(() -> owner.evidencePort().dryRun(
                    new IntegrationRuntimePorts.DryRunRequest(
                            dryRun, mappingBoundary.mappingHandle(),
                            sampleReference.providerHandle(), command.maxRows(),
                            command.asOf())));
        } catch (RuntimeException ambiguous) {
            return owner.finishUnchanged(authority, claim.reserved(), connectorId,
                    current.objectRevision(), IntegrationServiceInternals.mappingRef(connectorId, version),
                    ReceiptStatus.RESULT_UNKNOWN);
        }
        if (!result.valid() || result.resultRef() == null
                || result.resultDigest() == null) {
            return owner.finishUnchanged(authority, claim.reserved(), connectorId,
                    current.objectRevision(), IntegrationServiceInternals.mappingRef(connectorId, version),
                    ReceiptStatus.REJECTED);
        }
        return owner.finalizeEffect(authority, claim.reserved(), connectorId,
                current.objectRevision(), IntegrationServiceInternals.mappingRef(connectorId, version), () -> {
                    MappingRecord locked = repository.lockMapping(
                            authority.tenantId(), connectorId, version);
                    IntegrationServiceInternals.requireUnchanged(current, locked);
                    OffsetDateTime now = repository.databaseNow();
                    MappingRecord changed = repository.recordDryRun(
                            locked, authority.actorId(), claim.receipt().receiptId(),
                            result.resultRef(), result.resultDigest(), now);
                    repository.appendControlAudit(claim.reserved().seal(),
                            "CONNECTOR_MAPPING", connectorId,
                            changed.objectRevision(), "MAPPING_DRY_RUN_RECORDED",
                            changed, now);
                    return repository.finishCommand(claim.reserved().seal(),
                            claim.receipt().receiptId(), ReceiptStatus.SUCCEEDED,
                            connectorId, changed.objectRevision(),
                            IntegrationServiceInternals.mappingRef(connectorId, version), now);
                });
    }

    public CommandReceipt submitMapping(
            Authority authority,
            UUID connectorId,
            long version,
            ApprovalRequestCommand command) {
        owner.bind(authority);
        IntegrationServiceInternals.requireAuthor(authority);
        ReservedCommand reserved = owner.reserve(authority, "SUBMIT_MAPPING",
                SUBMIT_MAPPING,
                IntegrationServiceInternals.mappingTarget(connectorId, version, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        MappingRecord current = repository.lockMapping(
                authority.tenantId(), connectorId, version);
        IntegrationServiceInternals.requireRevision(current.objectRevision(), authority);
        if (command.subjectRevision() != current.subjectRevision()
                || !IntegrationServiceInternals.constant(command.payloadDigest(), current.mappingDigest())
                || current.dryRunResultDigest() == null) {
            throw IntegrationServiceInternals.conflict("SYS008_MAPPING_SUBMISSION_EVIDENCE_STALE");
        }
        return transitionMapping(authority, current, reserved,
                MappingState.PENDING_APPROVAL, null, command.payloadDigest());
    }

    public CommandReceipt approveMapping(
            Authority authority,
            UUID connectorId,
            long version,
            ApprovalDecisionCommand command) {
        owner.bind(authority);
        IntegrationServiceInternals.requireExecutor(authority);
        ReservedCommand reserved = owner.reserve(authority, "APPROVE_MAPPING",
                APPROVE_MAPPING,
                IntegrationServiceInternals.mappingTarget(connectorId, version, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        MappingRecord current = repository.lockMapping(
                authority.tenantId(), connectorId, version);
        IntegrationServiceInternals.requireRevision(current.objectRevision(), authority);
        IntegrationDomainPolicy.requireMakerChecker(
                current.authorActorId(), authority.actorId(),
                current.authorScopeDigest(), authority.populationScopeDigest());
        return transitionMapping(authority, current, reserved,
                MappingState.APPROVED, command.approvalReceiptId(),
                command.decisionDigest());
    }

    public CommandReceipt activateMapping(
            Authority authority,
            UUID connectorId,
            long version,
            ActivationCommand command) {
        owner.bind(authority);
        IntegrationServiceInternals.requireExecutor(authority);
        IntegrationServiceInternals.requireStepUp(authority);
        ReservedCommand reserved = owner.reserve(authority, "ACTIVATE_MAPPING",
                ACTIVATE_MAPPING,
                IntegrationServiceInternals.mappingTarget(connectorId, version, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        MappingRecord current = repository.lockMapping(
                authority.tenantId(), connectorId, version);
        IntegrationServiceInternals.requireRevision(current.objectRevision(), authority);
        IntegrationDomainPolicy.requireMakerChecker(
                current.authorActorId(), authority.actorId(),
                current.authorScopeDigest(), authority.populationScopeDigest());
        if (!command.approvalReceiptId().equals(current.approvalReceiptId())) {
            throw IntegrationServiceInternals.conflict("SYS008_MAPPING_ACTIVATION_EVIDENCE_STALE");
        }
        if (command.effectiveAt().isBefore(current.effectiveFrom())
                || current.effectiveTo() != null
                && !command.effectiveAt().isBefore(current.effectiveTo())
                || command.effectiveAt().isAfter(reserved.now().plusMinutes(5))) {
            throw IntegrationServiceInternals.conflict("SYS008_MAPPING_EFFECTIVE_WINDOW_INVALID");
        }
        return transitionMapping(authority, current, reserved,
                MappingState.ACTIVE, null,
                current.mappingDigest());
    }

    CommandReceipt transitionMapping(
            Authority authority,
            MappingRecord current,
            ReservedCommand reserved,
            MappingState target,
            UUID evidenceReceiptId,
            String evidenceDigest) {
        IntegrationDomainPolicy.requireTransition(current.state(), target);
        MappingRecord changed = repository.transitionMapping(
                current, target, authority.actorId(),
                evidenceReceiptId == null
                        ? reserved.receipt().receiptId() : evidenceReceiptId,
                evidenceDigest, authority.stepUpEvidenceDigest(), reserved.now());
        repository.appendControlAudit(reserved.seal(), "CONNECTOR_MAPPING",
                current.connectorId(), changed.objectRevision(),
                "MAPPING_" + target.name(), changed, reserved.now());
        return repository.finishCommand(reserved.seal(),
                reserved.receipt().receiptId(),
                ReceiptStatus.SUCCEEDED, current.connectorId(),
                changed.objectRevision(),
                IntegrationServiceInternals.mappingRef(current.connectorId(), current.version()), reserved.now());
    }
}
