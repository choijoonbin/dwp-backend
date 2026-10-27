package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.MappingRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.BoundaryResolutionPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.DefinitionEvidencePort;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolvedReference;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationServiceInternals.*;

/** Internal collaboration used by the non-Spring command helpers. */
interface IntegrationServiceOperations {

    ReservedCommand reserve(
            Authority authority,
            String commandType,
            String action,
            String target,
            Object body);

    boolean canRunEffect(ReservedCommand reserved);

    ReservedCommand claimEffect(
            ReservedCommand reserved,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef);

    CommandReceipt finalizeEffect(
            Authority authority,
            ReservedCommand reserved,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            Supplier<CommandReceipt> finalization);

    CommandReceipt finishUnchanged(
            Authority authority,
            ReservedCommand reserved,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            ReceiptStatus status);

    CommandReceipt finishOrRethrowEffectFailure(
            Authority authority,
            ReservedCommand reserved,
            UUID aggregatePublicId,
            long aggregateRevision,
            String resultRef,
            BaseException failure);

    ReservedCommand atDatabaseNow(ReservedCommand reserved);

    <T> T inTransaction(Authority authority, Supplier<T> work);

    <T> T withoutTransaction(Supplier<T> effect);

    void bind(Authority authority);

    BoundaryResolutionPort boundaryPort();

    DefinitionEvidencePort evidencePort();

    Sys008OpaqueReferenceOwnerPort referencePort();

    ConnectorExecutionRequestV1 dryRunArtifact(
            Authority authority,
            MappingRecord mapping,
            MappingDryRunCommand command,
            OffsetDateTime now);

    IntegrationRuntimePorts.ResolvedMappingBoundary
            resolveFrozenMappingBoundary(
                    Authority authority, MappingRecord current);

    ResolvedReference resolveReference(
            Authority authority,
            UUID connectorId,
            String subjectRef,
            ReferenceType type,
            String namespace,
            String reference,
            String digest);

    IntegrationRuntimePorts.ResolvedDefinitionBoundary
            resolveFrozenDefinitionBoundary(
                    Authority authority, DefinitionRecord current);
}
