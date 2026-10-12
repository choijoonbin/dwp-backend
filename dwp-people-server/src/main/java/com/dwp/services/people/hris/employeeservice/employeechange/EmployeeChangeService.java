package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.contracts.hris.xcon.v1.Xcon022EmployeeChangeAppliedV1;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge.EmployeeChangeApprovalRequestOutbox;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owner service for the exact HRM-010 employee-change lifecycle. */
@Service
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeService {

    private static final String CREATE_COMMAND = "EMPLOYEE_CHANGE_CREATE";
    private static final String SUBMIT_COMMAND = "EMPLOYEE_CHANGE_SUBMIT";
    private static final String APPLY_COMMAND = "EMPLOYEE_CHANGE_APPLY";
    private static final String CREATE_ACTION = "CREATE_EMPLOYEE_CHANGE_REQUEST";
    private static final String SUBMIT_ACTION = "SUBMIT_EMPLOYEE_CHANGE_REQUEST";
    private static final String APPLY_ACTION = "APPLY_EMPLOYEE_CHANGE_REQUEST";
    private static final String CREATE_PATH =
            "/api/people/v1/hris/employee-change-requests";
    private static final String REQUEST_PATH_PREFIX =
            "/api/people/v1/hris/employee-change-requests/";

    private final EmployeeChangeRepository repository;
    private final EmployeeChangeCanonicalizer canonicalizer;
    private final EmployeeChangePatchProtector protector;
    private final EmployeeChangeAuthority authority;
    private final EmployeeChangeWorkbenchProjector projector;
    private final HcmPopulationScopeService populationScopes;
    private final HcmPopulationRepository populations;
    private final HcmHighRiskCommandGuard highRisk;
    private final EmployeeChangeApprovalRequestOutbox approvalOutbox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String workflowKey;
    private final long workflowVersion;

    @Autowired
    EmployeeChangeService(
            EmployeeChangeRepository repository,
            EmployeeChangeCanonicalizer canonicalizer,
            EmployeeChangePatchProtector protector,
            EmployeeChangeAuthority authority,
            EmployeeChangeWorkbenchProjector projector,
            HcmPopulationScopeService populationScopes,
            HcmPopulationRepository populations,
            HcmHighRiskCommandGuard highRisk,
            EmployeeChangeApprovalRequestOutbox approvalOutbox,
            ObjectMapper mapper,
            @Value("${dwp.hris.employee-change.approval-workflow-key:employee-change.standard}")
            String workflowKey,
            @Value("${dwp.hris.employee-change.approval-workflow-version:1}")
            long workflowVersion) {
        this(repository, canonicalizer, protector, authority, projector,
                populationScopes, populations, highRisk, approvalOutbox, mapper,
                Clock.systemUTC(), workflowKey, workflowVersion);
    }

    EmployeeChangeService(
            EmployeeChangeRepository repository,
            EmployeeChangeCanonicalizer canonicalizer,
            EmployeeChangePatchProtector protector,
            EmployeeChangeAuthority authority,
            EmployeeChangeWorkbenchProjector projector,
            HcmPopulationScopeService populationScopes,
            HcmPopulationRepository populations,
            HcmHighRiskCommandGuard highRisk,
            EmployeeChangeApprovalRequestOutbox approvalOutbox,
            ObjectMapper mapper,
            Clock clock,
            String workflowKey,
            long workflowVersion) {
        this.repository = repository;
        this.canonicalizer = canonicalizer;
        this.protector = protector;
        this.authority = authority;
        this.projector = projector;
        this.populationScopes = populationScopes;
        this.populations = populations;
        this.highRisk = highRisk;
        this.approvalOutbox = approvalOutbox;
        this.mapper = mapper;
        this.clock = clock;
        if (workflowKey == null
                || !workflowKey.matches("[a-z][a-z0-9._-]{2,127}")
                || workflowVersion < 1) {
            throw new IllegalArgumentException("Employee-change approval workflow pin is invalid.");
        }
        this.workflowKey = workflowKey;
        this.workflowVersion = workflowVersion;
    }

    @Transactional(readOnly = true)
    WorkbenchResult workbench(EmployeeChangeDtos.WorkbenchQuery query) {
        authority.require("READ");
        PeopleRequestContext.Actor actor = actor();
        HcmPopulationScopeService.ResolvedPopulation population =
                populationScopes.requireOperations("READ");
        populationScopes.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
        HcmPepContext.Evidence pep = HcmPepContext.require();
        EmployeeChangeWorkbenchProjector.PreparedQuery prepared = projector.prepare(
                query, pep.decisionRevision(), clock.instant());
        List<EmployeeChangeRepository.WorkbenchRow> rows = repository.workbench(
                actor.tenantId(), prepared.asOf(), population.scope());
        EmployeeChangeDtos.WorkbenchProjection projection = projector.project(
                prepared, rows, population.scope().fieldGroups(), actor.personPublicId());
        String etag = '"' + canonicalizer.requestDigest(projection).substring(0, 32) + '"';
        return new WorkbenchResult(projection, prepared.scopeRevision(), etag);
    }

    @Transactional
    CommandResult create(
            EmployeeChangeDtos.CreateRequest request,
            String idempotencyKey,
            String ifMatch,
            UUID correlationId,
            String requestedPurpose) {
        String capability = authority.require("CREATE");
        PeopleRequestContext.Actor actor = actor();
        String key = idempotencyKey(idempotencyKey);
        String purpose = purpose(capability, requestedPurpose);
        long expectedTargetVersion = expectedVersion(ifMatch);
        EmployeeChangeCanonicalizer.CanonicalPatch patch = canonicalizer.canonicalPatch(request);
        requireUniqueEvidence(request.evidence());
        ScopeBinding scope = mutationScope(capability);
        EmployeeChangeRepository.TargetRow target = repository.targetForMutation(
                actor.tenantId(), request.targetPersonPublicId(),
                patch.targetAggregateType(), request.targetAggregatePublicId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireTarget(actor, scope, target, true);
        requireFields(scope, patch.fieldGroups());
        UUID requestPublicId = deterministicUuid(
                "employee-change-request", actor.tenantId(),
                request.targetPersonPublicId(), CREATE_ACTION, key);
        CommandEvidence evidence = evidence(
                actor, scope, purpose, capability, request.targetPersonPublicId());
        Map<String, Object> requestBinding = new LinkedHashMap<>();
        requestBinding.put("method", "POST");
        requestBinding.put("path", CREATE_PATH);
        requestBinding.put("requestPublicId", requestPublicId);
        requestBinding.put("ifMatch", expectedTargetVersion);
        requestBinding.put("idempotencyKey", key);
        requestBinding.put("correlationId", correlationId);
        requestBinding.put("purposeCode", purpose);
        requestBinding.put("body", request);
        String requestDigest = canonicalizer.requestDigest(requestBinding);
        Claim claim = claim(
                actor, evidence, CREATE_COMMAND, CREATE_ACTION, key, requestDigest,
                requestPublicId, expectedTargetVersion, correlationId);
        if (!claim.fresh()) return replay(claim.receipt());

        requireEffectiveDate(request.effectiveDate(), target);
        if (expectedTargetVersion != target.aggregateVersion()) {
            throw versionConflict("The employee-change target version is stale.");
        }

        EmployeeChangePatchProtector.ProtectedPatch protectedPatch = protector.protect(
                actor.tenantId(), requestPublicId, patch.bytes());
        long requesterPersonId = repository.requesterPersonId(
                actor.tenantId(), actor.personPublicId());
        EmployeeChangeRepository.RequestRow created = repository.create(
                actor.tenantId(), requestPublicId, requesterPersonId,
                actor.personPublicId(), target, patch, protectedPatch,
                request.effectiveDate(), evidence.populationDigest(),
                evidence.fieldRevision(), evidence.authorizationRevision(),
                evidence.contextDigest(), correlationId, actor.userId(),
                request.evidence(), purpose);
        EmployeeChangeRepository.ReceiptRow receipt = repository.completeReceipt(
                actor.tenantId(), claim.receipt().receiptId(), "CREATED",
                Map.of("requestPublicId", created.publicId().toString(),
                        "requestVersion", created.version()), clock.instant());
        repository.appendAudit(
                actor.tenantId(), actor.userId(), "employee.change.created",
                created.publicId(), correlationId,
                Map.of("state", created.lifecycleState(),
                        "targetType", created.targetAggregateType(),
                        "requestVersion", created.version()), clock.instant());
        return new CommandResult(toReceipt(receipt), created.version());
    }

    @Transactional
    CommandResult submit(
            UUID requestPublicId,
            String idempotencyKey,
            String ifMatch,
            UUID correlationId,
            String requestedPurpose) {
        String capability = authority.require("SUBMIT");
        PeopleRequestContext.Actor actor = actor();
        String key = idempotencyKey(idempotencyKey);
        String purpose = purpose(capability, requestedPurpose);
        long expectedRequestVersion = expectedVersion(ifMatch);
        ScopeBinding scope = mutationScope(capability);
        EmployeeChangeRepository.RequestRow request = requireRequest(
                actor.tenantId(), requestPublicId);
        if (!actor.personPublicId().equals(request.requesterPublicId())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Only the requester may submit an employee-change draft.");
        }
        EmployeeChangeRepository.TargetRow target = currentTarget(
                actor, scope, request, true);
        requireFields(scope, request.fieldGroups());
        CommandEvidence evidence = evidence(
                actor, scope, purpose, capability, request.targetPersonPublicId());
        String path = REQUEST_PATH_PREFIX + requestPublicId + "/submit";
        String requestDigest = commandDigest(
                path, requestPublicId, expectedRequestVersion, key, correlationId, purpose);
        Claim claim = claim(
                actor, evidence, SUBMIT_COMMAND, SUBMIT_ACTION, key, requestDigest,
                requestPublicId, expectedRequestVersion, correlationId);
        if (!claim.fresh()) return replay(claim.receipt());

        requireStateAndVersion(request, "DRAFT", expectedRequestVersion);
        requireEffectiveDate(request.effectiveDate(), target);
        if (target.aggregateVersion() != request.expectedTargetVersion()) {
            throw versionConflict("The employee-change target changed before submission.");
        }

        UUID approvalRequestId = deterministicUuid(
                "employee-change-approval", actor.tenantId(), requestPublicId,
                SUBMIT_ACTION, Long.toString(request.version() + 1));
        Instant now = clock.instant();
        EmployeeChangeRepository.RequestRow submitted = repository.submit(
                actor.tenantId(), request, approvalRequestId,
                workflowKey, workflowVersion, now, actor.userId());
        approvalOutbox.enqueueSubmitted(actor.tenantId(), submitted.publicId());
        EmployeeChangeRepository.ReceiptRow receipt = repository.completeReceipt(
                actor.tenantId(), claim.receipt().receiptId(), "SUBMITTED",
                Map.of("requestPublicId", submitted.publicId().toString(),
                        "requestVersion", submitted.version(),
                        "approvalRequestId", approvalRequestId.toString()), now);
        repository.appendAudit(
                actor.tenantId(), actor.userId(), "employee.change.submitted",
                submitted.publicId(), correlationId,
                Map.of("state", submitted.lifecycleState(),
                        "requestVersion", submitted.version(),
                        "approvalSubjectRevision", submitted.approvalSubjectRevision()), now);
        return new CommandResult(toReceipt(receipt), submitted.version());
    }

    @Transactional
    CommandResult apply(
            UUID requestPublicId,
            String idempotencyKey,
            String ifMatch,
            UUID correlationId,
            String requestedPurpose,
            HcmStepUpHeaders stepUp) {
        String capability = authority.require("APPLY");
        PeopleRequestContext.Actor actor = actor();
        String key = idempotencyKey(idempotencyKey);
        if (stepUp == null || !key.equals(stepUp.idempotencyKey())) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "The apply idempotency key is not bound to step-up evidence.");
        }
        String purpose = purpose(capability, requestedPurpose);
        long expectedRequestVersion = expectedVersion(ifMatch);
        ScopeBinding scope = mutationScope(capability);
        EmployeeChangeRepository.RequestRow request = requireRequest(
                actor.tenantId(), requestPublicId);
        if (actor.personPublicId().equals(request.requesterPublicId())) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "The employee-change requester cannot apply the same request.");
        }
        EmployeeChangeRepository.TargetRow target = currentTarget(
                actor, scope, request, true);
        requireFields(scope, request.fieldGroups());
        CommandEvidence evidence = evidence(
                actor, scope, purpose, capability, request.targetPersonPublicId());
        String path = REQUEST_PATH_PREFIX + requestPublicId + "/apply";
        String requestDigest = commandDigest(
                path, requestPublicId, expectedRequestVersion, key, correlationId, purpose);
        Claim claim = claim(
                actor, evidence, APPLY_COMMAND, APPLY_ACTION, key, requestDigest,
                requestPublicId, expectedRequestVersion, correlationId);
        Map<String, Object> stepUpPayload = Map.of(
                "requestPublicId", requestPublicId,
                "expectedVersion", expectedRequestVersion);
        if (!claim.fresh()) {
            highRisk.requireExact(
                    EmployeeChangeAuthority.APPLY_CAPABILITY,
                    "EMPLOYEE_CHANGE_REQUEST", requestPublicId.toString(),
                    expectedRequestVersion, path, stepUpPayload, stepUp);
            return replay(claim.receipt());
        }

        requireStateAndVersion(request, "APPROVED", expectedRequestVersion);
        requireEffectiveDate(request.effectiveDate(), target);
        if (target.aggregateVersion() != request.expectedTargetVersion()) {
            throw versionConflict("The approved employee-change target is stale.");
        }
        highRisk.requireExact(
                EmployeeChangeAuthority.APPLY_CAPABILITY,
                "EMPLOYEE_CHANGE_REQUEST", requestPublicId.toString(),
                expectedRequestVersion,
                path, stepUpPayload, stepUp);
        byte[] plaintext = protector.reveal(
                actor.tenantId(), request.publicId(), request.encryptedPatch());
        EmployeeChangeCanonicalizer.CanonicalPatch patch = canonicalizer.thaw(
                plaintext, request.patchHash(), request.targetAggregateType(),
                request.changeType(), request.patchSchemaKey());

        Instant now = clock.instant();
        EmployeeChangeRepository.RequestRow applying = repository.beginApply(
                actor.tenantId(), request, now, actor.userId());
        long appliedTargetVersion = repository.applyTarget(
                actor.tenantId(), applying, patch.operations(), actor.userId());
        EmployeeChangeRepository.RequestRow applied = repository.markApplied(
                actor.tenantId(), applying, appliedTargetVersion, now, actor.userId());
        UUID eventId = UUID.randomUUID();
        Xcon022EmployeeChangeAppliedV1 event = new Xcon022EmployeeChangeAppliedV1(
                applied.publicId(), applied.version(), appliedTargetVersion,
                claim.receipt().receiptId(), applied.fieldGroups(), correlationId,
                applied.effectiveDate(), eventId, now, applied.patchHash(),
                applied.publicId(), 1, applied.targetAggregatePublicId(),
                applied.targetAggregateType(), actor.tenantId());
        repository.appendEvent(
                actor.tenantId(), eventId, applied.publicId(),
                "EmployeeChangeApplied.v1", json(event), correlationId, now);
        EmployeeChangeRepository.ReceiptRow receipt = repository.completeReceipt(
                actor.tenantId(), claim.receipt().receiptId(), "APPLIED",
                Map.of("requestPublicId", applied.publicId().toString(),
                        "requestVersion", applied.version(),
                        "appliedTargetVersion", appliedTargetVersion,
                        "eventId", eventId.toString()), now);
        repository.appendAudit(
                actor.tenantId(), actor.userId(), "employee.change.applied",
                applied.publicId(), correlationId,
                Map.of("state", applied.lifecycleState(),
                        "requestVersion", applied.version(),
                        "targetType", applied.targetAggregateType(),
                        "appliedTargetVersion", appliedTargetVersion,
                        "eventType", "EmployeeChangeApplied.v1"), now);
        return new CommandResult(toReceipt(receipt), applied.version());
    }

    private ScopeBinding mutationScope(String capability) {
        HcmPepContext.Evidence pep = HcmPepContext.require();
        if ("hcm.personal.profile.update".equals(capability)) {
            populationScopes.requireSelfScope();
            return new ScopeBinding(
                    null, true, "self:" + pep.scopeKey(),
                    Set.of("DIRECTORY", "PERSON_PROFILE", "CONTACT"));
        }
        if ("hcm.team.people-change.request".equals(capability)) {
            HcmPopulationScopeService.ResolvedPopulation population =
                    populationScopes.requireTeamForMutation();
            populationScopes.requireTrustedScope(
                    population, "hcm.team", "TARGET_POPULATION",
                    "TEAM/ORG_UNIT",
                    "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION");
            return new ScopeBinding(
                    population, false, population.targetPopulationRevision(),
                    population.scope().fieldGroups());
        }
        HcmPopulationScopeService.ResolvedPopulation population =
                populationScopes.requireOperationsForMutation("READ");
        populationScopes.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
        return new ScopeBinding(
                population, false, population.targetPopulationRevision(),
                population.scope().fieldGroups());
    }

    private EmployeeChangeRepository.RequestRow requireRequest(
            long tenantId,
            UUID requestPublicId) {
        return repository.requestForMutation(tenantId, requestPublicId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private EmployeeChangeRepository.TargetRow currentTarget(
            PeopleRequestContext.Actor actor,
            ScopeBinding scope,
            EmployeeChangeRepository.RequestRow request,
            boolean lock) {
        EmployeeChangeRepository.TargetRow target = repository.targetForMutation(
                actor.tenantId(), request.targetPersonPublicId(),
                request.targetAggregateType(), request.targetAggregatePublicId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireTarget(actor, scope, target, lock);
        return target;
    }

    private void requireTarget(
            PeopleRequestContext.Actor actor,
            ScopeBinding scope,
            EmployeeChangeRepository.TargetRow target,
            boolean lock) {
        if (scope.self()) {
            if (!actor.personPublicId().equals(target.personPublicId())) {
                throw new BaseException(ErrorCode.NOT_FOUND);
            }
            return;
        }
        HcmPopulationRepository.PopulationScope population = scope.population().scope();
        boolean contained = lock
                ? populations.lockWorkerInPopulation(
                        actor.tenantId(), population, target.workerId())
                : populations.containsWorker(actor.tenantId(), population, target.workerId());
        if (!contained) throw new BaseException(ErrorCode.NOT_FOUND);
    }

    private void requireFields(ScopeBinding scope, List<String> fields) {
        for (String field : fields) {
            boolean allowed = switch (field) {
                case "PERSON_NAME", "PERSON_PROFILE" -> scope.self()
                        || scope.fieldGroups().contains("DIRECTORY");
                case "EMPLOYMENT", "ASSIGNMENT" -> scope.fieldGroups().contains("EMPLOYMENT")
                        || scope.fieldGroups().contains("LIMITED_EMPLOYMENT");
                default -> false;
            };
            if (!allowed) {
                throw new BaseException(ErrorCode.FORBIDDEN,
                        "The employee-change field policy denies this patch.");
            }
        }
    }

    private void requireEffectiveDate(
            LocalDate effectiveDate,
            EmployeeChangeRepository.TargetRow target) {
        if (effectiveDate == null
                || (target.effectiveFrom() != null
                    && effectiveDate.isBefore(target.effectiveFrom()))
                || (target.effectiveTo() != null
                    && effectiveDate.isAfter(target.effectiveTo()))) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The employee-change effective date is outside the target period.");
        }
    }

    private void requireStateAndVersion(
            EmployeeChangeRepository.RequestRow request,
            String state,
            long expectedVersion) {
        if (request.version() != expectedVersion) {
            throw versionConflict("The employee-change request version is stale.");
        }
        if (!state.equals(request.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "The employee-change request must be " + state + '.');
        }
    }

    private CommandEvidence evidence(
            PeopleRequestContext.Actor actor,
            ScopeBinding scope,
            String purpose,
            String capability,
            UUID subject) {
        HcmPepContext.Evidence pep = HcmPepContext.require();
        String populationDigest = canonicalizer.digestText(scope.scopeRevision());
        long fieldRevision = canonicalizer.positiveRevision(
                scope.scopeRevision() + '|' + scope.fieldGroups().stream().sorted().toList());
        long authorizationRevision = canonicalizer.positiveRevision(pep.decisionRevision());
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", actor.tenantId());
        context.put("userId", actor.userId());
        context.put("personPublicId", actor.personPublicId());
        context.put("subjectPublicId", subject);
        context.put("routeContractKey", pep.authority().routeContractKey());
        context.put("capabilityContractKey", capability);
        context.put("decisionRevision", pep.decisionRevision());
        context.put("contextKey", pep.contextKey());
        context.put("scopeKey", pep.scopeKey());
        context.put("purposeCode", purpose);
        return new CommandEvidence(
                subject, populationDigest, fieldRevision, purpose,
                authorizationRevision, canonicalizer.requestDigest(context));
    }

    private Claim claim(
            PeopleRequestContext.Actor actor,
            CommandEvidence evidence,
            String commandType,
            String action,
            String key,
            String requestDigest,
            UUID targetPublicId,
            long expectedVersion,
            UUID correlationId) {
        Instant now = clock.instant();
        UUID receiptId = UUID.randomUUID();
        EmployeeChangeRepository.ReceiptRow inserted = repository.insertReceipt(
                actor.tenantId(), receiptId, commandType, key, requestDigest,
                actor.personPublicId(), action, evidence.subjectPublicId(),
                evidence.populationDigest(), evidence.fieldRevision(), evidence.purpose(),
                evidence.authorizationRevision(), evidence.contextDigest(), targetPublicId,
                expectedVersion, correlationId, now).orElse(null);
        if (inserted != null) return new Claim(inserted, true);
        EmployeeChangeRepository.ReceiptRow existing = repository.receipt(
                actor.tenantId(), evidence.subjectPublicId(), action, key,
                commandType, correlationId).orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The employee-change command identity is already in use."));
        if (!commandType.equals(existing.commandType())
                || !key.equals(existing.idempotencyKey())
                || !requestDigest.equals(existing.requestDigest())
                || !actor.personPublicId().equals(existing.actorPublicId())
                || !action.equals(existing.action())
                || !evidence.subjectPublicId().equals(existing.subjectPublicId())
                || !evidence.purpose().equals(existing.purpose())
                || !targetPublicId.equals(existing.targetPublicId())
                || !Objects.equals(expectedVersion, existing.expectedVersion())
                || !correlationId.equals(existing.correlationId())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key is bound to different command evidence.");
        }
        if (!"SUCCEEDED".equals(existing.lifecycleState())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The prior employee-change command has not completed.");
        }
        return new Claim(existing, false);
    }

    private CommandResult replay(EmployeeChangeRepository.ReceiptRow receipt) {
        return new CommandResult(toReceipt(receipt), replayEntityVersion(receipt));
    }

    private long replayEntityVersion(EmployeeChangeRepository.ReceiptRow receipt) {
        try {
            if (receipt.resultReference() != null) {
                com.fasterxml.jackson.databind.JsonNode result =
                        mapper.readTree(receipt.resultReference());
                com.fasterxml.jackson.databind.JsonNode version = result.get("requestVersion");
                if (version != null && version.canConvertToLong() && version.longValue() > 0) {
                    return version.longValue();
                }
            }
        } catch (Exception ignored) {
            // Fail closed below; a successful receipt without its immutable result is corrupt.
        }
        throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                "The prior employee-change command result is unavailable.");
    }

    private CommandReceiptV1 toReceipt(EmployeeChangeRepository.ReceiptRow row) {
        return new CommandReceiptV1(
                row.receiptId(), row.commandType(), row.action(), row.subjectPublicId(),
                row.populationDigest(), row.fieldRevision(), row.purpose(),
                row.authorizationRevision(), receiptStatus(row.lifecycleState()),
                row.correlationId(), row.requestDigest(), row.createdAt());
    }

    private String receiptStatus(String lifecycle) {
        return switch (lifecycle) {
            case "RECEIVED" -> "ACCEPTED";
            case "VALIDATING", "RUNNING" -> "RUNNING";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "REJECTED", "CANCELLED" -> "REJECTED";
            case "FAILED_RETRYABLE", "DEAD" -> "FAILED";
            default -> "RESULT_UNKNOWN";
        };
    }

    private String commandDigest(
            String path,
            UUID requestPublicId,
            long expectedVersion,
            String idempotencyKey,
            UUID correlationId,
            String purpose) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("method", "POST");
        binding.put("path", path);
        binding.put("requestPublicId", requestPublicId);
        binding.put("ifMatch", expectedVersion);
        binding.put("idempotencyKey", idempotencyKey);
        binding.put("correlationId", correlationId);
        binding.put("purposeCode", purpose);
        binding.put("body", Map.of());
        return canonicalizer.requestDigest(binding);
    }

    private PeopleRequestContext.Actor actor() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.userId() == null || actor.userId() < 1
                || actor.tenantId() == null || actor.tenantId() < 1
                || actor.personPublicId() == null) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "A tenant-bound People identity is required.");
        }
        return actor;
    }

    private String idempotencyKey(String value) {
        if (value == null || value.length() < 16 || value.length() > 128
                || !value.equals(value.trim())
                || !value.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A canonical Idempotency-Key is required.");
        }
        return value;
    }

    private long expectedVersion(String value) {
        if (value == null || !value.matches("(?:W/)?\"(?:v:)?[0-9]+\"")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "If-Match must contain a numeric governed version.");
        }
        try {
            String number = value.replaceFirst("^(?:W/)?\"(?:v:)?", "")
                    .replace("\"", "");
            return Long.parseLong(number);
        } catch (RuntimeException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "If-Match contains an invalid governed version.");
        }
    }

    private String purpose(String capability, String requested) {
        String expected = switch (capability) {
            case "hcm.personal.profile.update" -> "EMPLOYEE_SELF_SERVICE";
            case "hcm.team.people-change.request" -> "TEAM_MANAGEMENT";
            case EmployeeChangeAuthority.APPLY_CAPABILITY -> "HR_LIFECYCLE";
            default -> "HR_OPERATIONS";
        };
        if (requested == null || requested.isBlank()) return expected;
        String value = requested.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9._-]{2,63}") || !expected.equals(value)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The employee-change purpose is not authorized for this route.");
        }
        return value;
    }

    private void requireUniqueEvidence(List<EmployeeChangeDtos.EvidenceReference> evidence) {
        if (evidence.stream().map(EmployeeChangeDtos.EvidenceReference::objectRef)
                .distinct().count() != evidence.size()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Employee-change evidence references must be unique.");
        }
    }

    private UUID deterministicUuid(
            String namespace,
            long tenantId,
            UUID subject,
            String action,
            String key) {
        String value = namespace + '\n' + tenantId + '\n' + subject + '\n' + action + '\n' + key;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Employee-change event serialization failed.", exception);
        }
    }

    private BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    record WorkbenchResult(
            EmployeeChangeDtos.WorkbenchProjection projection,
            String authorizationRevision,
            String etag) {
    }

    record CommandResult(CommandReceiptV1 receipt, long entityVersion) {
    }

    private record ScopeBinding(
            HcmPopulationScopeService.ResolvedPopulation population,
            boolean self,
            String scopeRevision,
            Set<String> fieldGroups) {
        ScopeBinding {
            fieldGroups = Set.copyOf(fieldGroups);
        }
    }

    private record CommandEvidence(
            UUID subjectPublicId,
            String populationDigest,
            long fieldRevision,
            String purpose,
            long authorizationRevision,
            String contextDigest) {
    }

    private record Claim(EmployeeChangeRepository.ReceiptRow receipt, boolean fresh) {
    }
}
