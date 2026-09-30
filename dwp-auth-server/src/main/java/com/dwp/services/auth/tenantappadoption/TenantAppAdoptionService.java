package com.dwp.services.auth.tenantappadoption;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.AppGovernanceAuthorization;
import com.dwp.services.auth.service.IdentityAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class TenantAppAdoptionService {

    private static final List<String> INCLUDED_OWNERS = List.of(
            "AUTH_PRODUCT_AUTHORIZATION_CATALOG",
            "AUTH_TENANT_APP_INSTALLATION",
            "AUTH_WORKFORCE_SEAT_RESERVATION");
    private static final List<String> EXCLUSIONS = List.of(
            "EXTERNAL_SAAS_PROVISIONING",
            "EXTERNAL_LICENSE_SETTLEMENT",
            "PRODUCT_RUNTIME_HEALTH_AND_RUNNABILITY");

    private final TenantAppAdoptionRepository repository;
    private final IdentityAuditService audit;
    private final AppGovernanceAuthorization authorization;
    private final Clock clock;

    @Autowired
    public TenantAppAdoptionService(
            TenantAppAdoptionRepository repository,
            IdentityAuditService audit,
            JdbcTemplate jdbc) {
        this(repository, audit, new AppGovernanceAuthorization(jdbc), Clock.systemUTC());
    }

    public TenantAppAdoptionService(
            TenantAppAdoptionRepository repository,
            IdentityAuditService audit,
            AppGovernanceAuthorization authorization,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TenantAppAdoptionDtos.AdoptionProjection projection(Long tenantId, Long actorId) {
        AppGovernanceAuthorization.Visibility visibility =
                authorization.requireVisibility(tenantId, actorId);
        ActionAuthority authority = actionAuthority(tenantId, actorId);
        List<TenantAppAdoptionDtos.Installation> installations = repository
                .installations(tenantId).stream()
                .filter(installation -> visibility.queueReader()
                        || visibility.appResourceKeys().contains(installation.appResourceKey()))
                .map(installation -> withActions(installation, actorId, authority))
                .toList();
        return new TenantAppAdoptionDtos.AdoptionProjection(
                clock.instant(), "COMPLETE_INTERNAL_OWNERS", INCLUDED_OWNERS, EXCLUSIONS,
                List.copyOf(authority.owners()), installations);
    }

    @Transactional(readOnly = true)
    public List<TenantAppAdoptionDtos.Assignment> assignments(
            Long tenantId, Long actorId, UUID installationId) {
        AppGovernanceAuthorization.Visibility visibility =
                authorization.requireVisibility(tenantId, actorId);
        ActionAuthority authority = actionAuthority(tenantId, actorId);
        Map<UUID, TenantAppAdoptionDtos.Installation> installations = repository
                .installations(tenantId).stream()
                .filter(installation -> visibility.queueReader()
                        || visibility.appResourceKeys().contains(installation.appResourceKey()))
                .collect(java.util.stream.Collectors.toMap(
                        TenantAppAdoptionDtos.Installation::installationId,
                        installation -> installation));
        if (installationId != null && !installations.containsKey(installationId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return repository.assignments(tenantId, installationId).stream()
                .filter(assignment -> installations.containsKey(assignment.installationId()))
                .map(assignment -> withActions(
                        assignment, installations.get(assignment.installationId()),
                        actorId, authority))
                .toList();
    }

    @Transactional
    public TenantAppAdoptionDtos.Installation createInstallation(
            Long tenantId,
            Long actorId,
            String correlationId,
            TenantAppAdoptionDtos.CreateInstallationRequest request) {
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_OWNER", request.appResourceKey(), correlationId,
                "TENANT_APP_INSTALLATION", request.appResourceKey());
        if (!repository.catalogProductExists(request.productKey())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The product is not present in the active authorization catalog.");
        }
        if (!repository.resourceTemplateExists(request.appResourceKey())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The application resource is not registered for tenant governance.");
        }
        TenantAppAdoptionDtos.Installation installation;
        try {
            installation = repository.insertInstallation(tenantId, actorId, request);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This tenant already has an adoption record for the product.",
                    exception);
        }
        event(tenantId, "INSTALLATION", installation.installationId(),
                "INSTALLATION_DRAFTED", actorId, correlationId, installation.version(), Map.of(
                        "productKey", installation.productKey(),
                        "installationKind", installation.installationKind(),
                        "externalExecutorState", installation.externalExecutorState()));
        audit.success(
                tenantId, actorId, "tenant-app.installation.drafted", "TENANT_APP_INSTALLATION",
                installation.installationId().toString(), correlationId, null,
                Map.of(
                        "productKey", installation.productKey(),
                        "lifecycleState", installation.lifecycleState(),
                        "externalExecutorState", installation.externalExecutorState()));
        return installation;
    }

    @Transactional
    public TenantAppAdoptionDtos.Installation submitInstallation(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID installationId,
            TenantAppAdoptionDtos.VersionedCommand command) {
        TenantAppAdoptionDtos.Installation current = repository.requireInstallation(
                tenantId, installationId);
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_OWNER", current.appResourceKey(), correlationId,
                "TENANT_APP_INSTALLATION", installationId.toString());
        TenantAppAdoptionDtos.Installation changed = repository.transitionInstallation(
                tenantId, installationId, command.version(), "DRAFT", "IN_REVIEW",
                actorId, null, null, clock.instant());
        event(tenantId, "INSTALLATION", installationId, "INSTALLATION_SUBMITTED",
                actorId, correlationId, changed.version(), Map.of());
        return changed;
    }

    @Transactional
    public TenantAppAdoptionDtos.Installation decideInstallation(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID installationId,
            TenantAppAdoptionDtos.DecisionCommand command) {
        TenantAppAdoptionDtos.Installation current = repository.requireInstallation(
                tenantId, installationId);
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_ACCESS_APPROVER", current.appResourceKey(), correlationId,
                "TENANT_APP_INSTALLATION", installationId.toString());
        requireIndependent(current.requestedBy(), actorId, "installation reviewer");
        String next = "APPROVE".equals(command.decision()) ? "APPROVED" : "REJECTED";
        TenantAppAdoptionDtos.Installation changed = repository.transitionInstallation(
                tenantId, installationId, command.version(), "IN_REVIEW", next,
                actorId, command.reason().trim(), null, clock.instant());
        event(tenantId, "INSTALLATION", installationId, "INSTALLATION_" + next,
                actorId, correlationId, changed.version(), Map.of("reason", command.reason().trim()));
        return changed;
    }

    @Transactional
    public TenantAppAdoptionDtos.Installation activateInstallation(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID installationId,
            TenantAppAdoptionDtos.ActivationCommand command) {
        TenantAppAdoptionDtos.Installation current = repository.lockInstallation(
                tenantId, installationId);
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", current.appResourceKey(), correlationId,
                "TENANT_APP_INSTALLATION", installationId.toString());
        if ("EXTERNAL_SERVICE".equals(current.installationKind())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "External provisioning is unavailable. The approved adoption remains non-effective.");
        }
        requireIndependent(current.requestedBy(), actorId, "installation activator");
        requireIndependent(current.approvedBy(), actorId, "installation activator");
        UUID receiptId = UUID.randomUUID();
        TenantAppAdoptionDtos.Installation changed = repository.transitionInstallation(
                tenantId, installationId, command.version(), "APPROVED", "ENABLED",
                actorId, command.reason().trim(), receiptId, clock.instant());
        event(tenantId, "INSTALLATION", installationId, "INSTALLATION_ENABLED",
                actorId, correlationId, changed.version(), Map.of(
                        "activationReceiptId", receiptId.toString(),
                        "runtimeClaim", "AUTH_CONTROL_PLANE_ONLY"));
        audit.success(
                tenantId, actorId, "tenant-app.installation.enabled", "TENANT_APP_INSTALLATION",
                installationId.toString(), correlationId,
                Map.of("lifecycleState", current.lifecycleState(), "version", current.version()),
                Map.of(
                        "lifecycleState", changed.lifecycleState(),
                        "version", changed.version(),
                        "activationReceiptId", receiptId.toString()));
        return changed;
    }

    @Transactional
    public TenantAppAdoptionDtos.Assignment createAssignment(
            Long tenantId,
            Long actorId,
            String correlationId,
            TenantAppAdoptionDtos.CreateAssignmentRequest request) {
        TenantAppAdoptionDtos.Installation installation = repository.requireInstallation(
                tenantId, request.installationId());
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", installation.appResourceKey(),
                correlationId, "TENANT_APP_WORKFORCE_ASSIGNMENT",
                request.installationId().toString());
        if (!"ENABLED".equals(installation.lifecycleState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Workforce seats can be requested only for an enabled internal adoption.");
        }
        if (!repository.activeUserExists(tenantId, request.userId())) {
            throw new BaseException(ErrorCode.NOT_FOUND, "The tenant user was not found.");
        }
        TenantAppAdoptionDtos.Assignment assignment;
        try {
            assignment = repository.insertAssignment(
                    tenantId, actorId, request,
                    "EXTERNAL_SERVICE".equals(installation.installationKind())
                            ? "UNAVAILABLE" : "NOT_REQUIRED");
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The user already has an open assignment for this product.",
                    exception);
        }
        event(tenantId, "WORKFORCE_ASSIGNMENT", assignment.assignmentId(),
                "ASSIGNMENT_REQUESTED", actorId, correlationId, assignment.version(), Map.of(
                        "installationId", assignment.installationId().toString(),
                        "userId", assignment.userId()));
        return assignment;
    }

    @Transactional
    public TenantAppAdoptionDtos.Assignment decideAssignment(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            TenantAppAdoptionDtos.DecisionCommand command) {
        TenantAppAdoptionDtos.Assignment current = repository.requireAssignment(
                tenantId, assignmentId);
        requireIndependent(current.requestedBy(), actorId, "assignment reviewer");
        requireIndependent(current.userId(), actorId, "assignment subject");
        TenantAppAdoptionDtos.Installation installation = repository.lockInstallation(
                tenantId, current.installationId());
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_ACCESS_APPROVER", installation.appResourceKey(),
                correlationId, "TENANT_APP_WORKFORCE_ASSIGNMENT", assignmentId.toString());
        if (!"ENABLED".equals(installation.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The installation is not enabled.");
        }
        String next = "APPROVE".equals(command.decision()) ? "APPROVED" : "DENIED";
        if ("APPROVED".equals(next)
                && installation.seatCapacity() != null
                && installation.reservedSeats() >= installation.seatCapacity()) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The internal seat reservation limit has been reached.");
        }
        TenantAppAdoptionDtos.Assignment changed = repository.transitionAssignment(
                tenantId, assignmentId, command.version(), "PENDING_APPROVAL", next,
                actorId, command.reason().trim(), null, clock.instant());
        event(tenantId, "WORKFORCE_ASSIGNMENT", assignmentId, "ASSIGNMENT_" + next,
                actorId, correlationId, changed.version(), Map.of("reason", command.reason().trim()));
        return changed;
    }

    @Transactional
    public TenantAppAdoptionDtos.Assignment activateAssignment(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            TenantAppAdoptionDtos.ActivationCommand command) {
        TenantAppAdoptionDtos.Assignment current = repository.requireAssignment(
                tenantId, assignmentId);
        requireIndependent(current.requestedBy(), actorId, "assignment activator");
        requireIndependent(current.approvedBy(), actorId, "assignment activator");
        requireIndependent(current.userId(), actorId, "assignment subject");
        TenantAppAdoptionDtos.Installation installation = repository.lockInstallation(
                tenantId, current.installationId());
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", installation.appResourceKey(),
                correlationId, "TENANT_APP_WORKFORCE_ASSIGNMENT", assignmentId.toString());
        if (!"ENABLED".equals(installation.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The installation is not enabled.");
        }
        UUID receiptId = UUID.randomUUID();
        TenantAppAdoptionDtos.Assignment changed = repository.transitionAssignment(
                tenantId, assignmentId, command.version(), "APPROVED", "ACTIVE",
                actorId, command.reason().trim(), receiptId, clock.instant());
        event(tenantId, "WORKFORCE_ASSIGNMENT", assignmentId, "ASSIGNMENT_ACTIVATED",
                actorId, correlationId, changed.version(), Map.of(
                        "activationReceiptId", receiptId.toString(),
                        "settlementState", changed.externalSettlementState()));
        audit.success(
                tenantId, actorId, "tenant-app.assignment.activated",
                "TENANT_APP_WORKFORCE_ASSIGNMENT", assignmentId.toString(), correlationId,
                Map.of("lifecycleState", current.lifecycleState(), "version", current.version()),
                Map.of(
                        "lifecycleState", changed.lifecycleState(),
                        "version", changed.version(),
                        "activationReceiptId", receiptId.toString()));
        return changed;
    }

    @Transactional
    public TenantAppAdoptionDtos.Assignment revokeAssignment(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            TenantAppAdoptionDtos.RevokeCommand command) {
        TenantAppAdoptionDtos.Assignment current = repository.requireAssignment(
                tenantId, assignmentId);
        TenantAppAdoptionDtos.Installation installation = repository.requireInstallation(
                tenantId, current.installationId());
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", installation.appResourceKey(),
                correlationId, "TENANT_APP_WORKFORCE_ASSIGNMENT", assignmentId.toString());
        TenantAppAdoptionDtos.Assignment changed = repository.transitionAssignment(
                tenantId, assignmentId, command.version(), "ACTIVE", "REVOKED",
                actorId, command.reason().trim(), null, clock.instant());
        event(tenantId, "WORKFORCE_ASSIGNMENT", assignmentId, "ASSIGNMENT_REVOKED",
                actorId, correlationId, changed.version(), Map.of("reason", command.reason().trim()));
        return changed;
    }

    private ActionAuthority actionAuthority(Long tenantId, Long actorId) {
        return new ActionAuthority(
                authorization.appResourceKeys(tenantId, actorId, "APP_OWNER"),
                authorization.appResourceKeys(tenantId, actorId, "APP_ACCESS_APPROVER"),
                authorization.appResourceKeys(tenantId, actorId, "APP_ACCESS_MANAGER"));
    }

    private TenantAppAdoptionDtos.Installation withActions(
            TenantAppAdoptionDtos.Installation installation,
            Long actorId,
            ActionAuthority authority) {
        List<String> actions = new java.util.ArrayList<>();
        if ("DRAFT".equals(installation.lifecycleState())
                && authority.owners().contains(installation.appResourceKey())) {
            actions.add("SUBMIT");
        }
        if ("IN_REVIEW".equals(installation.lifecycleState())
                && authority.approvers().contains(installation.appResourceKey())
                && !Objects.equals(installation.requestedBy(), actorId)) {
            actions.add("APPROVE");
            actions.add("REJECT");
        }
        if ("APPROVED".equals(installation.lifecycleState())
                && "INTERNAL_AUTH_CONTROLLED".equals(installation.installationKind())
                && authority.managers().contains(installation.appResourceKey())
                && !Objects.equals(installation.requestedBy(), actorId)
                && !Objects.equals(installation.approvedBy(), actorId)) {
            actions.add("ACTIVATE");
        }
        if ("ENABLED".equals(installation.lifecycleState())
                && authority.managers().contains(installation.appResourceKey())) {
            actions.add("REQUEST_ASSIGNMENT");
        }
        return new TenantAppAdoptionDtos.Installation(
                installation.installationId(), installation.productKey(),
                installation.appResourceKey(), installation.installationKind(),
                installation.lifecycleState(), installation.externalExecutorState(),
                installation.seatCapacity(), installation.reservedSeats(), installation.activeSeats(),
                installation.justification(), installation.requestedBy(), installation.submittedAt(),
                installation.approvedBy(), installation.approvedAt(), installation.decisionReason(),
                installation.activatedBy(), installation.activatedAt(),
                installation.activationReceiptId(), installation.version(), installation.createdAt(),
                installation.updatedAt(), actions);
    }

    private TenantAppAdoptionDtos.Assignment withActions(
            TenantAppAdoptionDtos.Assignment assignment,
            TenantAppAdoptionDtos.Installation installation,
            Long actorId,
            ActionAuthority authority) {
        List<String> actions = new java.util.ArrayList<>();
        if ("PENDING_APPROVAL".equals(assignment.lifecycleState())
                && authority.approvers().contains(installation.appResourceKey())
                && !Objects.equals(assignment.requestedBy(), actorId)
                && !Objects.equals(assignment.userId(), actorId)) {
            actions.add("APPROVE");
            actions.add("REJECT");
        }
        if ("APPROVED".equals(assignment.lifecycleState())
                && authority.managers().contains(installation.appResourceKey())) {
            if (!Objects.equals(assignment.requestedBy(), actorId)
                    && !Objects.equals(assignment.approvedBy(), actorId)
                    && !Objects.equals(assignment.userId(), actorId)) {
                actions.add("ACTIVATE");
            }
            actions.add("REVOKE");
        }
        if ("ACTIVE".equals(assignment.lifecycleState())
                && authority.managers().contains(installation.appResourceKey())) {
            actions.add("REVOKE");
        }
        return new TenantAppAdoptionDtos.Assignment(
                assignment.assignmentId(), assignment.installationId(), assignment.productKey(),
                assignment.userId(), assignment.userDisplayName(), assignment.lifecycleState(),
                assignment.seatQuantity(), assignment.sourceType(),
                assignment.externalSettlementState(), assignment.validFrom(), assignment.validTo(),
                assignment.justification(), assignment.requestedBy(), assignment.approvedBy(),
                assignment.approvedAt(), assignment.decisionReason(), assignment.activatedBy(),
                assignment.activatedAt(), assignment.activationReceiptId(), assignment.revokedBy(),
                assignment.revokedAt(), assignment.revocationReason(), assignment.version(),
                assignment.createdAt(), assignment.updatedAt(), actions);
    }

    private record ActionAuthority(
            Set<String> owners,
            Set<String> approvers,
            Set<String> managers) {
    }

    private void event(
            Long tenantId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Long actorId,
            String correlationId,
            long version,
            Map<String, Object> evidence) {
        repository.appendEvent(
                tenantId, aggregateType, aggregateId, eventType,
                actorId, correlationId, version, evidence);
    }

    private void requireIndependent(Long previousActor, Long actorId, String role) {
        if (previousActor == null || Objects.equals(previousActor, actorId)) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The " + role + " must be independent from earlier actors.");
        }
    }
}
