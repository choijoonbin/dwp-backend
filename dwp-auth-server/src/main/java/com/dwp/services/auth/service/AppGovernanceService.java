package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AppGovernanceService {

    private static final String CATALOG_ADMIN = "APP_CATALOG_ADMIN";
    private static final Set<String> CONTROL_PLANE_RESPONSIBILITIES = Set.of(
            "APP_OWNER", "APP_ACCESS_MANAGER",
            "APP_ACCESS_APPROVER", "APP_ACCESS_REVIEWER");

    private final JdbcTemplate jdbc;
    private final IdentityAuditService audit;
    private final AppGovernanceAuthorization authorization;
    private final AppGovernanceDenialAudit denials;
    private final AppGovernanceAssignmentStore assignmentStore;

    public AppGovernanceService(JdbcTemplate jdbc, IdentityAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.authorization = new AppGovernanceAuthorization(jdbc);
        this.denials = new AppGovernanceDenialAudit(audit);
        this.assignmentStore = new AppGovernanceAssignmentStore(jdbc);
    }

    @Transactional(readOnly = true)
    public AppGovernanceDtos.Dashboard dashboard(Long tenantId, Long actorId) {
        Set<String> roles = tenantRoles(tenantId, actorId);
        boolean catalogAdmin = roles.contains(CATALOG_ADMIN);
        List<AppGovernanceDtos.ResourceRole> actorScopes = resourceRoles(tenantId, actorId)
                .stream()
                .filter(scope -> CONTROL_PLANE_RESPONSIBILITIES.contains(
                        scope.responsibilityCode()))
                .toList();
        if (!catalogAdmin && actorScopes.isEmpty()) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        List<AppGovernanceDtos.ResourceSet> sets = resourceSets(tenantId);
        List<AppGovernanceDtos.Assignment> assignments = assignments(tenantId);
        if (!catalogAdmin) {
            Set<UUID> visible = actorScopes.stream()
                    .map(AppGovernanceDtos.ResourceRole::resourceSetId)
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
            sets = sets.stream().filter(value -> visible.contains(value.resourceSetId())).toList();
            assignments = assignments.stream()
                    .filter(value -> visible.contains(value.resourceSetId()))
                    .toList();
        }
        Set<UUID> firstApproverBootstrapEligibleIds =
                assignmentStore.firstApproverBootstrapEligibleAssignmentIds(
                        tenantId, actorId, catalogAdmin);
        assignments = assignments.stream()
                .map(value -> value.withFirstApproverBootstrapEligible(
                        firstApproverBootstrapEligibleIds.contains(value.assignmentId())))
                .toList();
        boolean canComposeResponsibilities = catalogAdmin || actorScopes.stream()
                .anyMatch(value -> "APP_OWNER".equals(value.responsibilityCode()));
        return new AppGovernanceDtos.Dashboard(
                metrics(tenantId, sets, assignments),
                canComposeResponsibilities ? responsibilities() : List.of(),
                canComposeResponsibilities ? principals(tenantId) : List.of(),
                sets, assignments);
    }

    @Transactional(readOnly = true)
    public List<AppGovernanceDtos.ResourceRole> resourceRoles(Long tenantId, Long userId) {
        return jdbc.query("""
                SELECT assignment.responsibility_code,
                       member.resource_type,
                       member.resource_key,
                       assignment.resource_set_id,
                       resource_set.resource_set_key,
                       assignment.valid_to
                  FROM com_admin_role_assignments assignment
                  JOIN com_admin_resource_sets resource_set
                    ON resource_set.tenant_id = assignment.tenant_id
                   AND resource_set.resource_set_id = assignment.resource_set_id
                   AND resource_set.lifecycle_state = 'ACTIVE'
                  JOIN com_admin_resource_set_members member
                    ON member.tenant_id = assignment.tenant_id
                   AND member.resource_set_id = assignment.resource_set_id
                   AND member.lifecycle_state = 'ACTIVE'
                 WHERE assignment.tenant_id = ?
                   AND assignment.lifecycle_state = 'ACTIVE'
                   AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
                   AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
                   AND (
                       (assignment.principal_type = 'USER' AND assignment.principal_ref = ?)
                       OR (assignment.principal_type = 'GROUP' AND EXISTS (
                           SELECT 1
                             FROM com_group_members membership
                             JOIN com_groups access_group
                               ON access_group.tenant_id = membership.tenant_id
                              AND access_group.group_id = membership.group_id
                              AND access_group.status = 'ACTIVE'
                            WHERE membership.tenant_id = assignment.tenant_id
                              AND membership.group_id::text = assignment.principal_ref
                              AND membership.user_id = ?)))
                 ORDER BY assignment.responsibility_code, member.resource_key
                """, this::resourceRole, tenantId, userId.toString(), userId);
    }

    @Transactional
    public AppGovernanceDtos.ResourceSet createResourceSet(
            Long tenantId,
            Long actorId,
            String correlationId,
            AppGovernanceDtos.CreateResourceSetRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-resource-set.create-denied",
                "APP_RESOURCE_SET", "CREATE",
                () -> createResourceSetInternal(
                        tenantId, actorId, correlationId, request));
    }

    private AppGovernanceDtos.ResourceSet createResourceSetInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            AppGovernanceDtos.CreateResourceSetRequest request) {
        authorization.requireCatalogAdmin(
                tenantId, actorId, correlationId, "APP_RESOURCE_SET", "CREATE");
        List<AppGovernanceDtos.ResourceMember> resources =
                requireAppResources(tenantId, request.resourceKeys());
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO com_admin_resource_sets (
                        resource_set_id, tenant_id, resource_set_key, name, description,
                        resource_type, lifecycle_state, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, 'APP', 'ACTIVE', ?, ?)
                    """, id, tenantId, normalizeKey(request.key()), request.name().trim(),
                    trimToNull(request.description()), actorId, actorId);
            replaceMembers(tenantId, id, resources, actorId);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT, "The application resource set already exists.", exception);
        }
        audit.success(tenantId, actorId, "access.app-resource-set.created",
                "APP_RESOURCE_SET", id.toString(), correlationId, null,
                snapshot("resourceSetId", id, "key", normalizeKey(request.key()),
                        "resources", request.resourceKeys()));
        return requireResourceSet(tenantId, id);
    }

    @Transactional
    public AppGovernanceDtos.ResourceSet updateResourceSet(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID resourceSetId,
            AppGovernanceDtos.UpdateResourceSetRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-resource-set.update-denied",
                "APP_RESOURCE_SET", resourceSetId.toString(),
                () -> updateResourceSetInternal(
                        tenantId, actorId, correlationId, resourceSetId, request));
    }

    private AppGovernanceDtos.ResourceSet updateResourceSetInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID resourceSetId,
            AppGovernanceDtos.UpdateResourceSetRequest request) {
        authorization.requireCatalogAdmin(
                tenantId, actorId, correlationId, "APP_RESOURCE_SET",
                resourceSetId.toString());
        AppGovernanceDtos.ResourceSet before = requireResourceSet(tenantId, resourceSetId);
        assignmentStore.lockResourceSet(tenantId, resourceSetId);
        before = requireResourceSet(tenantId, resourceSetId);
        if (before.version() != request.version()) conflict("The resource set changed. Refresh and retry.");
        Long assignments = jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_admin_role_assignments
                 WHERE tenant_id = ? AND resource_set_id = ?
                   AND lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                """, Long.class, tenantId, resourceSetId);
        if (assignments != null && assignments > 0) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Revoke or decide active assignments before changing the resource boundary.");
        }
        List<AppGovernanceDtos.ResourceMember> resources =
                requireAppResources(tenantId, request.resourceKeys());
        int changed = jdbc.update("""
                UPDATE com_admin_resource_sets
                   SET name = ?, description = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND resource_set_id = ? AND version = ?
                """, request.name().trim(), trimToNull(request.description()), actorId,
                tenantId, resourceSetId, request.version());
        if (changed != 1) conflict("The resource set changed. Refresh and retry.");
        jdbc.update("""
                DELETE FROM com_admin_resource_set_members
                 WHERE tenant_id = ? AND resource_set_id = ?
                """, tenantId, resourceSetId);
        replaceMembers(tenantId, resourceSetId, resources, actorId);
        audit.success(tenantId, actorId, "access.app-resource-set.updated",
                "APP_RESOURCE_SET", resourceSetId.toString(), correlationId,
                snapshot("version", before.version(), "resources", before.resources()),
                snapshot("version", before.version() + 1, "resources", request.resourceKeys()));
        return requireResourceSet(tenantId, resourceSetId);
    }

    @Transactional
    public AppGovernanceDtos.Assignment requestAssignment(
            Long tenantId,
            Long actorId,
            String correlationId,
            AppGovernanceDtos.CreateAssignmentRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-responsibility.request-denied",
                "APP_ADMIN_ASSIGNMENT", "CREATE",
                () -> requestAssignmentInternal(
                        tenantId, actorId, correlationId, request));
    }

    private AppGovernanceDtos.Assignment requestAssignmentInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            AppGovernanceDtos.CreateAssignmentRequest request) {
        String responsibility = request.responsibilityCode().toUpperCase(Locale.ROOT);
        String principalType = request.principalType().toUpperCase(Locale.ROOT);
        String principalRef = request.principalRef().trim();
        if (!CONTROL_PLANE_RESPONSIBILITIES.contains(responsibility)) {
            denied(tenantId, actorId, correlationId, "APP_ADMIN_ASSIGNMENT", "CREATE",
                    "PRESET_WORKFLOW_REQUIRED_FOR_SPECIALIST_RESPONSIBILITY");
        }
        AppGovernanceDtos.ResourceSet resourceSet = requireResourceSet(tenantId, request.resourceSetId());
        requireAssignmentRequestAuthority(
                tenantId, actorId, correlationId, responsibility, request.resourceSetId());
        assignmentStore.requirePrincipal(tenantId, principalType, principalRef);
        validateWindow(request.validTo());
        assignmentStore.lockAssignmentBoundary(
                tenantId, principalType, principalRef, resourceSet);
        resourceSet = requireResourceSet(tenantId, request.resourceSetId());
        assignmentStore.lockAssignmentBoundary(
                tenantId, principalType, principalRef, resourceSet);
        requireAssignmentRequestAuthority(
                tenantId, actorId, correlationId, responsibility, request.resourceSetId());
        assignmentStore.ensureNoDutyConflict(tenantId, principalType, principalRef,
                responsibility, request.resourceSetId(), null, request.validTo(), null);
        if (assignmentStore.hasOpenAssignment(tenantId, principalType, principalRef,
                responsibility, request.resourceSetId())) {
            conflict("An active or pending assignment already exists for this principal and scope.");
        }
        UUID assignmentId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime reviewDue = now.plusDays(180);
        if (request.validTo() != null && request.validTo().isBefore(reviewDue)) {
            reviewDue = request.validTo();
        }
        try {
            jdbc.update("""
                    INSERT INTO com_admin_role_assignments (
                        admin_role_assignment_id, tenant_id, principal_type, principal_ref,
                        responsibility_code, resource_set_id, assignment_source,
                        lifecycle_state, valid_from, valid_to, review_due_at, justification,
                        created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, 'MANUAL', 'PENDING_APPROVAL',
                            NULL, ?, ?, ?, ?, ?)
                    """, assignmentId, tenantId, principalType, principalRef,
                    responsibility, request.resourceSetId(), request.validTo(), reviewDue,
                    request.justification().trim(), actorId, actorId);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An active or pending assignment already exists for this principal and scope.",
                    exception);
        }
        audit.success(tenantId, actorId, "access.app-responsibility.requested",
                "APP_ADMIN_ASSIGNMENT", assignmentId.toString(), correlationId, null,
                snapshot("responsibility", responsibility, "resourceSet", resourceSet.key(),
                        "principalType", principalType, "principalRef", principalRef));
        return requireAssignment(tenantId, assignmentId);
    }

    private void requireAssignmentRequestAuthority(
            Long tenantId,
            Long actorId,
            String correlationId,
            String responsibility,
            UUID resourceSetId) {
        if ("APP_OWNER".equals(responsibility)) {
            authorization.requireCatalogAdmin(
                    tenantId, actorId, correlationId, "APP_ADMIN_ASSIGNMENT", "CREATE");
            return;
        }
        authorization.requirePresetRequester(
                tenantId, actorId, resourceSetId, correlationId);
    }

    @Transactional
    public AppGovernanceDtos.Assignment decideAssignment(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.AssignmentDecisionRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-responsibility.decision-denied",
                "APP_ADMIN_ASSIGNMENT", assignmentId.toString(),
                () -> decideAssignmentInternal(
                        tenantId, actorId, correlationId, assignmentId, request));
    }

    private AppGovernanceDtos.Assignment decideAssignmentInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.AssignmentDecisionRequest request) {
        AppGovernanceDtos.Assignment before = requireAssignment(tenantId, assignmentId);
        if (!CONTROL_PLANE_RESPONSIBILITIES.contains(before.responsibilityCode())) {
            denied(tenantId, actorId, correlationId, "APP_ADMIN_ASSIGNMENT",
                    assignmentId.toString(),
                    "PRESET_WORKFLOW_REQUIRED_FOR_SPECIALIST_RESPONSIBILITY");
        }
        requireAssignmentDecisionAuthority(
                tenantId, actorId, correlationId, assignmentId, before);
        AppGovernanceDtos.ResourceSet resourceSet =
                requireResourceSet(tenantId, before.resourceSetId());
        assignmentStore.lockAssignmentBoundary(
                tenantId, before.principalType(), before.principalRef(), resourceSet);
        before = requireAssignment(tenantId, assignmentId);
        requireAssignmentDecisionAuthority(
                tenantId, actorId, correlationId, assignmentId, before);
        if (before.version() != request.version()) conflict("The assignment changed. Refresh and retry.");
        if (!"PENDING_APPROVAL".equals(before.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only pending assignments can be decided.");
        }
        if (actorId.equals(before.requestedBy())
                || assignmentStore.principalIncludesUser(
                        tenantId, before.principalType(), before.principalRef(), actorId)) {
            denied(tenantId, actorId, correlationId, "APP_ADMIN_ASSIGNMENT",
                    assignmentId.toString(), "SELF_APPROVAL_FORBIDDEN");
        }
        String decision = request.decision().toUpperCase(Locale.ROOT);
        if ("APPROVED".equals(decision)) {
            validateWindow(before.validTo());
            assignmentStore.ensureNoDutyConflict(
                    tenantId, before.principalType(), before.principalRef(),
                    before.responsibilityCode(), before.resourceSetId(),
                    before.validFrom(), before.validTo(), assignmentId);
        }
        String state = "APPROVED".equals(decision) ? "ACTIVE" : "DENIED";
        int changed = jdbc.update("""
                UPDATE com_admin_role_assignments
                   SET lifecycle_state = ?,
                       valid_from = CASE WHEN ? = 'ACTIVE'
                                         THEN COALESCE(valid_from, CURRENT_TIMESTAMP)
                                         ELSE valid_from END,
                       approved_by = ?, approved_at = CURRENT_TIMESTAMP,
                       decision_reason = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND admin_role_assignment_id = ?
                   AND lifecycle_state = 'PENDING_APPROVAL' AND version = ?
                """, state, state, actorId, request.reason().trim(), actorId,
                tenantId, assignmentId, request.version());
        if (changed != 1) conflict("The assignment changed. Refresh and retry.");
        assignmentStore.invalidatePrincipal(
                tenantId, before.principalType(), before.principalRef(), actorId);
        audit.success(tenantId, actorId,
                "APPROVED".equals(decision)
                        ? "access.app-responsibility.approved"
                        : "access.app-responsibility.denied",
                "APP_ADMIN_ASSIGNMENT", assignmentId.toString(), correlationId,
                snapshot("state", before.lifecycleState()), snapshot("state", state));
        return requireAssignment(tenantId, assignmentId);
    }

    @Transactional
    public AppGovernanceDtos.Assignment revokeAssignment(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.RevokeAssignmentRequest request) {
        return denials.capture(
                tenantId, actorId, correlationId,
                "access.app-responsibility.revocation-denied",
                "APP_ADMIN_ASSIGNMENT", assignmentId.toString(),
                () -> revokeAssignmentInternal(
                        tenantId, actorId, correlationId, assignmentId, request));
    }

    private AppGovernanceDtos.Assignment revokeAssignmentInternal(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.RevokeAssignmentRequest request) {
        AppGovernanceDtos.Assignment before = requireAssignment(tenantId, assignmentId);
        requireAssignmentRevocationAuthority(
                tenantId, actorId, correlationId, assignmentId, before);
        AppGovernanceDtos.ResourceSet resourceSet =
                requireResourceSet(tenantId, before.resourceSetId());
        assignmentStore.lockAssignmentBoundary(
                tenantId, before.principalType(), before.principalRef(), resourceSet);
        before = requireAssignment(tenantId, assignmentId);
        requireAssignmentRevocationAuthority(
                tenantId, actorId, correlationId, assignmentId, before);
        if (before.version() != request.version()) conflict("The assignment changed. Refresh and retry.");
        if (!"ACTIVE".equals(before.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only active assignments can be revoked.");
        }
        if ("APP_OWNER".equals(before.responsibilityCode())
                && assignmentStore.wouldRemoveFinalEffectiveOwner(
                        tenantId, before.resourceSetId(), assignmentId)) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "Assign and approve a replacement owner before revoking the final owner.");
        }
        int changed = jdbc.update("""
                UPDATE com_admin_role_assignments
                   SET lifecycle_state = 'REVOKED', revoked_by = ?,
                       revoked_at = CURRENT_TIMESTAMP, revocation_reason = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND admin_role_assignment_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, actorId, request.reason().trim(), actorId,
                tenantId, assignmentId, request.version());
        if (changed != 1) conflict("The assignment changed. Refresh and retry.");
        assignmentStore.invalidatePrincipal(
                tenantId, before.principalType(), before.principalRef(), actorId);
        audit.success(tenantId, actorId, "access.app-responsibility.revoked",
                "APP_ADMIN_ASSIGNMENT", assignmentId.toString(), correlationId,
                snapshot("state", "ACTIVE"), snapshot("state", "REVOKED"));
        return requireAssignment(tenantId, assignmentId);
    }

    @Transactional
    public int expireDueAssignments(int batchSize) {
        int safeBatchSize = Math.min(1000, Math.max(1, batchSize));
        List<ExpiredAssignment> expired = jdbc.query("""
                WITH due AS (
                    SELECT admin_role_assignment_id
                      FROM com_admin_role_assignments
                     WHERE lifecycle_state = 'ACTIVE'
                       AND valid_to IS NOT NULL
                       AND valid_to <= CURRENT_TIMESTAMP
                     ORDER BY valid_to, admin_role_assignment_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                )
                UPDATE com_admin_role_assignments assignment
                   SET lifecycle_state = 'EXPIRED',
                       revoked_at = CURRENT_TIMESTAMP,
                       revocation_reason = 'VALIDITY_WINDOW_ELAPSED',
                       version = assignment.version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = NULL
                  FROM due
                 WHERE assignment.admin_role_assignment_id = due.admin_role_assignment_id
                   AND assignment.lifecycle_state = 'ACTIVE'
                   AND assignment.valid_to <= CURRENT_TIMESTAMP
                RETURNING assignment.admin_role_assignment_id, assignment.tenant_id,
                          assignment.principal_type, assignment.principal_ref,
                          assignment.valid_to
                """, (result, ignored) -> new ExpiredAssignment(
                result.getObject("admin_role_assignment_id", UUID.class),
                result.getLong("tenant_id"), result.getString("principal_type"),
                result.getString("principal_ref"),
                result.getObject("valid_to", OffsetDateTime.class)), safeBatchSize);
        Map<String, ExpiredAssignment> affectedPrincipals = new LinkedHashMap<>();
        for (ExpiredAssignment assignment : expired) {
            affectedPrincipals.put(
                    assignment.tenantId() + ":" + assignment.principalType()
                            + ":" + assignment.principalRef(),
                    assignment);
            audit.success(assignment.tenantId(), null,
                    "access.app-responsibility.expired", "APP_ADMIN_ASSIGNMENT",
                    assignment.assignmentId().toString(), "system:app-responsibility-expiry",
                    snapshot("state", "ACTIVE", "validTo", assignment.validTo()),
                    snapshot("state", "EXPIRED"));
        }
        affectedPrincipals.values().forEach(assignment -> assignmentStore.invalidatePrincipal(
                assignment.tenantId(), assignment.principalType(), assignment.principalRef(), null));
        return expired.size();
    }

    private List<AppGovernanceDtos.Responsibility> responsibilities() {
        return jdbc.query("""
                SELECT responsibility_code, display_name, description, risk_tier, sort_order
                  FROM sys_admin_responsibility_catalog
                 WHERE lifecycle_state = 'ACTIVE'
                 ORDER BY sort_order
                """, (result, ignored) -> new AppGovernanceDtos.Responsibility(
                result.getString("responsibility_code"), result.getString("display_name"),
                result.getString("description"), result.getString("risk_tier"),
                result.getInt("sort_order"))).stream()
                .filter(value -> CONTROL_PLANE_RESPONSIBILITIES.contains(value.code()))
                .toList();
    }

    private void requireAssignmentDecisionAuthority(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.Assignment assignment) {
        if ("APP_OWNER".equals(assignment.responsibilityCode())) {
            authorization.requireCatalogAdmin(
                    tenantId, actorId, correlationId,
                    "APP_ADMIN_ASSIGNMENT", assignmentId.toString());
            return;
        }
        if (assignmentStore.isFirstApproverBootstrapEligible(
                tenantId, actorId,
                tenantRoles(tenantId, actorId).contains(CATALOG_ADMIN), assignmentId)) {
            authorization.requireCatalogAdmin(
                    tenantId, actorId, correlationId,
                    "APP_ADMIN_ASSIGNMENT", assignmentId.toString());
            return;
        }
        authorization.requireScopedResponsibility(
                tenantId, actorId, "APP_ACCESS_APPROVER", assignment.resourceSetId(),
                correlationId, "APP_ADMIN_ASSIGNMENT", assignmentId.toString());
    }

    private void requireAssignmentRevocationAuthority(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID assignmentId,
            AppGovernanceDtos.Assignment assignment) {
        if ("APP_OWNER".equals(assignment.responsibilityCode())) {
            authorization.requireCatalogAdmin(
                    tenantId, actorId, correlationId,
                    "APP_ADMIN_ASSIGNMENT", assignmentId.toString());
            return;
        }
        authorization.requireScopedResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", assignment.resourceSetId(),
                correlationId, "APP_ADMIN_ASSIGNMENT", assignmentId.toString());
    }

    private List<AppGovernanceDtos.Principal> principals(Long tenantId) {
        return jdbc.query("""
                SELECT principal_type, principal_ref, display_name, detail
                  FROM (
                      SELECT 'USER' AS principal_type, user_id::text AS principal_ref,
                             display_name, COALESCE(job_title, '') AS detail
                        FROM com_users
                       WHERE tenant_id = ? AND status = 'ACTIVE'
                      UNION ALL
                      SELECT 'GROUP', group_id::text, display_name,
                             COALESCE(description, '')
                        FROM com_groups
                       WHERE tenant_id = ? AND status = 'ACTIVE'
                  ) principal
                 ORDER BY principal_type DESC, display_name
                 LIMIT 500
                """, (result, ignored) -> new AppGovernanceDtos.Principal(
                result.getString("principal_type"), result.getString("principal_ref"),
                result.getString("display_name"), result.getString("detail")), tenantId, tenantId);
    }

    private List<AppGovernanceDtos.ResourceSet> resourceSets(Long tenantId) {
        Map<UUID, List<AppGovernanceDtos.ResourceMember>> bySet = new LinkedHashMap<>();
        jdbc.query("""
                SELECT member.resource_set_id, member.resource_type, member.resource_key,
                       resource.name AS resource_name
                  FROM com_admin_resource_set_members member
                  JOIN com_resources resource
                    ON resource.tenant_id = member.tenant_id
                   AND resource.type = member.resource_type
                   AND resource.key = member.resource_key
                 WHERE member.tenant_id = ? AND member.lifecycle_state = 'ACTIVE'
                 ORDER BY member.resource_key
                """, result -> {
                    bySet.computeIfAbsent(
                                    result.getObject("resource_set_id", UUID.class),
                                    ignored -> new ArrayList<>())
                            .add(new AppGovernanceDtos.ResourceMember(
                                    result.getString("resource_type"),
                                    result.getString("resource_key"),
                                    result.getString("resource_name")));
                }, tenantId);
        return jdbc.query("""
                SELECT resource_set_id, resource_set_key, name, description,
                       lifecycle_state, version
                  FROM com_admin_resource_sets
                 WHERE tenant_id = ? AND lifecycle_state = 'ACTIVE'
                 ORDER BY name, resource_set_key
                """, (result, ignored) -> new AppGovernanceDtos.ResourceSet(
                result.getObject("resource_set_id", UUID.class), result.getString("resource_set_key"),
                result.getString("name"), result.getString("description"),
                result.getString("lifecycle_state"), result.getLong("version"),
                List.copyOf(bySet.getOrDefault(
                        result.getObject("resource_set_id", UUID.class), List.of()))), tenantId);
    }

    private List<AppGovernanceDtos.Assignment> assignments(Long tenantId) {
        return jdbc.query("""
                SELECT assignment.admin_role_assignment_id, assignment.principal_type,
                       assignment.principal_ref,
                       COALESCE(principal_user.display_name, principal_group.display_name,
                                assignment.principal_ref) AS principal_name,
                       assignment.responsibility_code, assignment.resource_set_id,
                       resource_set.resource_set_key, resource_set.name AS resource_set_name,
                       assignment.assignment_source, assignment.lifecycle_state,
                       assignment.valid_from, assignment.valid_to, assignment.review_due_at,
                       assignment.justification, assignment.created_by,
                       requester.display_name AS requested_by_name,
                       assignment.approved_by, approver.display_name AS approved_by_name,
                       assignment.approved_at, assignment.decision_reason,
                       assignment.version, assignment.created_at, assignment.updated_at
                  FROM com_admin_role_assignments assignment
                  JOIN com_admin_resource_sets resource_set
                    ON resource_set.tenant_id = assignment.tenant_id
                   AND resource_set.resource_set_id = assignment.resource_set_id
                  LEFT JOIN com_users principal_user
                    ON assignment.principal_type = 'USER'
                   AND principal_user.tenant_id = assignment.tenant_id
                   AND principal_user.user_id::text = assignment.principal_ref
                  LEFT JOIN com_groups principal_group
                    ON assignment.principal_type = 'GROUP'
                   AND principal_group.tenant_id = assignment.tenant_id
                   AND principal_group.group_id::text = assignment.principal_ref
                  LEFT JOIN com_users requester
                    ON requester.tenant_id = assignment.tenant_id
                   AND requester.user_id = assignment.created_by
                  LEFT JOIN com_users approver
                    ON approver.tenant_id = assignment.tenant_id
                   AND approver.user_id = assignment.approved_by
                 WHERE assignment.tenant_id = ?
                 ORDER BY CASE assignment.lifecycle_state
                          WHEN 'PENDING_APPROVAL' THEN 0
                          WHEN 'APPROVED' THEN 1 WHEN 'ACTIVE' THEN 2 ELSE 3 END,
                          assignment.updated_at DESC
                """, this::assignment, tenantId);
    }

    private AppGovernanceDtos.Metrics metrics(
            Long tenantId,
            List<AppGovernanceDtos.ResourceSet> sets,
            List<AppGovernanceDtos.Assignment> assignments) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);
        long active = assignments.stream().filter(value -> isEffectivelyActive(value, now)).count();
        long pending = assignments.stream()
                .filter(value -> "PENDING_APPROVAL".equals(value.lifecycleState())).count();
        long reviews = assignments.stream()
                .filter(value -> isEffectivelyActive(value, now))
                .filter(value -> !value.reviewDueAt().isAfter(due)).count();
        Set<UUID> effectiveOwnerSets = assignmentStore.effectiveOwnerResourceSetIds(tenantId);
        long withoutOwner = sets.stream()
                .filter(set -> !effectiveOwnerSets.contains(set.resourceSetId()))
                .count();
        return new AppGovernanceDtos.Metrics(active, pending, reviews, withoutOwner);
    }

    private boolean isEffectivelyActive(
            AppGovernanceDtos.Assignment assignment,
            OffsetDateTime now) {
        return "ACTIVE".equals(assignment.lifecycleState())
                && (assignment.validFrom() == null || !assignment.validFrom().isAfter(now))
                && (assignment.validTo() == null || assignment.validTo().isAfter(now));
    }

    private AppGovernanceDtos.ResourceSet requireResourceSet(Long tenantId, UUID resourceSetId) {
        return resourceSets(tenantId).stream()
                .filter(value -> value.resourceSetId().equals(resourceSetId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private AppGovernanceDtos.Assignment requireAssignment(Long tenantId, UUID assignmentId) {
        return assignments(tenantId).stream()
                .filter(value -> value.assignmentId().equals(assignmentId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private List<AppGovernanceDtos.ResourceMember> requireAppResources(
            Long tenantId, List<String> requestedKeys) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        requestedKeys.forEach(value -> keys.add(value.trim().toUpperCase(Locale.ROOT)));
        if (keys.size() != requestedKeys.size()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Resource keys must be unique.");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId);
        arguments.addAll(keys);
        List<AppGovernanceDtos.ResourceMember> resources = jdbc.query("""
                SELECT type, key, name FROM com_resources
                 WHERE tenant_id = ? AND type = 'APP' AND enabled = TRUE
                   AND key <> 'APP.ADMINISTRATION' AND key IN (%s)
                 ORDER BY key
                """.formatted(placeholders), (result, ignored) ->
                new AppGovernanceDtos.ResourceMember(
                        result.getString("type"), result.getString("key"), result.getString("name")),
                arguments.toArray());
        if (resources.size() != keys.size()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Every resource must be an active tenant application.");
        }
        return resources;
    }

    private void replaceMembers(
            Long tenantId,
            UUID resourceSetId,
            List<AppGovernanceDtos.ResourceMember> resources,
            Long actorId) {
        resources.forEach(resource -> jdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    resource_set_member_id, tenant_id, resource_set_id,
                    resource_type, resource_key, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, UUID.randomUUID(), tenantId, resourceSetId, resource.resourceType(),
                resource.resourceKey(), actorId, actorId));
    }

    private Set<String> tenantRoles(Long tenantId, Long userId) {
        return new LinkedHashSet<>(jdbc.query("""
                SELECT role.code
                  FROM com_roles role
                  JOIN (
                      SELECT member.role_id
                        FROM com_role_members member
                       WHERE member.tenant_id = ? AND member.user_id = ?
                      UNION
                      SELECT assignment.role_id
                        FROM com_group_role_assignments assignment
                        JOIN com_group_members membership
                          ON membership.tenant_id = assignment.tenant_id
                         AND membership.group_id = assignment.group_id
                        JOIN com_groups access_group
                          ON access_group.tenant_id = membership.tenant_id
                         AND access_group.group_id = membership.group_id
                         AND access_group.status = 'ACTIVE'
                       WHERE assignment.tenant_id = ? AND membership.user_id = ?
                         AND assignment.lifecycle_state = 'ACTIVE'
                         AND assignment.assignment_type = 'ACTIVE'
                         AND assignment.scope_type = 'TENANT'
                         AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
                         AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
                  ) effective ON effective.role_id = role.role_id
                 WHERE role.tenant_id = ? AND role.status = 'ACTIVE'
                """, (result, ignored) -> result.getString(1),
                tenantId, userId, tenantId, userId, tenantId));
    }

    private void validateWindow(OffsetDateTime validTo) {
        if (validTo != null && !validTo.isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The assignment must end in the future.");
        }
    }

    private String normalizeKey(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void denied(
            Long tenantId, Long actorId, String correlationId,
            String targetType, String targetId, String reason) {
        String message = reason.startsWith("PRESET_WORKFLOW_REQUIRED")
                ? "Product specialist access must use the governed app-admin preset workflow."
                : "The delegated application scope does not permit this action.";
        throw new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private void conflict(String message) {
        throw new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private Map<String, Object> snapshot(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return values;
    }

    private AppGovernanceDtos.ResourceRole resourceRole(ResultSet result, int ignored)
            throws SQLException {
        return new AppGovernanceDtos.ResourceRole(
                result.getString("responsibility_code"), result.getString("resource_type"),
                result.getString("resource_key"),
                result.getObject("resource_set_id", UUID.class),
                result.getString("resource_set_key"),
                result.getObject("valid_to", OffsetDateTime.class));
    }

    private AppGovernanceDtos.Assignment assignment(ResultSet result, int ignored)
            throws SQLException {
        return new AppGovernanceDtos.Assignment(
                result.getObject("admin_role_assignment_id", UUID.class),
                result.getString("principal_type"), result.getString("principal_ref"),
                result.getString("principal_name"), result.getString("responsibility_code"),
                result.getObject("resource_set_id", UUID.class),
                result.getString("resource_set_key"), result.getString("resource_set_name"),
                result.getString("assignment_source"), result.getString("lifecycle_state"),
                result.getObject("valid_from", OffsetDateTime.class),
                result.getObject("valid_to", OffsetDateTime.class),
                result.getObject("review_due_at", OffsetDateTime.class),
                result.getString("justification"), (Long) result.getObject("created_by"),
                result.getString("requested_by_name"), (Long) result.getObject("approved_by"),
                result.getString("approved_by_name"),
                result.getObject("approved_at", OffsetDateTime.class),
                result.getString("decision_reason"), result.getLong("version"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class), false);
    }

    private record ExpiredAssignment(
            UUID assignmentId,
            Long tenantId,
            String principalType,
            String principalRef,
            OffsetDateTime validTo) {
    }
}
