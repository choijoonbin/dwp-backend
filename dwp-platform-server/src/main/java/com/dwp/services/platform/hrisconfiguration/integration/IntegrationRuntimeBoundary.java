package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.AttemptRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.BoundaryResolutionPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderItemAttempt;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderRequest;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.CompensationPolicyConstraint;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolutionRequest;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.UnpinnedReferenceConstraint;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimeServiceInternals.*;

/** Internal integration runtime boundary operations shared by the owner facade. */
final class IntegrationRuntimeBoundary {
    private final ObjectProvider<BoundaryResolutionPort> boundaries;
    private final ObjectProvider<Sys008OpaqueReferenceOwnerPort> references;
    private final IntegrationCanonicalJson canonical;

    IntegrationRuntimeBoundary(
            ObjectProvider<BoundaryResolutionPort> boundaries,
            ObjectProvider<Sys008OpaqueReferenceOwnerPort> references,
            IntegrationCanonicalJson canonical) {
        this.boundaries = boundaries;
        this.references = references;
        this.canonical = canonical;
    }


    NonExportableProviderHandle resolveProviderReceipt(
            Authority authority,
            ExecutionRecord execution,
            ReconcileCommand command) {
        ResolutionRequest request = new ResolutionRequest(
                command.providerReceiptRef(), authority.tenantId(),
                execution.connectorId(), execution.executionId().toString(),
                authority.purposeCode(), ReferenceType.PROVIDER_RECEIPT_REF,
                "sys008.provider-receipt", command.providerReceiptDigest(),
                execution.authorizationRevision(),
                new Sys008OpaqueReferenceOwnerPort.ProviderReceiptConstraint(
                        execution.executionId(),
                        execution.connectorId().toString(),
                        execution.providerIdempotencyKey()), true);
        return referencePort().resolveAuthorized(request)
                .map(Sys008OpaqueReferenceOwnerPort.ResolvedReference::providerHandle)
                .orElseThrow(() -> IntegrationRuntimeServiceInternals.notFound(
                        "SYS008_PROVIDER_RECEIPT_NOT_AVAILABLE"));
    }

    ResolvedCompensation resolveCompensation(
            Authority authority,
            ExecutionRecord execution,
            CancelCommand command) {
        if (command.compensationPolicyRef() == null) {
            throw IntegrationRuntimeServiceInternals.conflict("SYS008_COMPENSATION_POLICY_REQUIRED");
        }
        String digest = canonical.digest(command.compensationPolicyRef(),
                execution.connectorId().toString(),
                Long.toString(execution.definitionVersion()),
                execution.operation().name());
        ResolutionRequest request = new ResolutionRequest(
                command.compensationPolicyRef(), authority.tenantId(),
                execution.connectorId(), execution.executionId().toString(),
                authority.purposeCode(), ReferenceType.COMPENSATION_POLICY_REF,
                "sys008.compensation-policy", digest,
                execution.authorizationRevision(), new CompensationPolicyConstraint(
                        execution.connectorId(), execution.definitionVersion(),
                        execution.operation().name(), true), true);
        NonExportableProviderHandle handle = referencePort()
                .resolveAuthorized(request)
                .map(Sys008OpaqueReferenceOwnerPort.ResolvedReference::providerHandle)
                .orElseThrow(() -> IntegrationRuntimeServiceInternals.notFound(
                        "SYS008_COMPENSATION_POLICY_NOT_AVAILABLE"));
        return new ResolvedCompensation(handle, digest);
    }

    ProviderRequest providerRequest(
            Authority authority,
            ExecutionRecord execution,
            List<AttemptRecord> attempts) {
        if (attempts.isEmpty()) {
            throw IntegrationRuntimeServiceInternals.conflict("SYS008_CURRENT_ATTEMPT_REQUIRED");
        }
        Set<UUID> legalEntities = execution.legalEntityPublicId() == null
                ? Set.of() : Set.of(execution.legalEntityPublicId());
        var population = Sys008TypedArtifactContract.issueCurrentPopulationContext(
                execution.connectorId(), legalEntities,
                execution.authorizationRevision());
        var frozenSource = Sys008TypedArtifactContract.freezeOwnerSource(
                execution.payloadRef(), execution.payloadDigest());
        var source = "LEGAL_ENTITY".equals(execution.populationKind())
                ? Sys008TypedArtifactContract.issueLegalEntitySourceEnvelope(
                        population, execution.legalEntityPublicId(), frozenSource)
                : Sys008TypedArtifactContract.issueTenantSourceEnvelope(
                        population, frozenSource);
        var context = Sys008TypedArtifactContract.issueOwnerExecutionContext(
                execution.executionId(), execution.connectorId(),
                execution.mappingId(), execution.mappingVersion(),
                execution.requestedAt().toInstant(),
                "actor:" + execution.requestedByActorId(),
                execution.ownerIdempotencyKey(), execution.correlationId());
        ConnectorExecutionRequestV1 artifact = IntegrationExecutionArtifacts.project(
                context, population, source, execution.operation(),
                execution.mappingDirection());

        var payloadReference = referenceHandle(
                authority, execution, execution.executionId().toString(),
                ReferenceType.PAYLOAD_REF, "sys008.execution-payload",
                execution.payloadRef(), execution.payloadDigest());
        var mappingReference = referenceHandle(
                authority, execution, execution.mappingId().toString(),
                ReferenceType.MAPPING_REF, "sys008.mapping",
                execution.mappingRef(), execution.mappingDigest());
        String secretDigest = canonical.digest(
                execution.connectorId().toString(), execution.endpoint().toString(),
                execution.egressPolicyKey(), execution.secretRef());
        var secretReference = referenceHandle(
                authority, execution, execution.connectorId().toString(),
                ReferenceType.SECRET_REF, "sys008.connector-secret",
                execution.secretRef(), secretDigest);
        BoundaryResolutionPort resolver = boundaryPort();
        var definitionBoundary = resolver.resolveDefinition(
                execution.tenantId(), execution.connectorId(),
                execution.definitionVersion(), execution.authorizationRevision(),
                authority.purposeCode());
        if (!Objects.equals(definitionBoundary.endpoint(), execution.endpoint())
                || !Objects.equals(definitionBoundary.secretRef(),
                    execution.secretRef())
                || !Objects.equals(definitionBoundary.egressPolicyKey(),
                    execution.egressPolicyKey())
                || definitionBoundary.endpointPolicyRevision()
                    != execution.endpointPolicyRevision()
                || definitionBoundary.secretRevision() != execution.secretRevision()) {
            throw IntegrationRuntimeServiceInternals.notFound("SYS008_FROZEN_DEFINITION_BOUNDARY_NOT_AVAILABLE");
        }
        if (!secretReference.equals(definitionBoundary.secretHandle())) {
            throw IntegrationRuntimeServiceInternals.notFound("SYS008_FROZEN_DEFINITION_BOUNDARY_NOT_AVAILABLE");
        }
        var mappingBoundary = resolver.resolveMapping(
                execution.tenantId(), execution.connectorId(),
                execution.mappingVersion(), execution.authorizationRevision(),
                authority.purposeCode());
        if (!Objects.equals(mappingBoundary.direction(),
                    execution.mappingDirection())
                || !Objects.equals(mappingBoundary.mappingRef(),
                    execution.mappingRef())
                || !IntegrationRuntimeServiceInternals.constant(mappingBoundary.mappingDigest(),
                    execution.mappingDigest())
                || !IntegrationRuntimeServiceInternals.constant(mappingBoundary.sourceSchemaDigest(),
                    execution.sourceSchemaDigest())
                || !IntegrationRuntimeServiceInternals.constant(mappingBoundary.targetSchemaDigest(),
                    execution.targetSchemaDigest())
                || !mappingReference.equals(mappingBoundary.mappingHandle())) {
            throw IntegrationRuntimeServiceInternals.notFound("SYS008_FROZEN_MAPPING_BOUNDARY_NOT_AVAILABLE");
        }
        var payloadBoundary = resolver.resolvePayload(
                execution.tenantId(), execution.connectorId(),
                execution.executionId(), execution.payloadRef(),
                execution.payloadDigest(), execution.authorizationRevision(),
                authority.purposeCode());
        if (!payloadReference.equals(payloadBoundary.payloadHandle())) {
            throw IntegrationRuntimeServiceInternals.notFound("SYS008_FROZEN_PAYLOAD_BOUNDARY_NOT_AVAILABLE");
        }
        var egressPin = Sys008EgressBoundary.requireAllowed(
                execution.endpoint(), definitionBoundary.allowlist(),
                definitionBoundary.resolvedDestinations());
        List<ProviderItemAttempt> selected = attempts.stream()
                .map(attempt -> new ProviderItemAttempt(
                        attempt.itemPublicId(), attempt.providerIdempotencyKey(),
                        attempt.requestDigest(), attempt.attemptFence()))
                .toList();
        long fence = attempts.stream().mapToLong(AttemptRecord::attemptFence)
                .max().orElseThrow();
        return new ProviderRequest(
                artifact, execution.providerIdempotencyKey(),
                execution.providerRequestDigest(), fence,
                execution.endpoint(), egressPin,
                definitionBoundary.secretHandle(),
                payloadBoundary.payloadHandle(), mappingBoundary.mappingHandle(),
                selected);
    }

    Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle referenceHandle(
            Authority authority,
            ExecutionRecord execution,
            String subjectRef,
            ReferenceType type,
            String namespace,
            String reference,
            String digest) {
        ResolutionRequest request = new ResolutionRequest(
                reference, authority.tenantId(), execution.connectorId(), subjectRef,
                authority.purposeCode(), type, namespace, digest,
                execution.authorizationRevision(),
                UnpinnedReferenceConstraint.INSTANCE, true);
        return referencePort().resolveAuthorized(request)
                .map(Sys008OpaqueReferenceOwnerPort.ResolvedReference::providerHandle)
                .orElseThrow(() -> IntegrationRuntimeServiceInternals.notFound(
                        "SYS008_OPAQUE_REFERENCE_NOT_AVAILABLE"));
    }

    String compensationKey(
            long tenantId,
            ExecutionRecord execution,
            String policyDigest) {
        return "sys008-comp-" + canonical.digest(
                Long.toString(tenantId), execution.executionId().toString(),
                execution.providerIdempotencyKey(), policyDigest);
    }

    String compensationRequestDigest(
            ExecutionRecord execution,
            String policyDigest,
            String reasonCode) {
        return canonical.digest(
                "SYS008_COMPENSATE", execution.executionId().toString(),
                execution.connectorId().toString(),
                Long.toString(execution.definitionVersion()),
                execution.operation().name(), execution.providerRequestDigest(),
                policyDigest, reasonCode);
    }

    BoundaryResolutionPort boundaryPort() {
        BoundaryResolutionPort port = boundaries.getIfAvailable();
        if (port == null) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "SYS008_BOUNDARY_RESOLUTION_UNAVAILABLE");
        }
        return port;
    }

    Sys008OpaqueReferenceOwnerPort referencePort() {
        Sys008OpaqueReferenceOwnerPort port = references.getIfAvailable();
        if (port == null) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "SYS008_REFERENCE_OWNER_UNAVAILABLE");
        }
        return port;
    }
}
