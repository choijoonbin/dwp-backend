package com.dwp.services.people.hris.experience;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Generic HRM-008/HRM-010 receipt authority over the unchanged V54 ledger. */
@Service
public class HrisExperienceCommandReceiptService {

    private static final String DIGEST = "[0-9a-f]{64}";

    private static final Map<Tuple, Family> REGISTRY = Map.ofEntries(
            assignment("HRM_ASSIGNMENT_EVENT_CREATE", "CREATE_ASSIGNMENT_EVENT"),
            assignment("HRM_ASSIGNMENT_EVENT_VALIDATE", "VALIDATE_ASSIGNMENT_EVENT"),
            assignment("HRM_ASSIGNMENT_EVENT_SUBMIT", "SUBMIT_ASSIGNMENT_EVENT"),
            assignment("HRM_ASSIGNMENT_EVENT_PUBLISH", "PUBLISH_ASSIGNMENT_EVENT"),
            employee("EMPLOYEE_CHANGE_CREATE", "CREATE_EMPLOYEE_CHANGE_REQUEST",
                    Set.of("EMPLOYEE_SELF_SERVICE", "TEAM_MANAGEMENT", "HR_OPERATIONS")),
            employee("EMPLOYEE_CHANGE_SUBMIT", "SUBMIT_EMPLOYEE_CHANGE_REQUEST",
                    Set.of("EMPLOYEE_SELF_SERVICE", "TEAM_MANAGEMENT", "HR_OPERATIONS")),
            employee("EMPLOYEE_CHANGE_APPLY", "APPLY_EMPLOYEE_CHANGE_REQUEST",
                    Set.of("HR_LIFECYCLE")));

    private final HrisExperienceCommandReceiptRepository repository;
    private final HrisExperienceReceiptAuthority authority;
    private final HcmPopulationRepository populations;
    private final ObjectMapper mapper;

    HrisExperienceCommandReceiptService(
            HrisExperienceCommandReceiptRepository repository,
            HrisExperienceReceiptAuthority authority,
            HcmPopulationRepository populations,
            ObjectMapper mapper) {
        this.repository = repository;
        this.authority = authority;
        this.populations = populations;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public ProjectedReceipt read(UUID receiptId) {
        try {
            HrisExperienceCommandReceiptRepository.ReceiptRecord row = repository.find(
                    currentTenant(), receiptId).orElseThrow(this::notFound);
            Family family = REGISTRY.get(new Tuple(
                    row.commandType(), row.action(), row.targetType()));
            if (family == null || !family.purposes().contains(row.purpose())) {
                throw notFound();
            }
            HrisExperienceReceiptAuthority.CurrentAuthority current =
                    authority.require(row, originPolicy(row));
            requireOrigin(row, current);
            ResultProjection result = validatedResult(row, family);
            HrisExperienceCommandReceiptRepository.TargetEvidence target =
                    target(row.tenantId(), row.targetPublicId() == null
                            ? result.targetPublicId() : row.targetPublicId(), family)
                            .orElse(null);
            requireCurrentTarget(row, current, family, target, result);
            CommandReceiptV1 receipt = new CommandReceiptV1(
                    row.receiptId(), row.commandType(), row.action(), row.subjectPublicId(),
                    row.populationDigest(), row.fieldRevision(), row.purpose(),
                    row.authorizationRevision(), status(row.lifecycleState()),
                    row.correlationId(), row.requestDigest(), row.createdAt());
            URI location = "SUCCEEDED".equals(receipt.status())
                    ? family.location(result.targetPublicId()) : null;
            return new ProjectedReceipt(receipt, location);
        } catch (BaseException exception) {
            throw notFound();
        }
    }

    static int sealedTupleCount() {
        return REGISTRY.size();
    }

    private long currentTenant() {
        try {
            Long tenant = com.dwp.services.people.security.PeopleRequestContext
                    .require().tenantId();
            if (tenant == null || tenant < 1) throw notFound();
            return tenant;
        } catch (IllegalStateException exception) {
            throw notFound();
        }
    }

    private void requireOrigin(
            HrisExperienceCommandReceiptRepository.ReceiptRecord row,
            HrisExperienceReceiptAuthority.CurrentAuthority current) {
        if (row.tenantId() != current.actor().tenantId()
                || !Objects.equals(row.actorPublicId(), current.actor().personPublicId())
                || row.subjectPublicId() == null
                || row.requestDigest() == null || !row.requestDigest().matches(DIGEST)
                || row.populationDigest() == null || !row.populationDigest().matches(DIGEST)
                || row.contextDigest() == null || !row.contextDigest().matches(DIGEST)
                || row.fieldRevision() < 1 || row.authorizationRevision() < 1
                || row.correlationId() == null || row.createdAt() == null) {
            throw notFound();
        }
    }

    private void requireCurrentTarget(
            HrisExperienceCommandReceiptRepository.ReceiptRecord row,
            HrisExperienceReceiptAuthority.CurrentAuthority current,
            Family family,
            HrisExperienceCommandReceiptRepository.TargetEvidence target,
            ResultProjection result) {
        String fields = current.fieldGroups().stream().sorted().toList().toString();
        String currentPopulation = family.kind() == Kind.ASSIGNMENT
                ? HrisExperienceDigests.assignmentPopulationDigest(
                        mapper, current.scopeRevision())
                : HrisExperienceDigests.sha256(current.scopeRevision());
        long currentFields = family.kind() == Kind.ASSIGNMENT
                ? HrisExperienceDigests.assignmentRevision(
                        current.scopeRevision() + '|' + fields)
                : HrisExperienceDigests.employeeChangeRevision(
                        current.scopeRevision() + '|' + fields);
        if (!row.populationDigest().equals(currentPopulation)
                || row.fieldRevision() != currentFields) {
            throw notFound();
        }
        if (target == null) {
            if (!(family.kind() == Kind.ASSIGNMENT
                    && "HRM_ASSIGNMENT_EVENT_CREATE".equals(row.commandType())
                    && row.targetPublicId() == null
                    && !"SUCCEEDED".equals(status(row.lifecycleState())))) {
                throw notFound();
            }
            return;
        }
        if (target.sourceRevision() < 0 || !fieldsAllowed(family, target, current)) {
            throw notFound();
        }
        requireRevisionBinding(row, target, result);
        if (current.self()) {
            if (!current.actor().personPublicId().equals(row.subjectPublicId())) {
                throw notFound();
            }
            return;
        }
        HcmPopulationRepository.PopulationScope scope = current.population().scope();
        boolean organization = scope.tenantWide()
                || target.organizationPublicId() != null
                && scope.organizationIds().contains(target.organizationPublicId());
        boolean prePublishHire = family.kind() == Kind.ASSIGNMENT
                && target.workerId() == null
                && "HIRE".equals(target.eventType())
                && Set.of("CREATE_ASSIGNMENT_EVENT", "VALIDATE_ASSIGNMENT_EVENT",
                        "SUBMIT_ASSIGNMENT_EVENT").contains(row.action());
        boolean worker = scope.tenantWide() || prePublishHire
                || target.workerId() != null
                && populations.containsWorker(row.tenantId(), scope, target.workerId());
        if (!organization || !worker) throw notFound();
    }

    private void requireRevisionBinding(
            HrisExperienceCommandReceiptRepository.ReceiptRecord row,
            HrisExperienceCommandReceiptRepository.TargetEvidence target,
            ResultProjection result) {
        Long expected = row.expectedVersion();
        if (expected == null || expected < 0) throw notFound();
        if (!"SUCCEEDED".equals(status(row.lifecycleState()))) {
            boolean valid = switch (row.action()) {
                case "CREATE_ASSIGNMENT_EVENT", "CREATE_EMPLOYEE_CHANGE_REQUEST" ->
                        target.originTargetRevision() == expected;
                default -> target.sourceRevision() == expected;
            };
            if (!valid) throw notFound();
            return;
        }
        if (result.sourceRevision() == null
                || target.sourceRevision() < result.sourceRevision()) {
            throw notFound();
        }
        switch (row.action()) {
            case "CREATE_ASSIGNMENT_EVENT" -> {
                if (result.sourceRevision() != 0
                        || target.originTargetRevision() != expected) throw notFound();
            }
            case "VALIDATE_ASSIGNMENT_EVENT", "SUBMIT_ASSIGNMENT_EVENT",
                    "PUBLISH_ASSIGNMENT_EVENT" ->
                    requireIncrement(expected, 1, result.sourceRevision());
            case "CREATE_EMPLOYEE_CHANGE_REQUEST" -> {
                if (result.sourceRevision() != 1
                        || target.originTargetRevision() != expected) throw notFound();
            }
            case "SUBMIT_EMPLOYEE_CHANGE_REQUEST" -> {
                requireIncrement(expected, 1, result.sourceRevision());
                if (!Objects.equals(target.approvalRequestId(), result.approvalRequestId())) {
                    throw notFound();
                }
            }
            case "APPLY_EMPLOYEE_CHANGE_REQUEST" -> {
                requireIncrement(expected, 2, result.sourceRevision());
                if (!Objects.equals(target.appliedTargetVersion(),
                        result.appliedTargetVersion())
                        || result.eventId() == null
                        || !repository.exactAppliedOrigin(
                        row.tenantId(), result.eventId(), result.targetPublicId(),
                        row.receiptId(), row.correlationId(), result.sourceRevision(),
                        result.appliedTargetVersion())) {
                    throw notFound();
                }
            }
            default -> throw notFound();
        }
    }

    private void requireIncrement(long expected, int increment, long actual) {
        if (expected > Long.MAX_VALUE - increment || actual != expected + increment) {
            throw notFound();
        }
    }

    private boolean fieldsAllowed(
            Family family,
            HrisExperienceCommandReceiptRepository.TargetEvidence target,
            HrisExperienceReceiptAuthority.CurrentAuthority current) {
        if (family.kind() == Kind.ASSIGNMENT) {
            return current.fieldGroups().contains("EMPLOYMENT");
        }
        for (String field : target.fieldGroups()) {
            boolean allowed = switch (field) {
                case "PERSON_NAME", "PERSON_PROFILE" -> current.self()
                        || current.fieldGroups().contains("DIRECTORY");
                case "EMPLOYMENT", "ASSIGNMENT" ->
                        current.fieldGroups().contains("EMPLOYMENT")
                        || current.fieldGroups().contains("LIMITED_EMPLOYMENT");
                default -> false;
            };
            if (!allowed) return false;
        }
        return true;
    }

    private Optional<HrisExperienceCommandReceiptRepository.TargetEvidence> target(
            long tenantId,
            UUID targetId,
            Family family) {
        if (targetId == null) return Optional.empty();
        return family.kind() == Kind.ASSIGNMENT
                ? repository.assignmentTarget(tenantId, targetId)
                : repository.employeeChangeTarget(tenantId, targetId);
    }

    private ResultProjection validatedResult(
            HrisExperienceCommandReceiptRepository.ReceiptRecord row,
            Family family) {
        String status = status(row.lifecycleState());
        if (!"SUCCEEDED".equals(status)) {
            if (row.resultReference() != null || row.resultCode() != null) throw notFound();
            return new ResultProjection(null, null, null, null, null);
        }
        if (row.resultReference() == null
                || !expectedResultCode(row.action()).equals(row.resultCode())) {
            throw notFound();
        }
        try {
            JsonNode result = mapper.readTree(row.resultReference());
            Set<String> expectedFields = expectedResultFields(row.action());
            Set<String> actualFields = new HashSet<>();
            result.fieldNames().forEachRemaining(actualFields::add);
            if (!result.isObject() || !actualFields.equals(expectedFields)) {
                throw notFound();
            }
            String field = family.kind() == Kind.ASSIGNMENT
                    ? "eventPublicId" : "requestPublicId";
            JsonNode value = result.get(field);
            if (value == null || !value.isTextual()) throw notFound();
            UUID parsed = UUID.fromString(value.textValue());
            if (!parsed.toString().equals(value.textValue())) throw notFound();
            ResultScalars scalars = requireResultScalars(
                    row.action(), result, row.resultCode());
            if (family.kind() == Kind.ASSIGNMENT
                    && "CREATE_ASSIGNMENT_EVENT".equals(row.action())) {
                if (row.targetPublicId() != null) throw notFound();
            } else if (!parsed.equals(row.targetPublicId())) {
                throw notFound();
            }
            return new ResultProjection(
                    parsed, scalars.sourceRevision(), scalars.approvalRequestId(),
                    scalars.appliedTargetVersion(), scalars.eventId());
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw notFound();
        }
    }

    private ResultScalars requireResultScalars(
            String action,
            JsonNode result,
            String resultCode) {
        String version = action.endsWith("ASSIGNMENT_EVENT")
                ? "eventVersion" : "requestVersion";
        JsonNode versionValue = result.get(version);
        long minimum = "CREATE_ASSIGNMENT_EVENT".equals(action) ? 0 : 1;
        if (versionValue == null || !versionValue.isIntegralNumber()
                || versionValue.longValue() < minimum) throw notFound();
        if (action.endsWith("ASSIGNMENT_EVENT")) {
            JsonNode lifecycle = result.get("lifecycleState");
            if (lifecycle == null || !lifecycle.isTextual()
                    || !resultCode.equals(lifecycle.textValue())) throw notFound();
        }
        UUID approvalRequestId = null;
        Long appliedTargetVersion = null;
        UUID eventId = null;
        if ("SUBMIT_EMPLOYEE_CHANGE_REQUEST".equals(action)) {
            approvalRequestId = requireCanonicalUuid(result.get("approvalRequestId"));
        }
        if ("APPLY_EMPLOYEE_CHANGE_REQUEST".equals(action)) {
            eventId = requireCanonicalUuid(result.get("eventId"));
            JsonNode applied = result.get("appliedTargetVersion");
            if (applied == null || !applied.isIntegralNumber()
                    || applied.longValue() < 1) throw notFound();
            appliedTargetVersion = applied.longValue();
        }
        return new ResultScalars(
                versionValue.longValue(), approvalRequestId,
                appliedTargetVersion, eventId);
    }

    private UUID requireCanonicalUuid(JsonNode value) {
        if (value == null || !value.isTextual()) throw notFound();
        try {
            UUID parsed = UUID.fromString(value.textValue());
            if (!parsed.toString().equals(value.textValue())) throw notFound();
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw notFound();
        }
    }

    private String expectedResultCode(String action) {
        return switch (action) {
            case "CREATE_ASSIGNMENT_EVENT" -> "DRAFT";
            case "VALIDATE_ASSIGNMENT_EVENT" -> "VALIDATED";
            case "SUBMIT_ASSIGNMENT_EVENT" -> "PENDING_APPROVAL";
            case "PUBLISH_ASSIGNMENT_EVENT" -> "PUBLISHED";
            case "CREATE_EMPLOYEE_CHANGE_REQUEST" -> "CREATED";
            case "SUBMIT_EMPLOYEE_CHANGE_REQUEST" -> "SUBMITTED";
            case "APPLY_EMPLOYEE_CHANGE_REQUEST" -> "APPLIED";
            default -> throw notFound();
        };
    }

    private Set<String> expectedResultFields(String action) {
        return switch (action) {
            case "CREATE_ASSIGNMENT_EVENT", "VALIDATE_ASSIGNMENT_EVENT",
                    "SUBMIT_ASSIGNMENT_EVENT", "PUBLISH_ASSIGNMENT_EVENT" ->
                    Set.of("eventPublicId", "lifecycleState", "eventVersion");
            case "CREATE_EMPLOYEE_CHANGE_REQUEST" ->
                    Set.of("requestPublicId", "requestVersion");
            case "SUBMIT_EMPLOYEE_CHANGE_REQUEST" ->
                    Set.of("requestPublicId", "requestVersion", "approvalRequestId");
            case "APPLY_EMPLOYEE_CHANGE_REQUEST" ->
                    Set.of("requestPublicId", "requestVersion",
                            "appliedTargetVersion", "eventId");
            default -> throw notFound();
        };
    }

    private String status(String lifecycle) {
        return switch (lifecycle) {
            case "RECEIVED" -> "ACCEPTED";
            case "VALIDATING", "RUNNING" -> "RUNNING";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "REJECTED", "CANCELLED" -> "REJECTED";
            case "FAILED_RETRYABLE", "DEAD" -> "FAILED";
            default -> "RESULT_UNKNOWN";
        };
    }

    private BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }

    private HrisExperienceReceiptAuthority.OriginPolicy originPolicy(
            HrisExperienceCommandReceiptRepository.ReceiptRecord row) {
        return switch (row.action()) {
            case "CREATE_ASSIGNMENT_EVENT" -> origin(
                    "route.hcm.operations.assignment-event-create.action",
                    "hcm.operations.assignment.manage");
            case "VALIDATE_ASSIGNMENT_EVENT" -> origin(
                    "route.hcm.operations.assignment-event-validate.action",
                    "hcm.operations.assignment.manage");
            case "SUBMIT_ASSIGNMENT_EVENT" -> origin(
                    "route.hcm.operations.assignment-event-submit.action",
                    "hcm.operations.assignment.manage");
            case "PUBLISH_ASSIGNMENT_EVENT" -> origin(
                    "route.hcm.operations.assignment-event-publish.action",
                    "hcm.operations.people-change.approve");
            case "CREATE_EMPLOYEE_CHANGE_REQUEST" -> origin(
                    "route.hcm.operations.employee-change-request-create.action",
                    employeeMutationCapability(row.purpose()));
            case "SUBMIT_EMPLOYEE_CHANGE_REQUEST" -> origin(
                    "route.hcm.operations.employee-change-request-submit.action",
                    employeeMutationCapability(row.purpose()));
            case "APPLY_EMPLOYEE_CHANGE_REQUEST" -> origin(
                    "route.hcm.operations.employee-change-request-apply.action",
                    "hcm.operations.people-change.approve");
            default -> throw notFound();
        };
    }

    private HrisExperienceReceiptAuthority.OriginPolicy origin(
            String route,
            String capability) {
        return new HrisExperienceReceiptAuthority.OriginPolicy(
                route, capability, "POST", false);
    }

    private String employeeMutationCapability(String purpose) {
        return switch (purpose) {
            case "EMPLOYEE_SELF_SERVICE" -> "hcm.personal.profile.update";
            case "TEAM_MANAGEMENT" -> "hcm.team.people-change.request";
            case "HR_OPERATIONS" -> "hcm.operations.people.update";
            default -> throw notFound();
        };
    }

    private static Map.Entry<Tuple, Family> assignment(String command, String action) {
        Family family = new Family(Kind.ASSIGNMENT, Set.of("HR_LIFECYCLE"));
        return Map.entry(new Tuple(command, action, "EMPLOYMENT_EVENT"), family);
    }

    private static Map.Entry<Tuple, Family> employee(
            String command,
            String action,
            Set<String> purposes) {
        Family family = new Family(Kind.EMPLOYEE_CHANGE, purposes);
        return Map.entry(new Tuple(command, action, "EMPLOYEE_CHANGE_REQUEST"), family);
    }

    public record ProjectedReceipt(CommandReceiptV1 receipt, URI resultLocation) {
    }

    private record ResultProjection(
            UUID targetPublicId,
            Long sourceRevision,
            UUID approvalRequestId,
            Long appliedTargetVersion,
            UUID eventId) {
    }

    private record ResultScalars(
            long sourceRevision,
            UUID approvalRequestId,
            Long appliedTargetVersion,
            UUID eventId) {
    }

    private record Tuple(String commandType, String action, String targetType) {
    }

    private record Family(Kind kind, Set<String> purposes) {
        Family {
            purposes = Set.copyOf(purposes);
        }

        URI location(UUID target) {
            if (target == null) return null;
            return kind == Kind.ASSIGNMENT
                    ? URI.create("/api/people/v1/hris/assignment-events/" + target)
                    : URI.create("/hr/operations/people/employee-change-requests"
                            + "?requestPublicId=" + target);
        }
    }

    private enum Kind {
        ASSIGNMENT,
        EMPLOYEE_CHANGE
    }
}
