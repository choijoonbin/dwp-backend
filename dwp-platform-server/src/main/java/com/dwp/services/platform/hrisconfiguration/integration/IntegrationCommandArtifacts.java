package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;
import com.dwp.platform.contracts.hris.generated.ConnectorMappingVersionV1;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.MappingRecord;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolutionRequest;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolvedReference;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.UnpinnedReferenceConstraint;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationServiceInternals.*;

/** Internal integration command artifacts operations shared by the owner facade. */
final class IntegrationCommandArtifacts {

    private final IntegrationServiceOperations owner;
    private final IntegrationCanonicalJson canonical;

    IntegrationCommandArtifacts(
            IntegrationServiceOperations owner,
            IntegrationCanonicalJson canonical) {
        this.owner = owner;
        this.canonical = canonical;
    }


    ConnectorExecutionRequestV1 dryRunArtifact(
            Authority authority,
            MappingRecord mapping,
            MappingDryRunCommand command,
            OffsetDateTime now) {
        var context = Sys008TypedArtifactContract.issueOwnerExecutionContext(
                UUID.randomUUID(), mapping.connectorId(), mapping.mappingId(),
                mapping.version(), now.toInstant(),
                "actor:" + authority.actorId(), authority.idempotencyKey(),
                authority.correlationId());
        var population = Sys008TypedArtifactContract.issueCurrentPopulationContext(
                mapping.connectorId(), java.util.Set.of(),
                authority.authorizationRevision());
        var source = Sys008TypedArtifactContract.issueTenantSourceEnvelope(
                population, Sys008TypedArtifactContract.freezeOwnerSource(
                        command.sampleRef(), command.sampleDigest()));
        ConnectorMappingVersionV1 ownerMapping = mappingArtifact(mapping);
        var pinned = "INBOUND".equals(mapping.direction())
                ? Sys008TypedArtifactContract.pinInboundOwnerMapping(ownerMapping)
                : Sys008TypedArtifactContract.pinOutboundOwnerMapping(ownerMapping);
        return Sys008TypedArtifactContract.projectDryRun(context, pinned, source);
    }

    ConnectorExecutionRequestV1 executionArtifact(
            Authority authority,
            UUID executionId,
            MappingRecord mapping,
            ExecutionCommand command,
            OffsetDateTime now) {
        var context = Sys008TypedArtifactContract.issueOwnerExecutionContext(
                executionId, mapping.connectorId(), mapping.mappingId(),
                mapping.version(), now.toInstant(),
                "actor:" + authority.actorId(), authority.idempotencyKey(),
                authority.correlationId());
        var population = Sys008TypedArtifactContract.issueCurrentPopulationContext(
                mapping.connectorId(), authority.legalEntityPublicId() == null
                        ? java.util.Set.of()
                        : java.util.Set.of(authority.legalEntityPublicId()),
                authority.authorizationRevision());
        var frozenSource = Sys008TypedArtifactContract.freezeOwnerSource(
                command.payloadRef(), command.payloadDigest());
        var source = "TENANT".equals(authority.populationKind())
                ? Sys008TypedArtifactContract.issueTenantSourceEnvelope(
                        population, frozenSource)
                : Sys008TypedArtifactContract.issueLegalEntitySourceEnvelope(
                        population, authority.legalEntityPublicId(), frozenSource);
        return IntegrationExecutionArtifacts.project(
                context, population, source, command.operation(),
                mapping.direction());
    }

    ResolvedReference resolveReference(
            Authority authority,
            UUID connectorId,
            String subjectRef,
            ReferenceType type,
            String namespace,
            String reference,
            String digest) {
        ResolutionRequest request = new ResolutionRequest(
                reference, authority.tenantId(), connectorId, subjectRef,
                authority.purposeCode(), type, namespace, digest,
                authority.authorizationRevision(),
                UnpinnedReferenceConstraint.INSTANCE, true);
        return owner.referencePort().resolveAuthorized(request)
                .orElseThrow(() -> IntegrationServiceInternals.notFound(
                        "SYS008_OPAQUE_REFERENCE_NOT_AVAILABLE"));
    }

    IntegrationRuntimePorts.ResolvedDefinitionBoundary
            resolveFrozenDefinitionBoundary(
                    Authority authority, DefinitionRecord current) {
        IntegrationRuntimePorts.ResolvedDefinitionBoundary resolved =
                owner.boundaryPort().resolveDefinition(
                        authority.tenantId(), current.connectorId(),
                        current.definitionVersion(),
                        authority.authorizationRevision(),
                        authority.purposeCode());
        URI canonicalStored = canonicalEndpoint(
                current.endpoint(), resolved.allowlist(),
                resolved.resolvedDestinations());
        URI canonicalResolved = canonicalEndpoint(
                resolved.endpoint(), resolved.allowlist(),
                resolved.resolvedDestinations());
        if (!canonicalStored.equals(current.endpoint())
                || !canonicalResolved.equals(current.endpoint())
                || !IntegrationServiceInternals.constant(current.egressPolicyKey(),
                        resolved.egressPolicyKey())
                || !IntegrationServiceInternals.constant(current.secretRef(), resolved.secretRef())
                || current.endpointPolicyRevision()
                        != resolved.endpointPolicyRevision()
                || current.secretRevision() != resolved.secretRevision()) {
            throw IntegrationServiceInternals.conflict("SYS008_DEFINITION_BOUNDARY_CHANGED");
        }
        String secretDigest = canonical.digest(
                current.connectorId().toString(), current.endpoint().toString(),
                current.egressPolicyKey(), current.secretRef());
        ResolvedReference secret = resolveReference(
                authority, current.connectorId(),
                current.connectorId().toString(), ReferenceType.SECRET_REF,
                "sys008.connector-secret", current.secretRef(), secretDigest);
        if (!secret.providerHandle().equals(resolved.secretHandle())) {
            throw IntegrationServiceInternals.conflict("SYS008_DEFINITION_BOUNDARY_CHANGED");
        }
        return resolved;
    }

    IntegrationRuntimePorts.ResolvedMappingBoundary
            resolveFrozenMappingBoundary(
                    Authority authority, MappingRecord current) {
        IntegrationRuntimePorts.ResolvedMappingBoundary resolved =
                owner.boundaryPort().resolveMapping(
                        authority.tenantId(), current.connectorId(),
                        current.version(), authority.authorizationRevision(),
                        authority.purposeCode());
        if (!IntegrationServiceInternals.constant(current.mappingRef(), resolved.mappingRef())
                || !IntegrationServiceInternals.constant(current.mappingDigest(), resolved.mappingDigest())
                || !IntegrationServiceInternals.constant(current.direction(), resolved.direction())
                || !IntegrationServiceInternals.constant(current.sourceSchemaDigest(),
                        resolved.sourceSchemaDigest())
                || !IntegrationServiceInternals.constant(current.targetSchemaDigest(),
                        resolved.targetSchemaDigest())) {
            throw IntegrationServiceInternals.conflict("SYS008_MAPPING_BOUNDARY_CHANGED");
        }
        ResolvedReference mapping = resolveReference(
                authority, current.connectorId(),
                current.mappingId().toString(), ReferenceType.MAPPING_REF,
                "sys008.mapping", current.mappingRef(),
                current.mappingDigest());
        if (!mapping.providerHandle().equals(resolved.mappingHandle())) {
            throw IntegrationServiceInternals.conflict("SYS008_MAPPING_BOUNDARY_CHANGED");
        }
        return resolved;
    }
}
