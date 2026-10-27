package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.AttemptRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEffectPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimeServiceInternals.*;

/** Internal collaboration used by the non-Spring command helpers. */
interface IntegrationRuntimeServiceOperations {

    ExecutionRecord lockExecutionAuthorized(
            Authority authority,
            UUID executionId);

    ExecutionResponse executionResponseAuthorized(
            Authority authority,
            UUID executionId);

    CommandSeal runtimeSeal(
            Authority authority,
            String action,
            UUID executionId,
            String evidenceDigest);

    ProviderEffectPort providerPort();

    String recordOperationsException(
            long tenantId,
            UUID executionId,
            String category,
            String evidenceDigest,
            OffsetDateTime observedAt);

    ProviderEvidence normalizeReconciliationEvidence(
            ReconcileClaim claim, ProviderEvidence returned);

    ProviderEvidence normalizeDispatchEvidence(
            DispatchClaim claim, ProviderEvidence returned);

    ProviderRequest providerRequest(
            Authority authority,
            ExecutionRecord execution,
            List<AttemptRecord> attempts);

    ProviderEvidence unknown(
            ExecutionRecord execution,
            List<AttemptRecord> attempts,
            OffsetDateTime now);
}
