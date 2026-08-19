package com.dwp.services.space.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.space.operations.SpaceOperationsService;
import com.dwp.services.space.integration.SpaceEntitlementPort;
import com.dwp.services.space.security.SpaceRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SpaceService {

    private final SpaceQueryRepository queries;
    private final SpaceCommandRepository commands;
    private final SpaceTemplateCommandRepository templateCommands;
    private final SpaceOwnerRecoveryRepository ownerRecovery;
    private final AuditOutboxRecorder audit;
    private final SpaceOperationsService operations;
    private final SpaceEntitlementPort entitlements;

    public SpaceService(
            SpaceQueryRepository queries,
            SpaceCommandRepository commands,
            SpaceTemplateCommandRepository templateCommands,
            SpaceOwnerRecoveryRepository ownerRecovery,
            AuditOutboxRecorder audit,
            SpaceOperationsService operations,
            SpaceEntitlementPort entitlements) {
        this.queries = queries;
        this.commands = commands;
        this.templateCommands = templateCommands;
        this.ownerRecovery = ownerRecovery;
        this.audit = audit;
        this.operations = operations;
        this.entitlements = entitlements;
    }

    @Transactional(readOnly = true)
    public SpaceDtos.HomeResponse home() {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        List<SpaceDtos.SpaceSummary> focusSpaces = queries.spaces(subject, "MY", "", 8);
        List<SpaceDtos.ActivitySummary> recentActivity = focusSpaces.stream()
                .limit(5)
                .flatMap(space -> queries.activity(subject.tenantId(), space.spaceId(), 3).stream())
                .sorted(Comparator.comparing(SpaceDtos.ActivitySummary::occurredAt).reversed())
                .limit(8)
                .toList();
        SpaceDtos.HomeMetrics metrics = queries.homeMetrics(subject);
        return new SpaceDtos.HomeResponse(
                Instant.now(),
                metrics,
                focusSpaces,
                recentActivity,
                queries.templates(subject.tenantId(), false).stream().limit(4).toList(),
                insights(metrics),
                subject.has("ACTION.SPACE_REQUEST", "CREATE", "MANAGE"),
                subject.has("ADMIN.SPACE_GOVERNANCE", "VIEW", "MANAGE"));
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.SpaceSummary> spaces(String scope, String query, int limit) {
        return queries.spaces(SpaceRequestContext.get(), scope, query, limit);
    }

    @Transactional(readOnly = true)
    public SpaceDtos.SpaceDetail space(String spaceKey) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        SpaceDtos.SpaceSummary summary = queries.space(subject, spaceKey);
        boolean canModerate = queries.canModerate(subject, summary.spaceId());
        boolean canManage = queries.canManage(subject, summary.spaceId());
        String role = summary.memberRole();
        boolean canViewContent = role != null || canModerate || "OPEN".equals(summary.visibility());
        boolean canContribute = canModerate
                || (role != null && List.of("CONTRIBUTOR", "EDITOR").contains(role));
        Map<String, String> policies = queries.policies(subject.tenantId(), summary.spaceId());
        return new SpaceDtos.SpaceDetail(
                summary,
                policies.get("contentPolicy"),
                policies.get("appPolicy"),
                policies.get("aiPolicy"),
                canContribute,
                canModerate,
                canManage,
                canViewContent
                        ? queries.content(subject.tenantId(), summary.spaceId(), canModerate, 20)
                        : List.of(),
                canViewContent ? queries.apps(subject.tenantId(), summary.spaceId()) : List.of(),
                canViewContent
                        ? queries.activity(subject.tenantId(), summary.spaceId(), 20)
                        : List.of());
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.TemplateSummary> templates(boolean includeDrafts) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        if (includeDrafts && !subject.has("ADMIN.SPACE_TEMPLATES", "VIEW", "MANAGE")) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return queries.templates(subject.tenantId(), includeDrafts);
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.RequestSummary> myRequests(String status) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        return queries.requests(subject.tenantId(), subject.userId(), status);
    }

    @Transactional
    public SpaceDtos.RequestSummary createRequest(
            SpaceDtos.CreateSpaceRequest input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        UUID requestId = commands.createRequest(subject, input);
        record(subject, "SPACE_CREATION_REQUESTED", "SPACE_REQUEST", requestId.toString(),
                correlationId, Map.of(
                        "requestedKey", input.requestedKey(),
                        "visibility", input.requestedVisibility(),
                        "templateId", input.templateId().toString()));
        SpaceDtos.RequestSummary created = queries.requests(
                        subject.tenantId(), subject.userId(), "ALL").stream()
                .filter(candidate -> candidate.requestId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        operations.recordPolicyEvaluation(
                subject, null, "SPACE_CREATION", "SPACE_REQUEST", requestId.toString(),
                "PENDING".equals(created.status()) ? "REVIEW" : "ALLOW",
                created.decisionMode(), created.riskLevel(), correlationId,
                created.policyEvidence());
        return created;
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.ContentSummary> content(String spaceKey) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        SpaceDtos.SpaceSummary space = queries.space(subject, spaceKey);
        boolean canModerate = queries.canModerate(subject, space.spaceId());
        if (space.memberRole() == null && !canModerate && !"OPEN".equals(space.visibility())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return queries.content(subject.tenantId(), space.spaceId(), canModerate, 100);
    }

    @Transactional
    public SpaceDtos.ContentSummary createContent(
            String spaceKey,
            SpaceDtos.CreateContentRequest input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        SpaceDtos.SpaceSummary space = queries.space(subject, spaceKey);
        if (!canContribute(subject, space)) throw new BaseException(ErrorCode.FORBIDDEN);
        UUID contentId = commands.createContent(subject, space.spaceId(), input);
        record(subject, "SPACE_CONTENT_CREATED", "SPACE_CONTENT", contentId.toString(),
                correlationId, Map.of(
                        "spaceKey", spaceKey,
                        "contentType", input.contentType(),
                        "classification", input.dataClassification()));
        SpaceDtos.ContentSummary created = queries.content(
                        subject.tenantId(), space.spaceId(), true, 100).stream()
                .filter(candidate -> candidate.contentId().equals(contentId))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Map<String, String> policies = queries.policies(subject.tenantId(), space.spaceId());
        operations.recordPolicyEvaluation(
                subject, space.spaceId(), "CONTENT_PUBLICATION", "CONTENT",
                contentId.toString(), "PUBLISHED".equals(created.lifecycleState())
                        ? "ALLOW" : "REVIEW",
                policies.get("contentPolicy"), classificationRisk(input.dataClassification()),
                correlationId, Map.of(
                        "contentType", input.contentType(),
                        "classification", input.dataClassification()));
        return created;
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.MemberSummary> members(String spaceKey) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        SpaceDtos.SpaceSummary space = queries.space(subject, spaceKey);
        if (!queries.canManage(subject, space.spaceId())) throw new BaseException(ErrorCode.FORBIDDEN);
        return queries.members(subject.tenantId(), space.spaceId());
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.AccessRequestSummary> myAccessRequests(String status) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        return queries.accessRequests(subject.tenantId(), null, subject.userId(), status);
    }

    @Transactional
    public SpaceDtos.AccessRequestSummary requestAccess(
            String spaceKey,
            SpaceDtos.CreateAccessRequest input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ACTION.SPACE_MEMBERSHIP", "CREATE", "MANAGE");
        SpaceDtos.SpaceSummary space = queries.space(subject, spaceKey);
        if (space.memberRole() != null && !"VIEWER".equals(space.memberRole())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The requester already has an active Space membership.");
        }
        UUID requestId = commands.createAccessRequest(subject, space.spaceId(), input);
        record(subject, "SPACE_ACCESS_REQUESTED", "SPACE_ACCESS_REQUEST", requestId.toString(),
                correlationId, Map.of(
                        "spaceKey", spaceKey,
                        "requestedRole", input.requestedRole()));
        SpaceDtos.AccessRequestSummary created = findAccessRequest(subject.tenantId(), requestId);
        operations.recordPolicyEvaluation(
                subject, space.spaceId(), "SPACE_ACCESS", "ACCESS_REQUEST",
                requestId.toString(), "PENDING".equals(created.status()) ? "REVIEW" : "ALLOW",
                created.decisionMode(), "CONTRIBUTOR".equals(input.requestedRole())
                        ? "MEDIUM" : "LOW",
                correlationId, Map.of(
                        "visibility", space.visibility(),
                        "requestedRole", input.requestedRole()));
        return created;
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.AccessRequestSummary> ownerAccessRequests(
            String spaceKey,
            String status) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        SpaceDtos.SpaceSummary space = queries.space(subject, spaceKey);
        requireSpaceManager(subject, space);
        return queries.accessRequests(subject.tenantId(), space.spaceId(), null, status);
    }

    @Transactional
    public void decideAccessRequest(
            String spaceKey,
            UUID requestId,
            SpaceDtos.AccessDecision input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        SpaceDtos.SpaceSummary space = queries.space(subject, spaceKey);
        requireSpaceManager(subject, space);
        commands.decideAccessRequest(subject, space.spaceId(), requestId, input);
        record(subject, "SPACE_ACCESS_DECIDED", "SPACE_ACCESS_REQUEST", requestId.toString(),
                correlationId, Map.of("decision", input.decision(), "note", input.note()));
    }

    @Transactional
    public List<SpaceDtos.MemberSummary> saveMember(
            String spaceKey,
            SpaceDtos.SaveMemberRequest input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        SpaceDtos.SpaceSummary space = queries.space(subject, spaceKey);
        requireSpaceManager(subject, space);
        commands.saveMember(subject, space.spaceId(), input);
        record(subject, "SPACE_MEMBERSHIP_SAVED", "SPACE", space.spaceId().toString(),
                correlationId, Map.of(
                        "principalType", input.principalType(),
                        "principalRef", input.principalRef(),
                        "memberRole", input.memberRole()));
        return queries.members(subject.tenantId(), space.spaceId());
    }

    @Transactional
    public List<SpaceDtos.MemberSummary> updateMember(
            String spaceKey,
            UUID membershipId,
            SpaceDtos.UpdateMemberRequest input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        SpaceDtos.SpaceSummary space = queries.space(subject, spaceKey);
        requireSpaceManager(subject, space);
        commands.updateMember(subject, space.spaceId(), membershipId, input);
        record(subject, "SPACE_MEMBERSHIP_UPDATED", "SPACE_MEMBERSHIP",
                membershipId.toString(), correlationId,
                Map.of("memberRole", input.memberRole()));
        return queries.members(subject.tenantId(), space.spaceId());
    }

    @Transactional
    public List<SpaceDtos.MemberSummary> revokeMember(
            String spaceKey,
            UUID membershipId,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        SpaceDtos.SpaceSummary space = queries.space(subject, spaceKey);
        requireSpaceManager(subject, space);
        commands.revokeMember(subject, space.spaceId(), membershipId);
        record(subject, "SPACE_MEMBERSHIP_REVOKED", "SPACE_MEMBERSHIP",
                membershipId.toString(), correlationId, Map.of("state", "REVOKED"));
        return queries.members(subject.tenantId(), space.spaceId());
    }

    @Transactional
    public SpaceDtos.SpaceDetail updatePolicies(
            String spaceKey,
            SpaceDtos.UpdatePolicyRequest input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        SpaceDtos.SpaceSummary space = queries.space(subject, spaceKey);
        if (!queries.canManage(subject, space.spaceId())) throw new BaseException(ErrorCode.FORBIDDEN);
        commands.updatePolicies(subject, space.spaceId(), input);
        record(subject, "SPACE_POLICIES_UPDATED", "SPACE", space.spaceId().toString(),
                correlationId, Map.of(
                        "contentPolicy", input.contentPolicy(),
                        "appPolicy", input.appPolicy(),
                        "aiPolicy", input.aiPolicy()));
        return space(spaceKey);
    }

    @Transactional(readOnly = true)
    public SpaceDtos.AdminOverview adminOverview() {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_GOVERNANCE", "VIEW", "MANAGE");
        return new SpaceDtos.AdminOverview(
                Instant.now(),
                queries.adminMetrics(subject.tenantId()),
                queries.requests(subject.tenantId(), null, "PENDING").stream().limit(8).toList(),
                queries.publicationReviews(subject.tenantId(), "PENDING").stream().limit(8).toList(),
                queries.lifecycleReviews(subject.tenantId(), "ALL").stream().limit(8).toList(),
                queries.adminSpaces(subject, "", 100));
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.RequestSummary> adminRequests(String status) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_GOVERNANCE", "VIEW", "MANAGE");
        return queries.requests(subject.tenantId(), null, status);
    }

    @Transactional
    public SpaceDtos.RequestSummary decideRequest(
            UUID requestId,
            SpaceDtos.RequestDecision input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_GOVERNANCE", "MANAGE");
        commands.decideRequest(subject, requestId, input);
        record(subject, "SPACE_CREATION_DECIDED", "SPACE_REQUEST", requestId.toString(),
                correlationId, Map.of("decision", input.decision(), "note", input.note()));
        return queries.requests(subject.tenantId(), null, "ALL").stream()
                .filter(candidate -> candidate.requestId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.PublicationReviewSummary> publicationReviews(String status) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_COMPLIANCE", "VIEW", "APPROVE", "MANAGE");
        return queries.publicationReviews(subject.tenantId(), status);
    }

    @Transactional
    public void decidePublication(
            UUID reviewId,
            SpaceDtos.ReviewDecision input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_COMPLIANCE", "APPROVE", "MANAGE");
        commands.decidePublication(subject, reviewId, input);
        record(subject, "SPACE_PUBLICATION_DECIDED", "SPACE_PUBLICATION_REVIEW",
                reviewId.toString(), correlationId,
                Map.of("decision", input.decision(), "note", input.note()));
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.LifecycleReviewSummary> lifecycleReviews(String status) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_ACCESS_REVIEW", "VIEW", "APPROVE", "MANAGE");
        return queries.lifecycleReviews(subject.tenantId(), status);
    }

    @Transactional
    public SpaceDtos.TemplateSummary createTemplate(
            SpaceDtos.SaveTemplateRequest input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_TEMPLATES", "CREATE", "MANAGE");
        UUID templateId = templateCommands.create(subject, input);
        record(subject, "SPACE_TEMPLATE_CREATED", "SPACE_TEMPLATE", templateId.toString(),
                correlationId, Map.of(
                        "templateKey", input.templateKey(),
                        "lifecycleState", input.lifecycleState()));
        return findTemplate(subject.tenantId(), templateId);
    }

    @Transactional
    public SpaceDtos.TemplateSummary updateTemplate(
            UUID templateId,
            SpaceDtos.SaveTemplateRequest input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_TEMPLATES", "UPDATE", "MANAGE");
        templateCommands.update(subject, templateId, input);
        record(subject, "SPACE_TEMPLATE_UPDATED", "SPACE_TEMPLATE", templateId.toString(),
                correlationId, Map.of(
                        "templateKey", input.templateKey(),
                        "lifecycleState", input.lifecycleState()));
        return findTemplate(subject.tenantId(), templateId);
    }

    @Transactional
    public void decideLifecycle(
            UUID lifecycleReviewId,
            SpaceDtos.LifecycleDecision input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_ACCESS_REVIEW", "APPROVE", "MANAGE");
        commands.decideLifecycle(subject, lifecycleReviewId, input);
        record(subject, "SPACE_LIFECYCLE_DECIDED", "SPACE_LIFECYCLE_REVIEW",
                lifecycleReviewId.toString(), correlationId,
                Map.of("recommendation", input.recommendation(), "note", input.note()));
    }

    @Transactional(readOnly = true)
    public List<SpaceDtos.SpaceSummary> adminSpaces(String query) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_GOVERNANCE", "VIEW", "MANAGE");
        return queries.adminSpaces(subject, query, 100);
    }

    @Transactional
    public List<SpaceDtos.MemberSummary> recoverOwner(
            String spaceKey,
            SpaceDtos.RecoverOwnerRequest input,
            String correlationId) {
        SpaceRequestContext.Subject subject = SpaceRequestContext.get();
        require(subject, "ADMIN.SPACE_GOVERNANCE", "MANAGE");
        SpaceDtos.SpaceSummary space = queries.adminSpace(subject, spaceKey);
        if (queries.hasActiveOwner(subject.tenantId(), space.spaceId())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Owner recovery is allowed only while the Space has no active owner.");
        }
        entitlements.validatePrincipal(new SpaceEntitlementPort.ValidationCommand(
                subject.tenantId(), correlationId, "USER",
                input.personPublicId().toString(), subject.userId()));
        ownerRecovery.recover(subject, space.spaceId(), input.personPublicId());
        record(subject, "SPACE_OWNER_RECOVERED", "SPACE", space.spaceId().toString(),
                correlationId, Map.of(
                        "spaceKey", space.spaceKey(),
                        "personPublicId", input.personPublicId().toString(),
                        "reason", input.reason().trim()));
        operations.recordPolicyEvaluation(
                subject, space.spaceId(), "SPACE_OWNER_RECOVERY", "SPACE",
                space.spaceId().toString(), "ALLOW", "ADMIN_RECOVERY", "CRITICAL",
                correlationId, Map.of(
                        "personPublicId", input.personPublicId().toString(),
                        "reason", input.reason().trim()));
        return queries.members(subject.tenantId(), space.spaceId());
    }

    private SpaceDtos.TemplateSummary findTemplate(long tenantId, UUID templateId) {
        return queries.templates(tenantId, true).stream()
                .filter(candidate -> candidate.templateId().equals(templateId))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void require(
            SpaceRequestContext.Subject subject,
            String resource,
            String... permissionCodes) {
        if (!subject.has(resource, permissionCodes)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    private boolean canContribute(
            SpaceRequestContext.Subject subject,
            SpaceDtos.SpaceSummary space) {
        return queries.canModerate(subject, space.spaceId())
                || List.of("CONTRIBUTOR", "EDITOR").contains(space.memberRole());
    }

    private void requireSpaceManager(
            SpaceRequestContext.Subject subject,
            SpaceDtos.SpaceSummary space) {
        if (!queries.canManage(subject, space.spaceId())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    private SpaceDtos.AccessRequestSummary findAccessRequest(long tenantId, UUID requestId) {
        return queries.accessRequests(tenantId, null, null, "ALL").stream()
                .filter(candidate -> candidate.accessRequestId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private List<SpaceDtos.Insight> insights(SpaceDtos.HomeMetrics metrics) {
        if (metrics.pendingRequests() > 0) {
            return List.of(new SpaceDtos.Insight(
                    "request-progress", "info",
                    "Space 요청이 정책 검토 중입니다", "A Space request is under policy review",
                    "요청 상태와 다음 승인 단계를 확인할 수 있습니다.",
                    "Review the request state and its next approval step.",
                    "/spaces/requests"));
        }
        return List.of(new SpaceDtos.Insight(
                "healthy", "success",
                "협업 공간이 안정적으로 운영 중입니다", "Your collaboration portfolio is healthy",
                "새로운 커뮤니티를 탐색하거나 템플릿에서 공간을 시작해 보세요.",
                "Discover a community or start a governed Space from a template.",
                "/spaces/discover"));
    }

    private String classificationRisk(String classification) {
        return switch (classification) {
            case "RESTRICTED" -> "CRITICAL";
            case "CONFIDENTIAL" -> "HIGH";
            case "INTERNAL" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private void record(
            SpaceRequestContext.Subject subject,
            String action,
            String targetType,
            String targetId,
            String correlationId,
            Map<String, Object> afterState) {
        audit.record(AuditEvent.builder()
                .tenantId(subject.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("INFO")
                .actorType("USER")
                .actorId(Long.toString(subject.userId()))
                .actorRoles(List.copyOf(subject.roles()))
                .sourceService("dwp-space-server")
                .sourceModule("enterprise-space-platform")
                .targetType(targetType)
                .targetId(targetId)
                .correlationId(correlationId)
                .afterState(afterState)
                .retentionClass("EXTENDED")
                .build());
    }
}
