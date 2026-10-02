package com.dwp.services.people.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.workforce.WorkforceAccessDtos;
import com.dwp.services.people.workforce.WorkforceAccessPolicyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Materializes the local acceptance workforce through production People owner
 * services. No privileged fixture SQL or synthetic PASS evidence is used.
 */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.people-workforce.synthetic-bootstrap.enabled",
        havingValue = "true")
public class LocalSyntheticPeopleWorkforceBootstrapService {

    private static final String ACTOR_WORKER_NUMBER = "E100001";
    private static final String TARGET_WORKER_NUMBER = "E100002";
    private static final String POLICY_ROLE = "TENANT_ADMIN";
    private static final String AUTH_ENDPOINT = "/internal/identity/v1/workforce-events";
    private static final String AUTH_TOKEN_HEADER = "X-DWP-Identity-Sync-Token";
    private static final Set<String> BOOTSTRAP_ROLES = Set.of("ADMIN", POLICY_ROLE);
    private static final Set<String> BOOTSTRAP_PERMISSIONS =
            Set.of("ADMIN.WORKFORCE_ACCESS:MANAGE");
    private static final Set<String> POLICY_FIELD_GROUPS = Set.of("DIRECTORY");
    private static final Set<String> POLICY_ACTIONS = Set.of("READ");

    private final HrisImportService imports;
    private final HrisIntegrationRepository repository;
    private final WorkforceAccessPolicyService accessPolicies;
    private final HcmPopulationScopeService populations;
    private final String expectedRunId;

    public LocalSyntheticPeopleWorkforceBootstrapService(
            HrisImportService imports,
            HrisIntegrationRepository repository,
            WorkforceAccessPolicyService accessPolicies,
            HcmPopulationScopeService populations,
            @Value("${dwp.hris.people-workforce.synthetic-bootstrap.run-id:}")
                    String expectedRunId) {
        this.imports = imports;
        this.repository = repository;
        this.accessPolicies = accessPolicies;
        this.populations = populations;
        this.expectedRunId = expectedRunId == null ? "" : expectedRunId;
        if (!this.expectedRunId.matches("w1-[0-9]{8}t[0-9]{6}z-[0-9a-f]{8}")) {
            throw new IllegalStateException(
                    "Local synthetic People bootstrap requires an exact W1 run binding.");
        }
    }

    @Transactional
    public LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapResponse bootstrap(
            LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapRequest request) {
        if (!expectedRunId.equals(request.runId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic People request is not bound to this runtime.");
        }
        if (!repository.isActiveTenantBinding(
                request.tenantId(), request.providerTenantId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Synthetic People request is not bound to an active People tenant.");
        }

        PeopleRequestContext.set(
                request.administratorActorId(),
                request.tenantId(),
                null,
                BOOTSTRAP_ROLES,
                BOOTSTRAP_PERMISSIONS);
        try {
            UUID correlationId = deterministic("people-import", request);
            HrisDtos.ImportResult imported = imports.importSyntheticWorkdayFixture(
                    "synthetic:" + expectedRunId + ":people-workforce",
                    correlationId.toString());
            if (imported.replayed()) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "Synthetic People bootstrap is a one-shot runtime boundary.");
            }
            if (!imported.syntheticFixture()
                    || !"COMPLETED".equals(imported.lifecycleState())
                    || imported.rejectedCount() != 0
                    || imported.readCount() != 3
                    || imported.createdCount() + imported.updatedCount() != 3) {
                throw new IllegalStateException(
                        "Synthetic People import did not complete with exact fixture evidence.");
            }
            HrisIntegrationRepository.WorkforceIdentityProjection identity = repository
                    .findWorkforceIdentity(request.tenantId(), ACTOR_WORKER_NUMBER)
                    .orElseThrow(() -> new IllegalStateException(
                            "Synthetic People import did not produce the bound actor workforce."));
            requireCompleteIdentity(identity, ACTOR_WORKER_NUMBER, "actor");
            HrisIntegrationRepository.WorkforceIdentityProjection target = repository
                    .findWorkforceIdentity(request.tenantId(), TARGET_WORKER_NUMBER)
                    .orElseThrow(() -> new IllegalStateException(
                            "Synthetic People import did not produce the target workforce."));
            requireCompleteIdentity(target, TARGET_WORKER_NUMBER, "target");

            PeopleRequestContext.set(
                    request.administratorActorId(),
                    request.tenantId(),
                    identity.personPublicId(),
                    BOOTSTRAP_ROLES,
                    BOOTSTRAP_PERMISSIONS);
            WorkforceAccessDtos.Policy policy = ensureOperationsPolicy(correlationId);
            HcmPopulationScopeService.ResolvedPopulation population = populations
                    .findOperations("READ")
                    .orElseThrow(() -> new IllegalStateException(
                            "Synthetic People workforce has no live DIRECTORY READ population."));
            if (population.evidence().count() != 2) {
                throw new IllegalStateException(
                        "Synthetic People workforce target population is not the exact fixture.");
            }
            if (!populations.containsWorker(population, target.workerId())
                    || populations.containsWorker(population, identity.workerId())) {
                throw new IllegalStateException(
                        "Synthetic People target lineage does not match the owner population.");
            }

            UUID eventId = deterministic("auth-workforce-event", request);
            var event = new LocalSyntheticPeopleWorkforceBootstrapDtos.AuthWorkforceEvent(
                    eventId,
                    request.providerTenantId(),
                    identity.personPublicId(),
                    identity.externalId(),
                    identity.workerNumber(),
                    identity.displayName(),
                    identity.givenName(),
                    identity.familyName(),
                    identity.workEmail(),
                    identity.jobTitle(),
                    identity.preferredLocale(),
                    identity.workerStatus(),
                    identity.sourceVersion());
            var binding = new LocalSyntheticPeopleWorkforceBootstrapDtos.AuthWorkforceBinding(
                    AUTH_ENDPOINT,
                    AUTH_TOKEN_HEADER,
                    request.administratorActorId(),
                    event);
            ReceiptEvidence evidence = new ReceiptEvidence(
                    expectedRunId,
                    request.providerTenantId(),
                    request.tenantId(),
                    request.administratorActorId(),
                    identity.personPublicId(),
                    identity.workerPublicId(),
                    identity.assignmentPublicId(),
                    identity.legalEmployerPublicId(),
                    identity.workerNumber(),
                    target.personPublicId(),
                    target.workerPublicId(),
                    target.assignmentPublicId(),
                    imported.syncRunId(),
                    imported.replayed(),
                    imported.readCount(),
                    policy.policyId(),
                    policy.version(),
                    population.evidence().count(),
                    population.evidence().revision(),
                    binding);
            String receipt = receiptSha256(evidence);
            return new LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapResponse(
                    expectedRunId,
                    request.providerTenantId(),
                    request.tenantId(),
                    request.administratorActorId(),
                    identity.personPublicId(),
                    identity.workerPublicId(),
                    identity.assignmentPublicId(),
                    identity.legalEmployerPublicId(),
                    identity.workerNumber(),
                    target.personPublicId(),
                    target.workerPublicId(),
                    target.assignmentPublicId(),
                    imported.syncRunId(),
                    imported.replayed(),
                    imported.readCount(),
                    policy.policyId(),
                    policy.version(),
                    population.evidence().count(),
                    population.evidence().revision(),
                    binding,
                    receipt);
        } finally {
            PeopleRequestContext.clear();
        }
    }

    private WorkforceAccessDtos.Policy ensureOperationsPolicy(UUID correlationId) {
        List<WorkforceAccessDtos.Policy> matching = accessPolicies.list().stream()
                .filter(policy -> "ROLE".equals(policy.subjectType()))
                .filter(policy -> POLICY_ROLE.equals(policy.subjectRef()))
                .filter(policy -> "TENANT".equals(policy.populationType()))
                .filter(policy -> "ACTIVE".equals(policy.lifecycleState()))
                .filter(policy -> Set.copyOf(policy.fieldGroups()).equals(POLICY_FIELD_GROUPS))
                .filter(policy -> Set.copyOf(policy.actionCodes()).equals(POLICY_ACTIONS))
                .filter(policy -> policy.validFrom() == null && policy.validTo() == null)
                .toList();
        if (matching.size() > 1) {
            throw new IllegalStateException(
                    "Synthetic People workforce resolved multiple active tenant-admin policies.");
        }
        if (!matching.isEmpty()) return matching.getFirst();
        return accessPolicies.create(
                new WorkforceAccessDtos.CreatePolicyRequest(
                        "ROLE",
                        POLICY_ROLE,
                        "TENANT",
                        null,
                        List.copyOf(POLICY_FIELD_GROUPS),
                        List.copyOf(POLICY_ACTIONS),
                        null,
                        null,
                        "Run-bound local W1 People workforce acceptance boundary."),
                correlationId.toString());
    }

    private void requireCompleteIdentity(
            HrisIntegrationRepository.WorkforceIdentityProjection identity,
            String expectedWorkerNumber,
            String lineageName) {
        if (identity.personPublicId() == null
                || identity.workerId() <= 0
                || identity.workerPublicId() == null
                || identity.assignmentPublicId() == null
                || identity.legalEmployerPublicId() == null
                || identity.externalId() == null || identity.externalId().isBlank()
                || !expectedWorkerNumber.equals(identity.workerNumber())
                || identity.displayName() == null || identity.displayName().isBlank()
                || identity.workerStatus() == null || identity.workerStatus().isBlank()) {
            throw new IllegalStateException(
                    "Synthetic People " + lineageName + " lineage is incomplete.");
        }
    }

    private UUID deterministic(
            String kind,
            LocalSyntheticPeopleWorkforceBootstrapDtos.BootstrapRequest request) {
        return UUID.nameUUIDFromBytes(String.join("|",
                        expectedRunId,
                        kind,
                        request.providerTenantId().toString(),
                        Long.toString(request.tenantId()),
                        Long.toString(request.administratorActorId()))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Hashes an ordered, length-delimited material rather than an ad-hoc joined
     * string. Every response field used by Auth binding, PAY/TIM lineage and the
     * population proof is thereby run-bound and tamper-evident.
     */
    static String receiptSha256(ReceiptEvidence evidence) {
        StringBuilder material = new StringBuilder();
        appendCanonical(material, "runId", evidence.runId());
        appendCanonical(material, "providerTenantId", evidence.providerTenantId());
        appendCanonical(material, "tenantId", evidence.tenantId());
        appendCanonical(material, "administratorActorId", evidence.administratorActorId());
        appendCanonical(material, "actorPersonPublicId", evidence.actorPersonPublicId());
        appendCanonical(material, "actorWorkerPublicId", evidence.actorWorkerPublicId());
        appendCanonical(material, "actorAssignmentPublicId", evidence.actorAssignmentPublicId());
        appendCanonical(material, "actorLegalEmployerPublicId",
                evidence.actorLegalEmployerPublicId());
        appendCanonical(material, "actorWorkerNumber", evidence.actorWorkerNumber());
        appendCanonical(material, "targetPersonPublicId", evidence.targetPersonPublicId());
        appendCanonical(material, "targetWorkerPublicId", evidence.targetWorkerPublicId());
        appendCanonical(material, "targetAssignmentPublicId",
                evidence.targetAssignmentPublicId());
        appendCanonical(material, "syncRunId", evidence.syncRunId());
        appendCanonical(material, "importReplayed", evidence.importReplayed());
        appendCanonical(material, "importedWorkerCount", evidence.importedWorkerCount());
        appendCanonical(material, "workforceAccessPolicyId",
                evidence.workforceAccessPolicyId());
        appendCanonical(material, "workforceAccessPolicyVersion",
                evidence.workforceAccessPolicyVersion());
        appendCanonical(material, "targetPopulationCount", evidence.targetPopulationCount());
        appendCanonical(material, "targetPopulationRevision",
                evidence.targetPopulationRevision());

        LocalSyntheticPeopleWorkforceBootstrapDtos.AuthWorkforceBinding binding =
                evidence.authWorkforceBinding();
        appendCanonical(material, "auth.endpoint", binding == null ? null : binding.endpoint());
        appendCanonical(material, "auth.tokenHeader",
                binding == null ? null : binding.tokenHeader());
        appendCanonical(material, "auth.expectedAdministratorUserId",
                binding == null ? null : binding.expectedAdministratorUserId());
        LocalSyntheticPeopleWorkforceBootstrapDtos.AuthWorkforceEvent event =
                binding == null ? null : binding.event();
        appendCanonical(material, "auth.event.eventId", event == null ? null : event.eventId());
        appendCanonical(material, "auth.event.providerTenantId",
                event == null ? null : event.providerTenantId());
        appendCanonical(material, "auth.event.personPublicId",
                event == null ? null : event.personPublicId());
        appendCanonical(material, "auth.event.externalId",
                event == null ? null : event.externalId());
        appendCanonical(material, "auth.event.workerNumber",
                event == null ? null : event.workerNumber());
        appendCanonical(material, "auth.event.displayName",
                event == null ? null : event.displayName());
        appendCanonical(material, "auth.event.givenName",
                event == null ? null : event.givenName());
        appendCanonical(material, "auth.event.familyName",
                event == null ? null : event.familyName());
        appendCanonical(material, "auth.event.workEmail",
                event == null ? null : event.workEmail());
        appendCanonical(material, "auth.event.jobTitle",
                event == null ? null : event.jobTitle());
        appendCanonical(material, "auth.event.preferredLocale",
                event == null ? null : event.preferredLocale());
        appendCanonical(material, "auth.event.workerStatus",
                event == null ? null : event.workerStatus());
        appendCanonical(material, "auth.event.sourceVersion",
                event == null ? null : event.sourceVersion());
        return sha256(material.toString());
    }

    private static void appendCanonical(
            StringBuilder material,
            String name,
            Object rawValue) {
        String value = rawValue == null ? "N" : "V" + rawValue;
        material.append(name.length()).append(':').append(name)
                .append('=').append(value.length()).append(':').append(value).append('\n');
    }

    record ReceiptEvidence(
            String runId,
            UUID providerTenantId,
            long tenantId,
            long administratorActorId,
            UUID actorPersonPublicId,
            UUID actorWorkerPublicId,
            UUID actorAssignmentPublicId,
            UUID actorLegalEmployerPublicId,
            String actorWorkerNumber,
            UUID targetPersonPublicId,
            UUID targetWorkerPublicId,
            UUID targetAssignmentPublicId,
            UUID syncRunId,
            boolean importReplayed,
            long importedWorkerCount,
            UUID workforceAccessPolicyId,
            long workforceAccessPolicyVersion,
            long targetPopulationCount,
            String targetPopulationRevision,
            LocalSyntheticPeopleWorkforceBootstrapDtos.AuthWorkforceBinding
                    authWorkforceBinding) {
    }
}
