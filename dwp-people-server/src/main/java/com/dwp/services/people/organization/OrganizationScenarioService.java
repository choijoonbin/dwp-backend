package com.dwp.services.people.organization;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OrganizationScenarioService {

    private final OrganizationScenarioRepository repository;
    private final OrganizationChartService chartService;
    private final OrganizationScenarioDecisionService decisionService;
    private final AuditOutboxRecorder audit;

    public OrganizationScenarioService(
            OrganizationScenarioRepository repository,
            OrganizationChartService chartService,
            OrganizationScenarioDecisionService decisionService,
            AuditOutboxRecorder audit) {
        this.repository = repository;
        this.chartService = chartService;
        this.decisionService = decisionService;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<OrganizationScenarioDtos.Scenario> scenarios() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        return repository.scenarios(actor.tenantId());
    }

    @Transactional
    public OrganizationScenarioDtos.Scenario create(
            OrganizationScenarioDtos.CreateScenarioRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        if (request.effectiveDate().isBefore(request.baselineDate())
                || request.effectiveDate().isBefore(LocalDate.now())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The scenario effective date must not precede its baseline or today.");
        }
        OrganizationChartDtos.OrganizationChart baseline = chartService.get(
                request.baselineDate(), null, 10);
        UUID scenarioId;
        try {
            scenarioId = repository.createScenario(
                    actor.tenantId(), request,
                    OrganizationBaselineFingerprint.compute(baseline), actor.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The scenario key already exists or the request violates the organization model.",
                    exception);
        }
        recordAudit(actor, "organization.scenario.created", scenarioId, correlationId,
                "MEDIUM", 45, Map.of(
                        "scenarioKey", request.scenarioKey(),
                        "baselineDate", request.baselineDate().toString(),
                        "effectiveDate", request.effectiveDate().toString()));
        return requireSummary(actor.tenantId(), scenarioId);
    }

    @Transactional
    public OrganizationScenarioDtos.Scenario cloneScenario(
            UUID sourceScenarioId,
            OrganizationScenarioDtos.CloneScenarioRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ScenarioRecord source = requireScenario(
                actor.tenantId(), sourceScenarioId);
        if (!Set.of("DRAFT", "IN_REVIEW", "APPROVED", "REJECTED")
                .contains(source.lifecycleState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only an active planning scenario can be cloned as an alternative.");
        }
        if (request.effectiveDate().isBefore(source.baselineDate())
                || request.effectiveDate().isBefore(LocalDate.now())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The cloned scenario effective date must not precede its baseline or today.");
        }
        int sourceChangeCount = repository.changes(actor.tenantId(), sourceScenarioId).size();
        UUID scenarioId;
        try {
            scenarioId = repository.cloneScenario(actor.tenantId(), source, request, actor.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The scenario key already exists or the source change set is no longer valid.",
                    exception);
        }
        recordAudit(actor, "organization.scenario.cloned", scenarioId, correlationId,
                "MEDIUM", 45, Map.of(
                        "sourceScenarioId", sourceScenarioId,
                        "sourceLifecycleState", source.lifecycleState(),
                        "changeCount", sourceChangeCount,
                        "effectiveDate", request.effectiveDate().toString()));
        return requireSummary(actor.tenantId(), scenarioId);
    }

    @Transactional
    public OrganizationScenarioDtos.Scenario addMove(
            UUID scenarioId,
            OrganizationScenarioDtos.AddOrganizationMoveRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ScenarioRecord scenario = requireScenario(actor.tenantId(), scenarioId);
        requireOwnerOrAdministrator(actor, scenario);
        requireVersion(scenario.version(), request.version());
        if (!"DRAFT".equals(scenario.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only a draft scenario can be edited.");
        }
        if (request.organizationId().equals(request.newParentOrganizationId())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "An organization cannot report to itself.");
        }
        OrganizationChartDtos.OrganizationChart effectiveChart = chartService.get(
                scenario.effectiveDate(), null, 10);
        if (request.organizationId().equals(effectiveChart.company().organizationId())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The company root cannot be moved.");
        }
        List<OrganizationScenarioRepository.MoveRecord> proposedMoves = new ArrayList<>(
                repository.moves(actor.tenantId(), scenarioId));
        if (proposedMoves.stream().anyMatch(move -> move.organizationId().equals(request.organizationId()))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This scenario already contains a move for the selected organization.");
        }
        proposedMoves.add(new OrganizationScenarioRepository.MoveRecord(
                UUID.randomUUID(), request.organizationId(), request.newParentOrganizationId()));
        validateGraph(effectiveChart, proposedMoves);
        OrganizationScenarioRepository.OrganizationRecord organization = organization(
                actor.tenantId(), request.organizationId(), scenario.effectiveDate());
        OrganizationScenarioRepository.OrganizationRecord parent = organization(
                actor.tenantId(), request.newParentOrganizationId(), scenario.effectiveDate());
        boolean changed = repository.addOrganizationMove(
                actor.tenantId(), scenario, organization, parent,
                actor.userId(), request.version());
        if (!changed) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The scenario changed. Refresh it before adding another move.");
        }
        recordAudit(actor, "organization.scenario.move-added", scenarioId, correlationId,
                "HIGH", 65, Map.of(
                        "organizationId", request.organizationId(),
                        "newParentOrganizationId", request.newParentOrganizationId()));
        return requireSummary(actor.tenantId(), scenarioId);
    }

    @Transactional
    public OrganizationScenarioDtos.Scenario addPositionMove(
            UUID scenarioId,
            OrganizationScenarioDtos.AddPositionMoveRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ScenarioRecord scenario = requireScenario(actor.tenantId(), scenarioId);
        requireOwnerOrAdministrator(actor, scenario);
        requireVersion(scenario.version(), request.version());
        if (!"DRAFT".equals(scenario.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only a draft scenario can be edited.");
        }
        if (request.positionId().equals(request.newParentPositionId())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A position cannot report to itself.");
        }
        OrganizationChartDtos.OrganizationChart effectiveChart = chartService.get(
                scenario.effectiveDate(), null, 10);
        List<OrganizationScenarioRepository.PositionMoveRecord> proposedMoves = new ArrayList<>(
                repository.positionMoves(actor.tenantId(), scenarioId));
        if (proposedMoves.stream().anyMatch(move -> move.positionId().equals(request.positionId()))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This scenario already contains a move for the selected position.");
        }
        ensureNotScheduledForClosure(
                actor.tenantId(), scenarioId, request.positionId(), request.newParentPositionId());
        proposedMoves.add(new OrganizationScenarioRepository.PositionMoveRecord(
                UUID.randomUUID(), request.positionId(), request.newParentPositionId()));
        validatePositionGraph(effectiveChart, proposedMoves);
        OrganizationScenarioRepository.PositionRecord position = position(
                actor.tenantId(), request.positionId(), scenario.effectiveDate());
        OrganizationScenarioRepository.PositionRecord parent = position(
                actor.tenantId(), request.newParentPositionId(), scenario.effectiveDate());
        boolean changed = repository.addPositionMove(
                actor.tenantId(), scenario, position, parent,
                actor.userId(), request.version());
        if (!changed) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The scenario changed. Refresh it before adding another move.");
        }
        recordAudit(actor, "organization.scenario.position-move-added", scenarioId, correlationId,
                "HIGH", 65, Map.of(
                        "positionId", request.positionId(),
                        "newParentPositionId", request.newParentPositionId()));
        return requireSummary(actor.tenantId(), scenarioId);
    }

    @Transactional
    public OrganizationScenarioDtos.Scenario createPosition(
            UUID scenarioId,
            OrganizationScenarioDtos.CreatePositionRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ScenarioRecord scenario = requireScenario(actor.tenantId(), scenarioId);
        requireOwnerOrAdministrator(actor, scenario);
        requireVersion(scenario.version(), request.version());
        if (!"DRAFT".equals(scenario.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only a draft scenario can be edited.");
        }
        if ((request.annualCostAmount() == null) != (request.costCurrency() == null)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Annual cost and currency must be provided together.");
        }
        if (request.availabilityDate().isBefore(scenario.effectiveDate())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Position availability cannot precede the scenario effective date.");
        }
        if (repository.positionKeyExists(
                actor.tenantId(), scenarioId, request.positionKey().trim())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The position key already exists.");
        }
        ensureNotScheduledForClosure(
                actor.tenantId(), scenarioId, request.reportsToPositionId());
        OrganizationChartDtos.OrganizationChart effectiveChart = chartService.get(
                scenario.effectiveDate(), null, 12);
        if (effectiveChart.organizations().stream().noneMatch(
                organization -> organization.organizationId().equals(request.organizationId()))) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Organization not found.");
        }
        if (effectiveChart.positions().stream().noneMatch(
                position -> position.positionId().equals(request.reportsToPositionId()))) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Parent position not found.");
        }
        OrganizationScenarioRepository.OrganizationRecord organization = organization(
                actor.tenantId(), request.organizationId(), scenario.effectiveDate());
        OrganizationScenarioRepository.PositionRecord parent = position(
                actor.tenantId(), request.reportsToPositionId(), scenario.effectiveDate());
        UUID positionId = UUID.randomUUID();
        if (!repository.addPositionCreate(
                actor.tenantId(), scenario, positionId, request, organization, parent,
                actor.userId(), request.version())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The scenario changed. Refresh it before creating a position.");
        }
        recordAudit(actor, "organization.scenario.position-created", scenarioId, correlationId,
                "HIGH", 65, Map.of(
                        "positionId", positionId,
                        "positionKey", request.positionKey().trim().toUpperCase(),
                        "organizationId", request.organizationId(),
                        "budgetedFte", request.budgetedFte()));
        return requireSummary(actor.tenantId(), scenarioId);
    }

    @Transactional
    public OrganizationScenarioDtos.Scenario closePosition(
            UUID scenarioId,
            UUID positionId,
            OrganizationScenarioDtos.ClosePositionRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ScenarioRecord scenario = requireScenario(actor.tenantId(), scenarioId);
        requireOwnerOrAdministrator(actor, scenario);
        requireVersion(scenario.version(), request.version());
        if (!"DRAFT".equals(scenario.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only a draft scenario can be edited.");
        }
        boolean positionAlreadyChanged = repository.positionMoves(actor.tenantId(), scenarioId).stream()
                .anyMatch(move -> move.positionId().equals(positionId));
        boolean positionAlreadyClosing = repository.positionCloses(actor.tenantId(), scenarioId).stream()
                .anyMatch(close -> close.positionId().equals(positionId));
        if (positionAlreadyChanged || positionAlreadyClosing) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A position cannot be moved and closed in the same scenario.");
        }
        OrganizationChartDtos.OrganizationChart proposed = chartService.get(
                scenario.effectiveDate(), null, 12, scenarioId);
        OrganizationChartDtos.Position position = proposed.positions().stream()
                .filter(candidate -> candidate.positionId().equals(positionId))
                .findFirst()
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The position is already closed or is outside the scenario scope."));
        if ("PLANNED".equals(position.status())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Remove the planned position change instead of adding a closure.");
        }
        if (!position.incumbentPersonIds().isEmpty()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A position with an incumbent cannot be closed. Move the assignment first.");
        }
        if (position.subordinatePositionCount() > 0) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A position with subordinate positions cannot be closed. Move them first.");
        }
        if (!"OPEN".equals(position.status())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Only an open, vacant position can be closed by an organization scenario.");
        }
        OrganizationScenarioRepository.PositionPlanningRecord planning = positionPlanning(
                actor.tenantId(), positionId, scenario.effectiveDate());
        if (!repository.addPositionClose(
                actor.tenantId(), scenario, planning, actor.userId(), request.version())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The scenario changed. Refresh it before closing a position.");
        }
        recordAudit(actor, "organization.scenario.position-closed", scenarioId, correlationId,
                "HIGH", 70, Map.of(
                        "positionId", positionId,
                        "positionKey", planning.key(),
                        "budgetedFte", planning.budgetedFte()));
        return requireSummary(actor.tenantId(), scenarioId);
    }

    @Transactional
    public OrganizationScenarioDtos.Scenario removeChange(
            UUID scenarioId,
            UUID changeId,
            long version,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ScenarioRecord scenario = requireScenario(
                actor.tenantId(), scenarioId);
        requireOwnerOrAdministrator(actor, scenario);
        requireVersion(scenario.version(), version);
        if (!"DRAFT".equals(scenario.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only a draft scenario can be edited.");
        }
        try {
            if (!repository.removeChange(
                    actor.tenantId(), scenarioId, changeId, actor.userId(), version)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The scenario changed. Refresh it before removing a change.");
            }
        } catch (IllegalStateException exception) {
            throw new BaseException(ErrorCode.NOT_FOUND, exception.getMessage(), exception);
        }
        recordAudit(actor, "organization.scenario.change-removed", scenarioId, correlationId,
                "HIGH", 60, Map.of("changeId", changeId));
        return requireSummary(actor.tenantId(), scenarioId);
    }

    @Transactional
    public OrganizationScenarioDtos.Scenario submit(
            UUID scenarioId,
            OrganizationScenarioDtos.SubmitScenarioRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ScenarioRecord scenario = requireScenario(actor.tenantId(), scenarioId);
        requireOwnerOrAdministrator(actor, scenario);
        requireVersion(scenario.version(), request.version());
        List<OrganizationScenarioDtos.Change> changes = repository.changes(actor.tenantId(), scenarioId);
        if (changes.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_STATE, "A scenario must contain at least one valid change.");
        }
        OrganizationChartDtos.OrganizationChart effectiveChart = chartService.get(
                scenario.effectiveDate(), null, 10);
        validateGraph(effectiveChart, repository.moves(actor.tenantId(), scenarioId));
        validatePositionGraph(effectiveChart, repository.positionMoves(actor.tenantId(), scenarioId));
        validateProjectedPositionGraph(chartService.get(
                scenario.effectiveDate(), null, 12, scenarioId));
        OrganizationScenarioDtos.DecisionPack validation = decisionService.validateForWorkflow(
                scenarioId, "SUBMIT", correlationId);
        ensureNotBlocked(validation);
        if (!repository.submit(
                actor.tenantId(), scenario, request.reason(), evidenceId(validation),
                actor.userId(), request.version())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The scenario changed or contains no publishable changes.");
        }
        recordAudit(actor, "organization.scenario.submitted", scenarioId, correlationId,
                "HIGH", 70, Map.of(
                        "reason", request.reason(),
                        "changeCount", changes.size(),
                        "validationRunId", evidenceId(validation)));
        return requireSummary(actor.tenantId(), scenarioId);
    }

    @Transactional
    public OrganizationScenarioDtos.Scenario decide(
            UUID scenarioId,
            OrganizationScenarioDtos.DecideScenarioRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ApprovalRecord approval = repository
                .approval(actor.tenantId(), scenarioId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(approval.version(), request.version());
        if (!"PENDING".equals(approval.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The scenario approval is no longer pending.");
        }
        if (!actor.hasAnyRole(
                approval.requiredRoleCode(), "PLATFORM_ADMIN", "TENANT_ADMIN", "ADMIN")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The required approval role is not assigned.");
        }
        if (approval.separationOfDuties() && approval.requestedBy().equals(actor.userId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Separation of duties prevents the scenario requester from approving it.");
        }
        String validationTrigger = "APPROVED".equals(request.decision()) ? "APPROVE" : "REJECT";
        OrganizationScenarioDtos.DecisionPack validation = decisionService.validateForWorkflow(
                scenarioId, validationTrigger, correlationId);
        if ("APPROVED".equals(request.decision())) ensureNotBlocked(validation);
        if (!repository.decide(
                actor.tenantId(), approval, request.decision(), request.reason(),
                evidenceId(validation), actor.userId(), request.version())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The scenario approval changed or expired.");
        }
        recordAudit(actor, "organization.scenario.approval-decided", scenarioId, correlationId,
                "HIGH", 75, Map.of(
                        "decision", request.decision(),
                        "reason", request.reason(),
                        "validationRunId", evidenceId(validation)));
        return requireSummary(actor.tenantId(), scenarioId);
    }

    @Transactional
    public OrganizationScenarioDtos.Scenario publish(
            UUID scenarioId,
            OrganizationScenarioDtos.PublishScenarioRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePlanner(actor);
        OrganizationScenarioRepository.ScenarioRecord scenario = requireScenario(actor.tenantId(), scenarioId);
        requireVersion(scenario.version(), request.version());
        if (!"APPROVED".equals(scenario.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only an approved scenario can be published.");
        }
        OrganizationScenarioDtos.DecisionPack validation = decisionService.validateForWorkflow(
                scenarioId, "PUBLISH", correlationId);
        ensureNotBlocked(validation);
        String currentFingerprint = OrganizationBaselineFingerprint.compute(
                chartService.get(scenario.baselineDate(), null, 10));
        if (!scenario.baselineFingerprint().equals(currentFingerprint)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The live organization changed after this scenario was created. Rebase it before publishing.");
        }
        List<OrganizationScenarioRepository.MoveRecord> moves = repository.moves(
                actor.tenantId(), scenarioId);
        List<OrganizationScenarioRepository.PositionMoveRecord> positionMoves = repository.positionMoves(
                actor.tenantId(), scenarioId);
        List<OrganizationScenarioRepository.PositionCreateRecord> positionCreates = repository.positionCreates(
                actor.tenantId(), scenarioId);
        List<OrganizationScenarioRepository.PositionCloseRecord> positionCloses = repository.positionCloses(
                actor.tenantId(), scenarioId);
        OrganizationChartDtos.OrganizationChart effectiveChart = chartService.get(
                scenario.effectiveDate(), null, 10);
        validateGraph(effectiveChart, moves);
        validatePositionGraph(effectiveChart, positionMoves);
        validateProjectedPositionGraph(chartService.get(
                scenario.effectiveDate(), null, 12, scenarioId));
        try {
            positionCreates.forEach(create -> repository.applyPositionCreate(
                    actor.tenantId(), create, scenario.effectiveDate(),
                    scenario.scenarioKey(), actor.userId()));
            moves.forEach(move -> repository.applyMove(
                    actor.tenantId(), move, scenario.effectiveDate(),
                    scenario.scenarioKey(), actor.userId()));
            positionMoves.forEach(move -> repository.applyPositionMove(
                    actor.tenantId(), move, scenario.effectiveDate(),
                    scenario.scenarioKey(), actor.userId()));
            positionCloses.forEach(close -> repository.applyPositionClose(
                    actor.tenantId(), close, scenario.effectiveDate(), actor.userId()));
        } catch (DataIntegrityViolationException | IllegalStateException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The effective organization changed and the scenario can no longer be published safely.",
                    exception);
        }
        if (!repository.publish(
                actor.tenantId(), scenarioId, evidenceId(validation),
                actor.userId(), request.version())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The scenario changed before publication completed.");
        }
        recordAudit(actor, "organization.scenario.published", scenarioId, correlationId,
                "CRITICAL", 90, Map.of(
                        "scenarioKey", scenario.scenarioKey(),
                        "effectiveDate", scenario.effectiveDate().toString(),
                        "validationRunId", evidenceId(validation),
                        "changeCount", moves.size() + positionMoves.size()
                                + positionCreates.size() + positionCloses.size()));
        return requireSummary(actor.tenantId(), scenarioId);
    }

    private void validateGraph(
            OrganizationChartDtos.OrganizationChart chart,
            List<OrganizationScenarioRepository.MoveRecord> moves) {
        Set<UUID> organizations = chart.organizations().stream()
                .map(OrganizationChartDtos.Organization::organizationId)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, UUID> parents = new HashMap<>();
        chart.organizations().forEach(organization -> {
            if (organization.parentOrganizationId() != null) {
                parents.put(organization.organizationId(), organization.parentOrganizationId());
            }
        });
        for (OrganizationScenarioRepository.MoveRecord move : moves) {
            if (!organizations.contains(move.organizationId()) || !organizations.contains(move.newParentId())) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Every scenario target must belong to the effective organization scope.");
            }
            if (move.organizationId().equals(chart.company().organizationId())) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The company root cannot be moved.");
            }
            parents.put(move.organizationId(), move.newParentId());
        }
        for (UUID organizationId : organizations) {
            Set<UUID> path = new HashSet<>();
            UUID current = organizationId;
            while (current != null) {
                if (!path.add(current)) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "The proposed scenario creates a supervisory cycle.");
                }
                current = parents.get(current);
            }
        }
    }

    private void validatePositionGraph(
            OrganizationChartDtos.OrganizationChart chart,
            List<OrganizationScenarioRepository.PositionMoveRecord> moves) {
        Set<UUID> positions = chart.positions().stream()
                .map(OrganizationChartDtos.Position::positionId)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, UUID> parents = new HashMap<>();
        chart.positions().forEach(position -> {
            if (position.reportsToPositionId() != null) {
                parents.put(position.positionId(), position.reportsToPositionId());
            }
        });
        for (OrganizationScenarioRepository.PositionMoveRecord move : moves) {
            if (!positions.contains(move.positionId()) || !positions.contains(move.newParentId())) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Every position scenario target must belong to the effective organization scope.");
            }
            parents.put(move.positionId(), move.newParentId());
        }
        for (UUID positionId : positions) {
            Set<UUID> path = new HashSet<>();
            UUID current = positionId;
            while (current != null) {
                if (!path.add(current)) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "The proposed scenario creates a position hierarchy cycle.");
                }
                current = parents.get(current);
            }
        }
    }

    private void validateProjectedPositionGraph(OrganizationChartDtos.OrganizationChart chart) {
        Set<UUID> positions = chart.positions().stream()
                .map(OrganizationChartDtos.Position::positionId)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, UUID> parents = new HashMap<>();
        chart.positions().forEach(position -> {
            UUID parentId = position.reportsToPositionId();
            if (parentId == null) return;
            if (!positions.contains(parentId)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The proposed scenario leaves a position reporting to a closed or unavailable parent.");
            }
            parents.put(position.positionId(), parentId);
        });
        for (UUID positionId : positions) {
            Set<UUID> path = new HashSet<>();
            UUID current = positionId;
            while (current != null) {
                if (!path.add(current)) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "The proposed scenario creates a position hierarchy cycle.");
                }
                current = parents.get(current);
            }
        }
    }

    private void ensureNotScheduledForClosure(
            Long tenantId,
            UUID scenarioId,
            UUID... positionIds) {
        Set<UUID> requested = Set.of(positionIds);
        boolean conflict = repository.positionCloses(tenantId, scenarioId).stream()
                .map(OrganizationScenarioRepository.PositionCloseRecord::positionId)
                .anyMatch(requested::contains);
        if (conflict) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A position scheduled for closure cannot be moved or used as a reporting parent.");
        }
    }

    private OrganizationScenarioRepository.OrganizationRecord organization(
            Long tenantId,
            UUID organizationId,
            LocalDate asOf) {
        try {
            return repository.organization(tenantId, organizationId, asOf);
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private OrganizationScenarioRepository.PositionRecord position(
            Long tenantId,
            UUID positionId,
            LocalDate asOf) {
        try {
            return repository.position(tenantId, positionId, asOf);
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private OrganizationScenarioRepository.PositionPlanningRecord positionPlanning(
            Long tenantId,
            UUID positionId,
            LocalDate asOf) {
        try {
            return repository.positionPlanning(tenantId, positionId, asOf);
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private OrganizationScenarioRepository.ScenarioRecord requireScenario(
            Long tenantId,
            UUID scenarioId) {
        return repository.scenario(tenantId, scenarioId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private OrganizationScenarioDtos.Scenario requireSummary(Long tenantId, UUID scenarioId) {
        return repository.scenarios(tenantId).stream()
                .filter(item -> item.scenarioId().equals(scenarioId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void requirePlanner(PeopleRequestContext.Actor actor) {
        if (!actor.hasAnyRole(
                "HR_ADMIN", "PEOPLE_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN", "ADMIN")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Organization design permission is required.");
        }
    }

    private void requireOwnerOrAdministrator(
            PeopleRequestContext.Actor actor,
            OrganizationScenarioRepository.ScenarioRecord scenario) {
        if (!scenario.ownerUserId().equals(actor.userId())
                && !actor.hasAnyRole("HR_ADMIN", "PLATFORM_ADMIN", "ADMIN")) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Only the owner can edit this scenario.");
        }
    }

    private void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The scenario changed. Refresh and try again.");
        }
    }

    private void ensureNotBlocked(OrganizationScenarioDtos.DecisionPack decision) {
        if (decision.blockingIssueCount() > 0) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The organization decision pack contains blocking validation findings.");
        }
    }

    private UUID evidenceId(OrganizationScenarioDtos.DecisionPack decision) {
        if (decision.validationRunId() == null) {
            throw new IllegalStateException("Persisted organization validation evidence is required.");
        }
        return decision.validationRunId();
    }

    private void recordAudit(
            PeopleRequestContext.Actor actor,
            String action,
            UUID scenarioId,
            String correlationId,
            String severity,
            int riskScore,
            Map<String, Object> metadata) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity(severity)
                .riskScore(riskScore)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("organization-design")
                .targetType("ORGANIZATION_SCENARIO")
                .targetId(scenarioId.toString())
                .correlationId(correlationId)
                .metadata(metadata)
                .retentionClass("EXTENDED")
                .build());
    }
}
