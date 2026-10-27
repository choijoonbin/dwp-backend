package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolvedReference;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationServiceInternals.*;

/** Internal integration definition commands operations shared by the owner facade. */
final class IntegrationDefinitionCommands {

    private final IntegrationServiceOperations owner;
    private final IntegrationRepository repository;
    private final IntegrationCanonicalJson canonical;

    IntegrationDefinitionCommands(
            IntegrationServiceOperations owner,
            IntegrationRepository repository,
            IntegrationCanonicalJson canonical) {
        this.owner = owner;
        this.repository = repository;
        this.canonical = canonical;
    }


    public CommandReceipt createConnector(Authority authority, ConnectorCommand command) {
        owner.bind(authority);
        IntegrationServiceInternals.requireAuthor(authority);
        ReservedCommand reserved = owner.reserve(authority, "CREATE_CONNECTOR",
                CREATE_CONNECTOR, "CONNECTOR_COLLECTION", command);
        if (!reserved.owner()) return reserved.receipt();
        UUID connectorId = UUID.randomUUID();
        var boundary = owner.boundaryPort().resolveDefinition(
                authority.tenantId(), connectorId, 1,
                authority.authorizationRevision(), authority.purposeCode());
        if (!IntegrationServiceInternals.constant(boundary.egressPolicyKey(), command.egressPolicyKey())
                || !IntegrationServiceInternals.constant(boundary.secretRef(), command.secretRef())) {
            throw IntegrationServiceInternals.notFound("SYS008_EGRESS_POLICY_NOT_AVAILABLE");
        }
        URI canonicalEndpoint = IntegrationServiceInternals.canonicalEndpoint(
                command.endpoint(), boundary.allowlist(),
                boundary.resolvedDestinations());
        URI resolvedEndpoint = IntegrationServiceInternals.canonicalEndpoint(
                boundary.endpoint(), boundary.allowlist(),
                boundary.resolvedDestinations());
        if (!canonicalEndpoint.equals(resolvedEndpoint)) {
            throw IntegrationServiceInternals.notFound("SYS008_DEFINITION_BOUNDARY_CHANGED");
        }
        ConnectorCommand canonicalCommand = new ConnectorCommand(
                command.connectorKey(), command.type(), canonicalEndpoint,
                command.secretRef(), command.egressPolicyKey());
        String secretDigest = canonical.digest(
                connectorId.toString(), canonicalEndpoint.toString(),
                command.egressPolicyKey(), command.secretRef());
        ResolvedReference secret = owner.resolveReference(
                authority, connectorId, connectorId.toString(),
                ReferenceType.SECRET_REF, "sys008.connector-secret",
                command.secretRef(), secretDigest);
        if (!secret.providerHandle().equals(boundary.secretHandle())) {
            throw IntegrationServiceInternals.notFound("SYS008_SECRET_REFERENCE_NOT_AVAILABLE");
        }
        DefinitionRecord definition = repository.insertDefinition(
                authority.tenantId(), connectorId, authority.actorId(),
                authority.populationScopeDigest(), canonicalCommand,
                canonical.digest(canonical.digest(canonicalCommand),
                        Long.toString(boundary.endpointPolicyRevision()),
                        Long.toString(boundary.secretRevision())),
                boundary.endpointPolicyRevision(), boundary.secretRevision(),
                reserved.now());
        repository.appendControlAudit(reserved.seal(), "CONNECTOR_DEFINITION",
                definition.connectorId(), definition.aggregateRevision(),
                "DEFINITION_CREATED", definition, reserved.now());
        return repository.finishCommand(reserved.seal(), reserved.receipt().receiptId(),
                ReceiptStatus.SUCCEEDED, definition.connectorId(),
                definition.aggregateRevision(), IntegrationServiceInternals.connectorRef(definition.connectorId()),
                reserved.now());
    }

    public CommandReceipt validateDefinition(
            Authority authority,
            UUID connectorId,
            DefinitionValidationCommand command) {
        IntegrationServiceInternals.requireTrustedAuthority(authority);
        IntegrationServiceInternals.requireAuthor(authority);
        DefinitionEffectClaim claim = owner.inTransaction(authority, () -> {
            ReservedCommand reserved = owner.reserve(authority, "VALIDATE_DEFINITION",
                    VALIDATE_DEFINITION,
                    IntegrationServiceInternals.definitionTarget(connectorId, authority), command);
            if (!owner.canRunEffect(reserved)) {
                return DefinitionEffectClaim.replay(reserved.receipt());
            }
            DefinitionRecord current = repository.lockDefinition(
                    authority.tenantId(), connectorId);
            IntegrationServiceInternals.requireRevision(current.aggregateRevision(), authority);
            if (!IntegrationServiceInternals.constant(current.definitionDigest(), command.definitionDigest())) {
                throw IntegrationServiceInternals.conflict("SYS008_DEFINITION_DIGEST_CHANGED");
            }
            ReservedCommand claimed = owner.claimEffect(
                    reserved, connectorId, current.aggregateRevision(),
                    IntegrationServiceInternals.connectorRef(connectorId));
            if (!claimed.owner()) {
                return DefinitionEffectClaim.replay(claimed.receipt());
            }
            return new DefinitionEffectClaim(claimed, current, null, false);
        });
        if (claim.replay()) return claim.receipt();
        DefinitionRecord current = claim.current();
        IntegrationRuntimePorts.ResolvedDefinitionBoundary boundary;
        try {
            boundary = owner.withoutTransaction(() -> owner.resolveFrozenDefinitionBoundary(
                    authority, current));
            if (boundary.endpointPolicyRevision()
                    != command.endpointPolicyRevision()
                    || boundary.secretRevision() != command.secretRevision()) {
                throw IntegrationServiceInternals.conflict("SYS008_DEFINITION_POLICY_REVISION_CHANGED");
            }
        } catch (BaseException rejected) {
            return owner.finishOrRethrowEffectFailure(
                    authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), IntegrationServiceInternals.connectorRef(connectorId), rejected);
        }
        IntegrationRuntimePorts.ValidationEvidence validation;
        try {
            validation = owner.withoutTransaction(() -> owner.evidencePort().validate(
                    new IntegrationRuntimePorts.DefinitionValidationRequest(
                            authority.tenantId(), connectorId,
                            current.definitionVersion(), current.definitionDigest(),
                            current.endpoint(), boundary.allowlist(),
                            boundary.resolvedDestinations(), boundary.secretHandle(),
                            boundary.endpointPolicyRevision(),
                            boundary.secretRevision())));
        } catch (RuntimeException ambiguous) {
            return owner.finishUnchanged(authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), IntegrationServiceInternals.connectorRef(connectorId),
                    ReceiptStatus.RESULT_UNKNOWN);
        }
        if (!validation.valid() || validation.evidenceDigest() == null) {
            return owner.finishUnchanged(authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), IntegrationServiceInternals.connectorRef(connectorId),
                    ReceiptStatus.REJECTED);
        }
        return owner.finalizeEffect(authority, claim.reserved(), connectorId,
                current.aggregateRevision(), IntegrationServiceInternals.connectorRef(connectorId), () -> {
                    DefinitionRecord locked = repository.lockDefinition(
                            authority.tenantId(), connectorId);
                    IntegrationServiceInternals.requireUnchanged(current, locked);
                    return transitionDefinition(authority, locked,
                            owner.atDatabaseNow(claim.reserved()),
                            DefinitionState.VALIDATED,
                            null, validation.evidenceDigest());
                });
    }

    public CommandReceipt testConnector(
            Authority authority,
            UUID connectorId,
            ConnectorTestCommand command) {
        IntegrationServiceInternals.requireTrustedAuthority(authority);
        IntegrationServiceInternals.requireAuthor(authority);
        DefinitionEffectClaim claim = owner.inTransaction(authority, () -> {
            ReservedCommand reserved = owner.reserve(authority, "TEST_CONNECTOR",
                    TEST_CONNECTOR, IntegrationServiceInternals.definitionTarget(connectorId, authority), command);
            if (!owner.canRunEffect(reserved)) {
                return DefinitionEffectClaim.replay(reserved.receipt());
            }
            DefinitionRecord current = repository.lockDefinition(
                    authority.tenantId(), connectorId);
            if (current.state() != DefinitionState.VALIDATED) {
                throw IntegrationServiceInternals.conflict(
                        "SYS008_CONNECTION_TEST_REQUIRES_VALIDATED_DEFINITION");
            }
            ReservedCommand claimed = owner.claimEffect(
                    reserved, connectorId, current.aggregateRevision(),
                    IntegrationServiceInternals.connectorRef(connectorId));
            if (!claimed.owner()) {
                return DefinitionEffectClaim.replay(claimed.receipt());
            }
            return new DefinitionEffectClaim(claimed, current, null, false);
        });
        if (claim.replay()) return claim.receipt();
        DefinitionRecord current = claim.current();
        IntegrationRuntimePorts.DefinitionValidationRequest request;
        ResolvedReference sample;
        try {
            var boundary = owner.withoutTransaction(() ->
                    owner.resolveFrozenDefinitionBoundary(authority, current));
            request = new IntegrationRuntimePorts.DefinitionValidationRequest(
                    authority.tenantId(), connectorId, current.definitionVersion(),
                    current.definitionDigest(), current.endpoint(), boundary.allowlist(),
                    boundary.resolvedDestinations(), boundary.secretHandle(),
                    boundary.endpointPolicyRevision(), boundary.secretRevision());
            sample = owner.withoutTransaction(() -> owner.resolveReference(
                    authority, connectorId, connectorId.toString(),
                    ReferenceType.SAMPLE_REF,
                    "sys008.connection-test-sample", command.sampleRef(),
                    command.sampleDigest()));
        } catch (BaseException rejected) {
            return owner.finishOrRethrowEffectFailure(
                    authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), IntegrationServiceInternals.connectorRef(connectorId), rejected);
        }
        IntegrationRuntimePorts.ConnectionTestEvidence tested;
        try {
            tested = owner.withoutTransaction(() -> owner.evidencePort().test(
                    new IntegrationRuntimePorts.ConnectionTestRequest(
                            request, command.testMode(), sample.providerHandle(),
                            command.sampleDigest())));
        } catch (RuntimeException ambiguous) {
            return owner.finishUnchanged(authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), IntegrationServiceInternals.connectorRef(connectorId),
                    ReceiptStatus.RESULT_UNKNOWN);
        }
        if (!tested.succeeded() || tested.receiptRef() == null
                || tested.receiptDigest() == null) {
            return owner.finishUnchanged(authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), IntegrationServiceInternals.connectorRef(connectorId),
                    ReceiptStatus.REJECTED);
        }
        return owner.finalizeEffect(authority, claim.reserved(), connectorId,
                current.aggregateRevision(), IntegrationServiceInternals.connectorRef(connectorId), () -> {
                    DefinitionRecord locked = repository.lockDefinition(
                            authority.tenantId(), connectorId);
                    IntegrationServiceInternals.requireUnchanged(current, locked);
                    OffsetDateTime now = repository.databaseNow();
                    IntegrationDomainPolicy.requireTransition(
                            locked.state(), DefinitionState.TESTED);
                    DefinitionRecord changed = repository.recordConnectionTest(
                            locked, authority.actorId(), claim.receipt().receiptId(),
                            tested.receiptRef(), tested.receiptDigest(), now);
                    repository.appendControlAudit(claim.reserved().seal(),
                            "CONNECTOR_DEFINITION", connectorId,
                            changed.aggregateRevision(), "DEFINITION_TESTED", changed,
                            now);
                    return repository.finishCommand(claim.reserved().seal(),
                            claim.receipt().receiptId(), ReceiptStatus.SUCCEEDED,
                            connectorId, changed.aggregateRevision(),
                            IntegrationServiceInternals.connectorRef(connectorId), now);
                });
    }

    public CommandReceipt submitDefinition(
            Authority authority,
            UUID connectorId,
            ApprovalRequestCommand command) {
        owner.bind(authority);
        IntegrationServiceInternals.requireAuthor(authority);
        ReservedCommand reserved = owner.reserve(authority, "SUBMIT_DEFINITION",
                SUBMIT_DEFINITION, IntegrationServiceInternals.definitionTarget(connectorId, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        DefinitionRecord current = repository.lockDefinition(
                authority.tenantId(), connectorId);
        IntegrationServiceInternals.requireRevision(current.aggregateRevision(), authority);
        if (command.subjectRevision() != current.subjectRevision()
                || !IntegrationServiceInternals.constant(command.payloadDigest(), current.definitionDigest())
                || current.connectionTestReceiptDigest() == null) {
            throw IntegrationServiceInternals.conflict("SYS008_DEFINITION_SUBMISSION_EVIDENCE_STALE");
        }
        return transitionDefinition(authority, current, reserved,
                DefinitionState.PENDING_APPROVAL, null, command.payloadDigest());
    }

    public CommandReceipt approveDefinition(
            Authority authority,
            UUID connectorId,
            ApprovalDecisionCommand command) {
        owner.bind(authority);
        IntegrationServiceInternals.requireExecutor(authority);
        ReservedCommand reserved = owner.reserve(authority, "APPROVE_DEFINITION",
                APPROVE_DEFINITION, IntegrationServiceInternals.definitionTarget(connectorId, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        DefinitionRecord current = repository.lockDefinition(
                authority.tenantId(), connectorId);
        IntegrationServiceInternals.requireRevision(current.aggregateRevision(), authority);
        IntegrationDomainPolicy.requireMakerChecker(
                current.authorActorId(), authority.actorId(),
                current.authorScopeDigest(), authority.populationScopeDigest());
        return transitionDefinition(authority, current, reserved,
                DefinitionState.APPROVED, command.approvalReceiptId(),
                command.decisionDigest());
    }

    public CommandReceipt activateDefinition(
            Authority authority,
            UUID connectorId,
            ActivationCommand command) {
        owner.bind(authority);
        IntegrationServiceInternals.requireExecutor(authority);
        ReservedCommand reserved = owner.reserve(authority, "ACTIVATE_DEFINITION",
                ACTIVATE_DEFINITION, IntegrationServiceInternals.definitionTarget(connectorId, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        DefinitionRecord current = repository.lockDefinition(
                authority.tenantId(), connectorId);
        IntegrationServiceInternals.requireRevision(current.aggregateRevision(), authority);
        IntegrationServiceInternals.requireStepUp(authority);
        IntegrationDomainPolicy.requireMakerChecker(
                current.authorActorId(), authority.actorId(),
                current.authorScopeDigest(), authority.populationScopeDigest());
        if (!command.approvalReceiptId().equals(current.approvalReceiptId())
                || command.effectiveAt().isAfter(reserved.now().plusMinutes(5))) {
            throw IntegrationServiceInternals.conflict("SYS008_DEFINITION_ACTIVATION_EVIDENCE_STALE");
        }
        return transitionDefinition(authority, current, reserved,
                DefinitionState.ACTIVE, null,
                current.definitionDigest());
    }

    CommandReceipt transitionDefinition(
            Authority authority,
            DefinitionRecord current,
            ReservedCommand reserved,
            DefinitionState target,
            UUID evidenceReceiptId,
            String evidenceDigest) {
        IntegrationDomainPolicy.requireTransition(current.state(), target);
        DefinitionRecord changed = repository.transitionDefinition(
                current, target, authority.actorId(),
                evidenceReceiptId == null
                        ? reserved.receipt().receiptId() : evidenceReceiptId,
                evidenceDigest, authority.stepUpEvidenceDigest(), reserved.now());
        repository.appendControlAudit(reserved.seal(), "CONNECTOR_DEFINITION",
                current.connectorId(), changed.aggregateRevision(),
                "DEFINITION_" + target.name(), changed, reserved.now());
        return repository.finishCommand(reserved.seal(),
                reserved.receipt().receiptId(),
                ReceiptStatus.SUCCEEDED, current.connectorId(),
                changed.aggregateRevision(), IntegrationServiceInternals.connectorRef(current.connectorId()),
                reserved.now());
    }
}
