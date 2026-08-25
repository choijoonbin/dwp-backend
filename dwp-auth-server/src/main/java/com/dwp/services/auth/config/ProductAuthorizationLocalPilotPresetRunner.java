package com.dwp.services.auth.config;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.repository.AppAdminPresetRepository;
import com.dwp.services.auth.service.AppAdminPresetRequestService;
import com.dwp.services.auth.service.AppAdminPresetService;
import com.dwp.services.auth.service.AppGovernanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Local-only governed preset fixture used by the browser-verification identity. */
@Component
@Order(120)
public class ProductAuthorizationLocalPilotPresetRunner implements ApplicationRunner {

    static final long TENANT_ID = 1L;
    static final long OWNER_REQUESTER_ID = 5L;
    static final long PRESET_REQUESTER_ID = 23L;
    static final long ACTIVATOR_ID = 14L;
    static final long APPROVER_ID = 15L;
    static final long SUBJECT_ID = 900018L;
    static final String PRESET_CODE = "APPROVAL_DESIGNER";
    static final String RESOURCE_SET_KEY = "RS_APPROVALS";

    private static final String CORRELATION_ID =
            "local-core006-approval-designer-bootstrap";
    private static final String JUSTIFICATION =
            "Local CORE-006 pilot verification for the separated Approvals management surface.";
    private static final String APPROVAL_REASON =
            "Independent local approval for the CORE-006 browser verification fixture.";
    private static final String ACTIVATION_REASON =
            "Independent local activation for the CORE-006 browser verification fixture.";
    private static final Set<String> OPEN_STATES =
            Set.of("PENDING_APPROVAL", "APPROVED", "ACTIVE");
    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProductAuthorizationLocalPilotPresetRunner.class);

    private final boolean enabled;
    private final JdbcTemplate jdbc;
    private final AppGovernanceService governance;
    private final AppAdminPresetRepository repository;
    private final AppAdminPresetRequestService requests;
    private final AppAdminPresetService presets;
    private final Clock clock;

    @Autowired
    public ProductAuthorizationLocalPilotPresetRunner(
            @Value("${dwp.product-authorization.local-pilot-activation.enabled:false}")
            boolean enabled,
            Environment environment,
            JdbcTemplate jdbc,
            AppGovernanceService governance,
            AppAdminPresetRepository repository,
            AppAdminPresetRequestService requests,
            AppAdminPresetService presets) {
        this(enabled, environment, jdbc, governance, repository, requests, presets,
                Clock.systemUTC());
    }

    ProductAuthorizationLocalPilotPresetRunner(
            boolean enabled,
            Environment environment,
            JdbcTemplate jdbc,
            AppGovernanceService governance,
            AppAdminPresetRepository repository,
            AppAdminPresetRequestService requests,
            AppAdminPresetService presets,
            Clock clock) {
        this.enabled = enabled;
        this.jdbc = jdbc;
        this.governance = governance;
        this.repository = repository;
        this.requests = requests;
        this.presets = presets;
        this.clock = clock;
        if (enabled && !local(environment)) {
            throw new IllegalStateException(
                    "CORE-006 local pilot preset bootstrap is forbidden outside DWP_ENVIRONMENT=local.");
        }
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void run(ApplicationArguments arguments) {
        if (!enabled) {
            LOGGER.info("CORE-006 local pilot Approvals preset bootstrap is disabled.");
            return;
        }
        lockBoundary();
        UUID resourceSetId = exactResourceSet();
        requireLocalIdentitiesAndAnchorAuthority(resourceSetId);
        ensureControlResponsibility(
                resourceSetId, APPROVER_ID, "APP_ACCESS_APPROVER",
                PRESET_REQUESTER_ID);
        ensureControlResponsibility(
                resourceSetId, ACTIVATOR_ID, "APP_ACCESS_MANAGER", APPROVER_ID);
        requireExactLifecycleAuthorities(resourceSetId);

        AppGovernanceDtos.AppAdminPresetAssignment assignment = findOpenAssignment();
        if (assignment == null) assignment = requestPreset(resourceSetId);
        assignment = convergePreset(assignment);
        validateActiveAssignment(assignment);
        LOGGER.info(
                "CORE-006 local Approvals designer preset is active: subject={} aggregate={} version={}",
                SUBJECT_ID, assignment.presetAssignmentId(), assignment.version());
    }

    private void ensureControlResponsibility(
            UUID resourceSetId,
            long subjectId,
            String responsibility,
            long decisionActorId) {
        AppGovernanceDtos.Assignment current =
                findOpenControlAssignment(resourceSetId, subjectId, responsibility);
        if (current == null) {
            current = governance.requestAssignment(
                    TENANT_ID, OWNER_REQUESTER_ID,
                    controlCorrelation(responsibility, "request"),
                    new AppGovernanceDtos.CreateAssignmentRequest(
                            "USER", Long.toString(subjectId), responsibility,
                            resourceSetId, OffsetDateTime.now(clock).plusYears(10),
                            JUSTIFICATION));
        }
        if ("PENDING_APPROVAL".equals(current.lifecycleState())) {
            current = governance.decideAssignment(
                    TENANT_ID, decisionActorId,
                    controlCorrelation(responsibility, "approve"),
                    current.assignmentId(),
                    new AppGovernanceDtos.AssignmentDecisionRequest(
                            "APPROVED", APPROVAL_REASON, current.version()));
        }
        validateControlAssignment(
                current, resourceSetId, subjectId, responsibility, decisionActorId);
    }

    private AppGovernanceDtos.AppAdminPresetAssignment convergePreset(
            AppGovernanceDtos.AppAdminPresetAssignment initial) {
        AppGovernanceDtos.AppAdminPresetAssignment current = initial;
        if ("PENDING_APPROVAL".equals(current.lifecycleState())) {
            deferPresetConstraints();
            current = presets.decide(
                    TENANT_ID, APPROVER_ID, CORRELATION_ID,
                    current.presetAssignmentId(),
                    new AppGovernanceDtos.AppAdminPresetDecisionRequest(
                            "APPROVED", APPROVAL_REASON, current.version()));
        }
        if ("APPROVED".equals(current.lifecycleState())) {
            deferPresetConstraints();
            current = presets.activate(
                    TENANT_ID, ACTIVATOR_ID, CORRELATION_ID,
                    current.presetAssignmentId(),
                    new AppGovernanceDtos.ActivateAppAdminPresetRequest(
                            ACTIVATION_REASON, current.version()));
        }
        return current;
    }

    private AppGovernanceDtos.AppAdminPresetAssignment requestPreset(UUID resourceSetId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return requests.requestGoverned(
                TENANT_ID, PRESET_REQUESTER_ID, CORRELATION_ID,
                new AppGovernanceDtos.CreateAppAdminPresetAssignmentRequest(
                        "USER", Long.toString(SUBJECT_ID), PRESET_CODE,
                        resourceSetId, now.plusYears(10), now.plusYears(1), JUSTIFICATION));
    }

    private void requireLocalIdentitiesAndAnchorAuthority(UUID resourceSetId) {
        Long activeUsers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_users
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                   AND user_id IN (?, ?, ?, ?, ?)
                """, Long.class, TENANT_ID, OWNER_REQUESTER_ID,
                PRESET_REQUESTER_ID, APPROVER_ID, ACTIVATOR_ID, SUBJECT_ID);
        if (activeUsers == null || activeUsers != 5L) {
            throw new IllegalStateException(
                    "CORE-006 local pilot actors or subject are missing or inactive.");
        }
        if (!hasResponsibility(OWNER_REQUESTER_ID, "APP_OWNER", resourceSetId)
                || !hasTenantRole(PRESET_REQUESTER_ID, "APP_CATALOG_ADMIN")) {
            throw new IllegalStateException(
                    "CORE-006 local pilot owner or catalog authority anchor is missing.");
        }
    }

    private void requireExactLifecycleAuthorities(UUID resourceSetId) {
        if (!hasResponsibility(APPROVER_ID, "APP_ACCESS_APPROVER", resourceSetId)
                || !hasResponsibility(ACTIVATOR_ID, "APP_ACCESS_MANAGER", resourceSetId)) {
            throw new IllegalStateException(
                    "CORE-006 local pilot actors do not hold the required exact scoped responsibilities.");
        }
    }

    private boolean hasResponsibility(long userId, String responsibility, UUID resourceSetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM com_admin_role_assignments assignment
                     WHERE assignment.tenant_id = ?
                       AND assignment.responsibility_code = ?
                       AND assignment.resource_set_id = ?
                       AND assignment.lifecycle_state = 'ACTIVE'
                       AND (assignment.valid_from IS NULL
                            OR assignment.valid_from <= CURRENT_TIMESTAMP)
                       AND (assignment.valid_to IS NULL
                            OR assignment.valid_to > CURRENT_TIMESTAMP)
                       AND ((assignment.principal_type = 'USER'
                              AND assignment.principal_ref = ?)
                         OR (assignment.principal_type = 'GROUP' AND EXISTS (
                             SELECT 1 FROM com_group_members membership
                              JOIN com_groups access_group
                                ON access_group.tenant_id = membership.tenant_id
                               AND access_group.group_id = membership.group_id
                               AND access_group.status = 'ACTIVE'
                             WHERE membership.tenant_id = assignment.tenant_id
                               AND membership.group_id::text = assignment.principal_ref
                               AND membership.user_id = ?))))
                """, Boolean.class, TENANT_ID, responsibility, resourceSetId,
                Long.toString(userId), userId));
    }

    private boolean hasTenantRole(long userId, String roleCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM com_roles role
                    JOIN com_role_members member
                      ON member.tenant_id = role.tenant_id
                     AND member.role_id = role.role_id
                     AND member.user_id = ?
                   WHERE role.tenant_id = ? AND role.code = ? AND role.status = 'ACTIVE'
                    UNION ALL
                    SELECT 1 FROM com_roles role
                    JOIN com_group_role_assignments assignment
                      ON assignment.tenant_id = role.tenant_id
                     AND assignment.role_id = role.role_id
                    JOIN com_group_members member
                      ON member.tenant_id = assignment.tenant_id
                     AND member.group_id = assignment.group_id
                     AND member.user_id = ?
                    JOIN com_groups access_group
                      ON access_group.tenant_id = member.tenant_id
                     AND access_group.group_id = member.group_id
                     AND access_group.status = 'ACTIVE'
                   WHERE role.tenant_id = ? AND role.code = ? AND role.status = 'ACTIVE'
                     AND assignment.lifecycle_state = 'ACTIVE'
                     AND assignment.assignment_type = 'ACTIVE'
                     AND assignment.scope_type = 'TENANT'
                     AND (assignment.valid_from IS NULL
                          OR assignment.valid_from <= CURRENT_TIMESTAMP)
                     AND (assignment.valid_to IS NULL
                          OR assignment.valid_to > CURRENT_TIMESTAMP))
                """, Boolean.class, userId, TENANT_ID, roleCode,
                userId, TENANT_ID, roleCode));
    }

    private UUID exactResourceSet() {
        List<AppGovernanceDtos.AppAdminPresetResourceSetOption> matches =
                repository.resourceSetOptions(TENANT_ID, "APP.APPROVALS").stream()
                        .filter(value -> RESOURCE_SET_KEY.equals(value.resourceSetKey()))
                        .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "CORE-006 local pilot requires exactly one active RS_APPROVALS resource set.");
        }
        return matches.getFirst().resourceSetId();
    }

    private AppGovernanceDtos.Assignment findOpenControlAssignment(
            UUID resourceSetId, long subjectId, String responsibility) {
        List<AppGovernanceDtos.Assignment> matches =
                governance.dashboard(TENANT_ID, PRESET_REQUESTER_ID).assignments().stream()
                        .filter(value -> resourceSetId.equals(value.resourceSetId()))
                        .filter(value -> "USER".equals(value.principalType()))
                        .filter(value -> Long.toString(subjectId).equals(value.principalRef()))
                        .filter(value -> responsibility.equals(value.responsibilityCode()))
                        .filter(value -> OPEN_STATES.contains(value.lifecycleState()))
                        .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "CORE-006 local pilot found multiple open control responsibilities: "
                            + responsibility);
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private AppGovernanceDtos.AppAdminPresetAssignment findOpenAssignment() {
        List<AppGovernanceDtos.AppAdminPresetAssignment> matches =
                repository.assignments(TENANT_ID).stream()
                        .filter(value -> PRESET_CODE.equals(value.presetCode()))
                        .filter(value -> "USER".equals(value.principalType()))
                        .filter(value -> Long.toString(SUBJECT_ID).equals(value.principalRef()))
                        .filter(value -> RESOURCE_SET_KEY.equals(value.resourceSetKey()))
                        .filter(value -> OPEN_STATES.contains(value.lifecycleState()))
                        .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "CORE-006 local pilot found multiple open Approvals designer aggregates.");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private void validateControlAssignment(
            AppGovernanceDtos.Assignment assignment,
            UUID resourceSetId,
            long subjectId,
            String responsibility,
            long decisionActorId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!"ACTIVE".equals(assignment.lifecycleState())
                || !resourceSetId.equals(assignment.resourceSetId())
                || !"USER".equals(assignment.principalType())
                || !Long.toString(subjectId).equals(assignment.principalRef())
                || !responsibility.equals(assignment.responsibilityCode())
                || assignment.requestedBy() == null
                || assignment.requestedBy() != OWNER_REQUESTER_ID
                || assignment.approvedBy() == null
                || assignment.approvedBy() != decisionActorId
                || assignment.validTo() == null
                || !assignment.validTo().isAfter(now)) {
            throw new IllegalStateException(
                    "CORE-006 local pilot control responsibility is not exactly active: "
                            + responsibility);
        }
    }

    private void validateActiveAssignment(
            AppGovernanceDtos.AppAdminPresetAssignment assignment) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean actorsMatch = assignment.requestedBy() != null
                && assignment.requestedBy() == PRESET_REQUESTER_ID
                && assignment.approvedBy() != null
                && assignment.approvedBy() == APPROVER_ID
                && assignment.activatedBy() != null
                && assignment.activatedBy() == ACTIVATOR_ID;
        boolean dutiesActive = !assignment.duties().isEmpty()
                && assignment.duties().stream()
                        .allMatch(duty -> "ACTIVE".equals(duty.lifecycleState()));
        if (!"ACTIVE".equals(assignment.lifecycleState())
                || !actorsMatch || !dutiesActive
                || assignment.validTo() == null || !assignment.validTo().isAfter(now)) {
            throw new IllegalStateException(
                    "CORE-006 local pilot Approvals designer aggregate is not exactly active.");
        }
    }

    private void lockBoundary() {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", ignored -> { },
                "dwp-local-core006-preset:" + TENANT_ID + ':' + RESOURCE_SET_KEY);
    }

    private void deferPresetConstraints() {
        jdbc.execute("SET CONSTRAINTS trg_scoped_duty_assignment_sod DEFERRED");
        jdbc.execute("SET CONSTRAINTS trg_app_preset_aggregate_consistency DEFERRED");
        jdbc.execute("SET CONSTRAINTS trg_app_preset_duty_consistency DEFERRED");
        jdbc.execute("SET CONSTRAINTS trg_app_preset_responsibility_consistency DEFERRED");
    }

    private static String controlCorrelation(String responsibility, String phase) {
        return CORRELATION_ID + '-' + responsibility.toLowerCase(Locale.ROOT) + '-' + phase;
    }

    private static boolean local(Environment environment) {
        String value = environment.getProperty("DWP_ENVIRONMENT", "");
        return "local".equals(value.trim().toLowerCase(Locale.ROOT));
    }
}
