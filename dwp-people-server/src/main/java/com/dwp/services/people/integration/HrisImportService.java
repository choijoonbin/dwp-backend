package com.dwp.services.people.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class HrisImportService {

    private static final Set<String> WORKER_TYPES =
            Set.of("EMPLOYEE", "CONTINGENT", "NONWORKER", "PENDING");
    private static final Set<String> WORKER_STATUSES =
            Set.of("ACTIVE", "LEAVE", "TERMINATED", "PENDING");
    private static final Set<String> ASSIGNMENT_STATUSES =
            Set.of("ACTIVE", "SUSPENDED", "ENDED", "PENDING");

    private final HrisIntegrationRepository repository;
    private final WorkdayReferenceMapper mapper;
    private final ObjectMapper objectMapper;
    private final boolean syntheticImportEnabled;

    public HrisImportService(
            HrisIntegrationRepository repository,
            WorkdayReferenceMapper mapper,
            ObjectMapper objectMapper,
            @Value("${dwp.people.synthetic-import-enabled:false}") boolean syntheticImportEnabled) {
        this.repository = repository;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.syntheticImportEnabled = syntheticImportEnabled;
    }

    @Transactional
    public HrisDtos.ImportResult importSyntheticWorkdayFixture(
            String requestedIdempotencyKey,
            String requestedCorrelationId) {
        if (!syntheticImportEnabled) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic HRIS import is disabled outside an explicitly enabled development environment.");
        }
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requireDataOperationsAdministrator(actor);
        HrisModels.WorkforceBatch batch = mapper.mapSyntheticFixture();
        validate(batch);
        String idempotencyKey = normalizeIdempotencyKey(
                requestedIdempotencyKey == null || requestedIdempotencyKey.isBlank()
                        ? "synthetic:" + batch.watermark()
                        : requestedIdempotencyKey);
        String correlationId = requestedCorrelationId == null || requestedCorrelationId.isBlank()
                ? UUID.randomUUID().toString()
                : requestedCorrelationId.trim();
        String payloadHash = sha256(batch);

        long sourceSystemId = repository.upsertSource(
                actor.tenantId(), actor.userId(), batch.sourceKey(), batch.sourceType(),
                "Workday reference source");
        repository.upsertReferenceConnector(actor.tenantId(), actor.userId(), sourceSystemId);
        repository.upsertMappingProfile(
                actor.tenantId(), actor.userId(), sourceSystemId,
                batch.sourceSchemaVersion(), mapper.mappingDefinition());

        HrisIntegrationRepository.Receipt receipt = repository.acquireReceipt(
                actor.tenantId(), sourceSystemId, idempotencyKey, payloadHash);
        if (!receipt.payloadSha256().equals(payloadHash)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used with a different HRIS payload.");
        }
        if (!receipt.acquired()) {
            if (receipt.syncRunId() == null || "PROCESSING".equals(receipt.state())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The HRIS import with this idempotency key is still processing.");
            }
            HrisDtos.SyncRun run = repository.findRun(actor.tenantId(), receipt.syncRunId())
                    .orElseThrow(() -> new IllegalStateException("Completed HRIS run is missing."));
            return result(run, true, batch.synthetic());
        }

        UUID syncRunId = UUID.randomUUID();
        repository.startRun(
                actor.tenantId(), actor.userId(), sourceSystemId, receipt.receiptId(),
                syncRunId, correlationId, batch.watermark());

        long created = 0;
        long updated = 0;
        for (HrisModels.WorkerRecord worker : batch.workers()) {
            HrisIntegrationRepository.PersonUpsert person = repository.upsertPerson(
                    actor.tenantId(), actor.userId(), sourceSystemId, worker);
            if (person.inserted()) created++; else updated++;
            repository.upsertName(actor.tenantId(), actor.userId(), person.personId(), worker);
            long employerId = repository.upsertEmployer(
                    actor.tenantId(), actor.userId(), sourceSystemId, worker.employer());
            long workerId = repository.upsertWorker(
                    actor.tenantId(), actor.userId(), sourceSystemId, person.personId(), worker);
            long relationshipId = repository.upsertRelationship(
                    actor.tenantId(), actor.userId(), sourceSystemId,
                    workerId, employerId, worker);
            for (HrisModels.Assignment assignment : worker.assignments()) {
                long organizationId = repository.upsertOrganization(
                        actor.tenantId(), actor.userId(), sourceSystemId, assignment.organization(),
                        assignment.effectiveStartDate(), assignment.effectiveEndDate());
                long jobProfileId = repository.upsertJobProfile(
                        actor.tenantId(), actor.userId(), sourceSystemId, assignment.jobProfile());
                Long jobGradeId = repository.upsertJobGrade(
                        actor.tenantId(), actor.userId(), sourceSystemId, assignment.jobGrade());
                long locationId = repository.upsertLocation(
                        actor.tenantId(), actor.userId(), sourceSystemId, assignment.location());
                long positionId = repository.upsertPosition(
                        actor.tenantId(), actor.userId(), sourceSystemId, assignment.position(),
                        organizationId, jobProfileId, locationId);
                repository.upsertAssignment(
                        actor.tenantId(), actor.userId(), sourceSystemId, relationshipId,
                        organizationId, jobProfileId, jobGradeId, locationId, positionId, assignment);
                repository.upsertExternalMapping(
                        actor.tenantId(), actor.userId(), sourceSystemId,
                        "ASSIGNMENT",
                        assignment.assignmentKey() + ":" + assignment.effectiveStartDate(),
                        assignment.externalId(),
                        assignment.sourceVersion());
            }
            repository.replaceWorkEmail(
                    actor.tenantId(), actor.userId(), person.personId(),
                    worker.workEmail(), worker.originalHireDate());
            repository.upsertExternalMapping(
                    actor.tenantId(), actor.userId(), sourceSystemId,
                    "PERSON", person.publicId().toString(), worker.externalId(), worker.sourceVersion());
            repository.upsertExternalMapping(
                    actor.tenantId(), actor.userId(), sourceSystemId,
                    "WORKER", worker.workerNumber(), worker.externalId() + ":worker", worker.sourceVersion());
            repository.emitProjectionChanged(
                    actor.tenantId(), person.publicId(), syncRunId, correlationId,
                    worker, primaryJobTitle(worker));
        }

        repository.completeRun(
                actor.tenantId(), sourceSystemId, receipt.receiptId(), syncRunId,
                batch.watermark(), batch.workers().size(), created, updated, 0);
        repository.auditImport(
                actor.tenantId(), actor.userId(), sourceSystemId, syncRunId,
                correlationId, batch.workers().size(), created, updated);
        HrisDtos.SyncRun run = repository.findRun(actor.tenantId(), syncRunId)
                .orElseThrow(() -> new IllegalStateException("Completed HRIS run is missing."));
        return result(run, false, batch.synthetic());
    }

    @Transactional(readOnly = true)
    public List<HrisDtos.SourceSystem> sources() {
        return repository.listSources(PeopleRequestContext.require().tenantId());
    }

    @Transactional(readOnly = true)
    public List<HrisDtos.ConnectorInstance> connectors() {
        return repository.listConnectors(PeopleRequestContext.require().tenantId());
    }

    @Transactional
    public HrisDtos.ConnectorInstance createConnector(
            HrisDtos.CreateConnectorRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requireDataOperationsAdministrator(actor);
        List<String> issues = connectorIssues(
                request.connectorType(), request.endpointUri(), request.authMode(),
                request.credentialReference());
        if (!issues.isEmpty()) throw invalid(String.join(" ", issues));
        long sourceSystemId = repository.upsertSource(
                actor.tenantId(), actor.userId(), request.sourceKey().trim().toLowerCase(),
                request.sourceType(), request.sourceName().trim());
        HrisDtos.ConnectorInstance connector;
        try {
            connector = repository.createConnector(
                    actor.tenantId(), actor.userId(), sourceSystemId, request);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An HRIS connector with this key already exists.", exception);
        }
        repository.auditConnector(
                actor.tenantId(), actor.userId(), connector.connectorInstanceId(),
                "people.hris-connector.created", correlationId,
                json(Map.of(
                        "connectorKey", connector.connectorKey(),
                        "connectorType", connector.connectorType(),
                        "authMode", connector.authMode(),
                        "credentialReference", redactReference(connector.credentialReference()))));
        return connector;
    }

    @Transactional
    public HrisDtos.ConnectorInstance updateConnector(
            UUID connectorId,
            HrisDtos.UpdateConnectorRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requireDataOperationsAdministrator(actor);
        HrisDtos.ConnectorInstance current = repository
                .findConnector(actor.tenantId(), connectorId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        List<String> issues = connectorIssues(
                current.connectorType(), request.endpointUri(), current.authMode(),
                request.credentialReference());
        if ("ACTIVE".equals(request.lifecycleState()) && !issues.isEmpty()) {
            throw invalid(String.join(" ", issues));
        }
        if (!repository.updateConnector(
                actor.tenantId(), actor.userId(), connectorId, request)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The HRIS connector changed after it was loaded. Refresh and try again.");
        }
        HrisDtos.ConnectorInstance updated = repository
                .findConnector(actor.tenantId(), connectorId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        repository.auditConnector(
                actor.tenantId(), actor.userId(), connectorId,
                "people.hris-connector.updated", correlationId,
                json(Map.of(
                        "lifecycleState", updated.lifecycleState(),
                        "endpointConfigured", updated.endpointUri() != null,
                        "credentialReference", redactReference(updated.credentialReference()))));
        return updated;
    }

    @Transactional
    public HrisDtos.ConfigurationCheck checkConnectorConfiguration(
            UUID connectorId,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requireDataOperationsAdministrator(actor);
        HrisDtos.ConnectorInstance connector = repository
                .findConnector(actor.tenantId(), connectorId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        List<String> issues = connectorIssues(
                connector.connectorType(), connector.endpointUri(), connector.authMode(),
                connector.credentialReference());
        String health = issues.isEmpty() ? "UNKNOWN" : "FAILED";
        repository.recordConfigurationCheck(
                actor.tenantId(), actor.userId(), connectorId, health);
        repository.auditConnector(
                actor.tenantId(), actor.userId(), connectorId,
                "people.hris-connector.configuration-checked", correlationId,
                json(Map.of(
                        "valid", issues.isEmpty(),
                        "externalConnectivityTested", false,
                        "issueCount", issues.size())));
        return new HrisDtos.ConfigurationCheck(
                connectorId, issues.isEmpty(), health, false, List.copyOf(issues), Instant.now());
    }

    @Transactional(readOnly = true)
    public List<HrisDtos.MappingProfile> mappings() {
        return repository.listMappings(PeopleRequestContext.require().tenantId());
    }

    @Transactional(readOnly = true)
    public List<HrisDtos.SyncRun> runs(int requestedSize) {
        return repository.listRuns(
                PeopleRequestContext.require().tenantId(),
                Math.min(100, Math.max(1, requestedSize)));
    }

    private void requireDataOperationsAdministrator(PeopleRequestContext.Actor actor) {
        if (!actor.hasAnyRole("ADMIN", "HR_ADMIN")) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "HR administrator permission is required to change HRIS data operations.");
        }
    }

    private HrisDtos.ImportResult result(
            HrisDtos.SyncRun run,
            boolean replayed,
            boolean synthetic) {
        return new HrisDtos.ImportResult(
                run.syncRunId(), run.sourceKey(), run.lifecycleState(),
                run.readCount(), run.createdCount(), run.updatedCount(), run.rejectedCount(),
                replayed, synthetic, List.of("people.worker-projection.changed"));
    }

    private String primaryJobTitle(HrisModels.WorkerRecord worker) {
        return worker.assignments().stream()
                .filter(HrisModels.Assignment::primary)
                .findFirst()
                .or(() -> worker.assignments().stream().findFirst())
                .map(HrisModels.Assignment::businessTitle)
                .orElse(null);
    }

    private List<String> connectorIssues(
            String connectorType,
            String endpointUri,
            String authMode,
            String credentialReference) {
        List<String> issues = new ArrayList<>();
        if (!"FILE_IMPORT".equals(connectorType)) {
            if (endpointUri == null || endpointUri.isBlank()) {
                issues.add("A remote HRIS connector requires an HTTPS endpoint.");
            } else {
                try {
                    URI endpoint = new URI(endpointUri.trim());
                    if (!"https".equalsIgnoreCase(endpoint.getScheme())
                            || endpoint.getHost() == null
                            || endpoint.getUserInfo() != null) {
                        issues.add("The HRIS endpoint must be an HTTPS origin without embedded credentials.");
                    }
                } catch (URISyntaxException exception) {
                    issues.add("The HRIS endpoint URI is invalid.");
                }
            }
        }
        if (!"NONE".equals(authMode)) {
            String reference = credentialReference == null ? "" : credentialReference.trim();
            if (!reference.matches("(vault|secret|env|aws-secretsmanager)://[A-Za-z0-9/_.:@-]+")) {
                issues.add("Credentials must use a supported secret reference; raw secrets are not accepted.");
            }
        }
        return issues;
    }

    private String redactReference(String value) {
        if (value == null || value.isBlank()) return "none";
        int delimiter = value.indexOf("://");
        return delimiter < 0 ? "redacted" : value.substring(0, delimiter) + "://***";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRIS audit serialization failed.", exception);
        }
    }

    private void validate(HrisModels.WorkforceBatch batch) {
        if (!batch.synthetic()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The reference importer accepts only the bundled synthetic fixture.");
        }
        if (!"WORKDAY".equals(batch.sourceType()) || batch.workers().isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid Workday fixture metadata.");
        }
        for (HrisModels.WorkerRecord worker : batch.workers()) {
            requireText(worker.externalId(), "worker.externalId");
            requireText(worker.workerNumber(), "worker.workerNumber");
            requireText(worker.displayName(), "worker.displayName");
            if (!WORKER_TYPES.contains(worker.workerType())) {
                throw invalid("Unsupported worker type: " + worker.workerType());
            }
            if (!WORKER_STATUSES.contains(worker.workerStatus())) {
                throw invalid("Unsupported worker status: " + worker.workerStatus());
            }
            if (worker.originalHireDate() == null || worker.employer() == null
                    || worker.assignments() == null || worker.assignments().isEmpty()) {
                throw invalid("Worker employment and assignment data are required.");
            }
            for (HrisModels.Assignment assignment : worker.assignments()) {
                if (!ASSIGNMENT_STATUSES.contains(assignment.assignmentStatus())) {
                    throw invalid("Unsupported assignment status: " + assignment.assignmentStatus());
                }
                if (assignment.effectiveStartDate() == null
                        || (assignment.effectiveEndDate() != null
                        && assignment.effectiveEndDate().isBefore(assignment.effectiveStartDate()))) {
                    throw invalid("Assignment effective dates are invalid.");
                }
            }
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw invalid("Missing required field: " + field);
    }

    private String normalizeIdempotencyKey(String value) {
        String normalized = value.trim();
        if (normalized.length() > 160 || !normalized.matches("[A-Za-z0-9:._-]+")) {
            throw invalid("The idempotency key format is invalid.");
        }
        return normalized;
    }

    private String sha256(Object value) {
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(value);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(serialized);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("HRIS payload hashing failed.", exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
