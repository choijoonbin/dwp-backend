package com.dwp.services.people.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HrisConnectorExecutionService {

    private final HrisIntegrationRepository repository;
    private final HrisImportService importer;
    private final WorkdayRestAdapter workday;
    private final HcmHighRiskCommandGuard highRisk;
    private final HrisHighRiskTargetRepository highRiskTargets;

    public HrisConnectorExecutionService(
            HrisIntegrationRepository repository,
            HrisImportService importer,
            WorkdayRestAdapter workday,
            HcmHighRiskCommandGuard highRisk,
            HrisHighRiskTargetRepository highRiskTargets) {
        this.repository = repository;
        this.importer = importer;
        this.workday = workday;
        this.highRisk = highRisk;
        this.highRiskTargets = highRiskTargets;
    }

    @Transactional
    public HrisDtos.ImportResult execute(
            UUID connectorId,
            String syncMode,
            UUID retryOfSyncRunId,
            String requestedCorrelationId) {
        return execute(connectorId, syncMode, retryOfSyncRunId,
                requestedCorrelationId, null);
    }

    @Transactional
    public HrisDtos.ImportResult execute(
            UUID connectorId,
            String syncMode,
            UUID retryOfSyncRunId,
            String requestedCorrelationId,
            HcmStepUpHeaders headers) {
        PeopleRequestContext.Actor actor = administrator();
        HrisDtos.ConnectorInstance connector = lockedConnector(actor.tenantId(), connectorId);
        highRisk.require(
                "hcm.integration.execute", "HCM_CONNECTOR", connectorId.toString(),
                connector.version(),
                "/api/people/v1/workforce/data-operations/hris/connectors/"
                        + connectorId + "/executions",
                Map.of("syncMode", syncMode), headers);
        return performExecute(actor, connector, syncMode, retryOfSyncRunId,
                requestedCorrelationId);
    }

    private HrisDtos.ImportResult performExecute(
            PeopleRequestContext.Actor actor,
            HrisDtos.ConnectorInstance connector,
            String syncMode,
            UUID retryOfSyncRunId,
            String requestedCorrelationId) {
        if (!"ACTIVE".equals(connector.lifecycleState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only an active HRIS connector can execute a synchronization.");
        }
        HrisIntegrationRepository.MappingRuntime mapping = repository
                .findActiveMapping(actor.tenantId(), connector.sourceSystemId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_STATE,
                        "Activate a reviewed HRIS mapping profile before synchronization."));
        if (!workday.supports(connector)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The configured HRIS connector type does not have an installed runtime adapter.");
        }
        String correlationId = correlationId(requestedCorrelationId);
        String cursor = "FULL".equals(syncMode)
                ? null
                : repository.currentCursor(actor.tenantId(), connector.connectorInstanceId());
        repository.markConnectorAttempt(
                actor.tenantId(), actor.userId(), connector.connectorInstanceId());
        try {
            WorkdayRestAdapter.FetchResult fetched = workday.fetch(
                    connector, mapping, cursor, syncMode);
            return importer.importConnectorBatch(
                    connector, mapping, fetched.batch(), syncMode, fetched.pageCount(),
                    retryOfSyncRunId, correlationId);
        } catch (RuntimeException exception) {
            Failure failure = failure(exception);
            repository.recordFailedRun(
                    actor.tenantId(), actor.userId(), connector, mapping.mappingProfileId(),
                    retryOfSyncRunId, correlationId, syncMode, cursor,
                    failure.code(), failure.message());
            throw new BaseException(
                    failure.blocked() ? ErrorCode.INVALID_STATE : ErrorCode.EXTERNAL_SERVICE_ERROR,
                    failure.message(), exception);
        }
    }

    @Transactional
    public HrisDtos.ImportResult retry(UUID syncRunId, String correlationId) {
        return retry(syncRunId, correlationId, null);
    }

    @Transactional
    public HrisDtos.ImportResult retry(
            UUID syncRunId, String correlationId, HcmStepUpHeaders headers) {
        PeopleRequestContext.Actor actor = administrator();
        HrisHighRiskTargetRepository.SyncRunTarget previous = highRiskTargets
                .lockRun(actor.tenantId(), syncRunId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!"FAILED".equals(previous.lifecycleState()) || previous.connectorId() == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only a failed connector synchronization can be retried.");
        }
        highRisk.require(
                "hcm.integration.execute", "HCM_SYNC_RUN", syncRunId.toString(),
                previous.version(),
                "/api/people/v1/workforce/data-operations/hris/sync-runs/"
                        + syncRunId + "/retry",
                Map.of(), headers);
        if (!highRiskTargets.claimRunRetry(
                actor.tenantId(), syncRunId, previous.version())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The synchronization run changed before retry.");
        }
        String replayMode = "FULL".equals(previous.syncMode()) ? "FULL" : "DELTA";
        HrisDtos.ConnectorInstance connector = lockedConnector(
                actor.tenantId(), previous.connectorId());
        return performExecute(actor, connector, replayMode, syncRunId, correlationId);
    }

    public HrisDtos.ConfigurationCheck probe(UUID connectorId, String correlationId) {
        PeopleRequestContext.Actor actor = administrator();
        HrisDtos.ConnectorInstance connector = connector(actor.tenantId(), connectorId);
        return probe(actor, connector, correlationId);
    }

    @Transactional
    public HrisDtos.ConfigurationCheck checkConnector(
            UUID connectorId, String correlationId, HcmStepUpHeaders headers) {
        PeopleRequestContext.Actor actor = administrator();
        HrisDtos.ConnectorInstance connector = lockedConnector(actor.tenantId(), connectorId);
        highRisk.require(
                "hcm.integration.execute", "HCM_CONNECTOR", connectorId.toString(),
                connector.version(),
                "/api/people/v1/workforce/data-operations/hris/connectors/"
                        + connectorId + "/configuration-check",
                Map.of(), headers);
        HrisDtos.ConfigurationCheck local = importer.checkConnectorConfiguration(
                connectorId, correlationId);
        return local.valid() ? probe(actor, connector, correlationId) : local;
    }

    private HrisDtos.ConfigurationCheck probe(
            PeopleRequestContext.Actor actor,
            HrisDtos.ConnectorInstance connector,
            String correlationId) {
        UUID connectorId = connector.connectorInstanceId();
        try {
            WorkdayRestAdapter.ProbeResult probe = workday.probe(connector);
            repository.recordConfigurationCheck(
                    actor.tenantId(), actor.userId(), connectorId, probe.healthState());
            repository.auditConnector(
                    actor.tenantId(), actor.userId(), connectorId,
                    "people.hris-connector.connectivity-verified", correlationId(correlationId),
                    "{\"valid\":true,\"externalConnectivityTested\":true}");
            return new HrisDtos.ConfigurationCheck(
                    connectorId, true, probe.healthState(), true, List.of(), Instant.now());
        } catch (RuntimeException exception) {
            Failure failure = failure(exception);
            String health = failure.blocked() ? "DEGRADED" : "FAILED";
            repository.recordConfigurationCheck(actor.tenantId(), actor.userId(), connectorId, health);
            repository.auditConnector(
                    actor.tenantId(), actor.userId(), connectorId,
                    "people.hris-connector.connectivity-blocked", correlationId(correlationId),
                    "{\"valid\":false,\"externalConnectivityTested\":false,\"reasonCode\":\""
                            + failure.code() + "\"}");
            return new HrisDtos.ConfigurationCheck(
                    connectorId, false, health, false, List.of(failure.message()), Instant.now());
        }
    }

    @Transactional
    public HrisDtos.MappingProfile createMapping(HrisDtos.CreateMappingProfileRequest request) {
        PeopleRequestContext.Actor actor = administrator();
        validateMapping(request);
        try {
            return repository.createMapping(actor.tenantId(), actor.userId(), request);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An HRIS mapping profile with this key already exists.", exception);
        }
    }

    @Transactional
    public HrisDtos.MappingProfile activateMapping(
            UUID mappingId,
            HrisDtos.ActivateMappingRequest request) {
        PeopleRequestContext.Actor actor = administrator();
        if (!repository.activateMapping(
                actor.tenantId(), actor.userId(), mappingId, request.version())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The mapping profile is not a current draft. Refresh and try again.");
        }
        return repository.findMapping(actor.tenantId(), mappingId).orElseThrow();
    }

    @Transactional
    public HrisDtos.ReconciliationRun reconcile(UUID connectorId, UUID syncRunId) {
        return reconcile(connectorId, syncRunId, null);
    }

    @Transactional
    public HrisDtos.ReconciliationRun reconcile(
            UUID connectorId, UUID syncRunId, HcmStepUpHeaders headers) {
        PeopleRequestContext.Actor actor = administrator();
        HrisDtos.ConnectorInstance connector = lockedConnector(actor.tenantId(), connectorId);
        highRisk.require(
                "hcm.integration.execute", "HCM_CONNECTOR", connectorId.toString(),
                connector.version(),
                "/api/people/v1/workforce/data-operations/hris/connectors/"
                        + connectorId + "/reconciliations",
                Map.of("syncRunId", syncRunId.toString()), headers);
        HrisDtos.SyncRun run = repository.findRun(actor.tenantId(), syncRunId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!connectorId.equals(run.connectorInstanceId())
                || !("SUCCEEDED".equals(run.lifecycleState()) || "PARTIAL".equals(run.lifecycleState()))) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Reconciliation requires a successful run from the selected connector.");
        }
        return repository.reconcile(actor.tenantId(), actor.userId(), connectorId, syncRunId);
    }

    @Transactional(readOnly = true)
    public List<HrisDtos.ReconciliationRun> reconciliations(int requestedSize) {
        return repository.listReconciliationRuns(
                PeopleRequestContext.require().tenantId(), bounded(requestedSize));
    }

    @Transactional(readOnly = true)
    public List<HrisDtos.ReconciliationIssue> issues(String state, int requestedSize) {
        String normalized = state == null || state.isBlank() ? null : state.trim().toUpperCase();
        if (normalized != null && !List.of("OPEN", "RESOLVED", "ACCEPTED").contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid reconciliation issue state.");
        }
        return repository.listReconciliationIssues(
                PeopleRequestContext.require().tenantId(), normalized, bounded(requestedSize));
    }

    @Transactional
    public void resolveIssue(UUID issueId, HrisDtos.ResolveIssueRequest request) {
        PeopleRequestContext.Actor actor = administrator();
        if (!repository.resolveReconciliationIssue(
                actor.tenantId(), actor.userId(), issueId, request)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The issue is no longer open.");
        }
    }

    private HrisDtos.ConnectorInstance connector(Long tenantId, UUID connectorId) {
        return repository.findConnector(tenantId, connectorId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private HrisDtos.ConnectorInstance lockedConnector(Long tenantId, UUID connectorId) {
        long version = highRiskTargets.lockConnector(tenantId, connectorId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        HrisDtos.ConnectorInstance connector = connector(tenantId, connectorId);
        if (connector.version() != version) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The locked HRIS connector revision could not be resolved.");
        }
        return connector;
    }

    private PeopleRequestContext.Actor administrator() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (HcmPepContext.current() == null
                && !actor.hasAnyRole("ADMIN", "HR_ADMIN")) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "HR administrator permission is required to operate HRIS integrations.");
        }
        return actor;
    }

    private void validateMapping(HrisDtos.CreateMappingProfileRequest request) {
        if (!request.mappingDefinition().isObject()
                || !request.mappingDefinition().path("mappings").isArray()
                || request.mappingDefinition().path("mappings").isEmpty()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A mapping profile must contain a non-empty mappings array.");
        }
        if (request.mappingDefinition().toString().length() > 131_072) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The mapping profile is too large.");
        }
        if (!"dwp.workforce-projection.v1".equals(request.targetSchemaVersion())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The target workforce projection contract is not supported.");
        }
    }

    private Failure failure(RuntimeException exception) {
        if (exception instanceof HrisConnectorBlockedException blocked) {
            return new Failure(blocked.reasonCode(), blocked.getMessage(), true);
        }
        if (exception instanceof RestClientException) {
            return new Failure(
                    "REMOTE_REQUEST_FAILED",
                    "The remote HRIS request failed. Review connector health and provider availability.",
                    false);
        }
        if (exception instanceof BaseException base) {
            return new Failure("PAYLOAD_REJECTED", base.getMessage(), false);
        }
        return new Failure(
                "PROJECTION_FAILED",
                "The HRIS payload could not be projected. Review the run error queue.",
                false);
    }

    private String correlationId(String requested) {
        if (requested == null || requested.isBlank()) return UUID.randomUUID().toString();
        String normalized = requested.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private int bounded(int requested) {
        return Math.min(200, Math.max(1, requested));
    }

    private record Failure(String code, String message, boolean blocked) {
    }
}
