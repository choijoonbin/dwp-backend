package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;

@Service
public class NavigationPolicyService {

    static final String PURPOSE = "HRIS_SYSTEM_ADMIN";
    static final String VIEW_PERMISSION = "CONFIG.HRIS_NAVIGATION_POLICY:VIEW";
    static final String UPDATE_PERMISSION = "CONFIG.HRIS_NAVIGATION_POLICY:UPDATE";
    static final String PUBLISH_PERMISSION = "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH";
    static final String PUBLISHED_EVENT = "dwp.hris.navigation-policy.published.v1";

    private final NavigationPolicyRepository repository;
    private final LegalEntityContextResolver legalEntityResolver;
    private final NavigationPolicyEligibilityProvider eligibilityProvider;
    private final NavigationPolicyRolloutProvider rolloutProvider;
    private final StepUpEvidenceVerifier stepUpVerifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public NavigationPolicyService(
            NavigationPolicyRepository repository,
            LegalEntityContextResolver legalEntityResolver,
            NavigationPolicyEligibilityProvider eligibilityProvider,
            NavigationPolicyRolloutProvider rolloutProvider,
            StepUpEvidenceVerifier stepUpVerifier,
            ObjectMapper objectMapper) {
        this(repository, legalEntityResolver, eligibilityProvider, rolloutProvider,
                stepUpVerifier, objectMapper, Clock.systemUTC());
    }

    NavigationPolicyService(
            NavigationPolicyRepository repository,
            LegalEntityContextResolver legalEntityResolver,
            NavigationPolicyEligibilityProvider eligibilityProvider,
            NavigationPolicyRolloutProvider rolloutProvider,
            StepUpEvidenceVerifier stepUpVerifier,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.legalEntityResolver = legalEntityResolver;
        this.eligibilityProvider = eligibilityProvider;
        this.rolloutProvider = rolloutProvider;
        this.stepUpVerifier = stepUpVerifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse workspace(
            AccessContext access,
            String workbenchId,
            ScopeKind scopeKind,
            UUID scopePublicId) {
        requireViewer(access);
        requireWorkbench(workbenchId);
        UUID legalEntity = resolveScope(access, scopeKind, scopePublicId);
        return new WorkspaceResponse(
                workbenchId, scopeKind, legalEntity,
                repository.findLatestDraft(access.tenantId(), scopeKind, legalEntity).orElse(null),
                repository.findPublished(access.tenantId(), scopeKind, legalEntity).orElse(null),
                repository.findPublishedHistory(access.tenantId(), scopeKind, legalEntity));
    }

    @Transactional
    public CommandReceipt createDraft(
            AccessContext access, CommandContext command, DraftCommand body) {
        requireAuthor(access);
        requireClientDigest(body.requestDigest());
        if (body.expectedVersion() != 0) throw conflict("HRIS_NAV_POLICY_CREATE_VERSION_CONFLICT");
        PolicyDocument policy = body.policy();
        verifyPolicyScope(access, policy, body.scopePublicId());
        validateForScope(access.tenantId(), policy);
        String payloadDigest = digest(policy);
        return execute(access, command, "CREATE_DRAFT", body.requestDigest(), body,
                ignored -> repository.createDraft(
                        access.tenantId(), UUID.randomUUID(), UUID.randomUUID(),
                        access.subjectId(), policy, payloadDigest, now()));
    }

    @Transactional
    public CommandReceipt updateDraft(
            AccessContext access, CommandContext command, UUID revisionId, DraftCommand body) {
        requireAuthor(access);
        requireClientDigest(body.requestDigest());
        RevisionView current = required(access.tenantId(), revisionId);
        PolicyDocument policy = body.policy();
        verifyPolicyScope(access, policy, body.scopePublicId());
        if (current.lifecycleState() != LifecycleState.DRAFT
                || current.policy().scopeKind() != policy.scopeKind()
                || !java.util.Objects.equals(
                        current.policy().legalEntityPublicId(), policy.legalEntityPublicId())) {
            throw conflict("HRIS_NAV_POLICY_DRAFT_IMMUTABLE_OR_SCOPE_MISMATCH");
        }
        validateForScope(access.tenantId(), policy);
        return execute(access, command, "UPDATE_DRAFT", body.requestDigest(), body,
                ignored -> {
                    if (!repository.updateDraft(
                            access.tenantId(), revisionId, body.expectedVersion(), policy,
                            digest(policy), now())) {
                        throw conflict("HRIS_NAV_POLICY_OBJECT_VERSION_CONFLICT");
                    }
                    return required(access.tenantId(), revisionId);
                });
    }

    @Transactional
    public CommandReceipt validate(
            AccessContext access, CommandContext command,
            UUID revisionId, LifecycleCommand body) {
        requireAuthor(access);
        RevisionView current = required(access.tenantId(), revisionId);
        validateForScope(access.tenantId(), current.policy());
        return transition(access, command, revisionId, body, "VALIDATE",
                LifecycleState.DRAFT, LifecycleState.VALIDATED);
    }

    @Transactional
    public CommandReceipt submit(
            AccessContext access, CommandContext command,
            UUID revisionId, LifecycleCommand body) {
        requireAuthor(access);
        return transition(access, command, revisionId, body, "SUBMIT",
                LifecycleState.VALIDATED, LifecycleState.PENDING_APPROVAL);
    }

    @Transactional
    public CommandReceipt approve(
            AccessContext access, CommandContext command,
            UUID revisionId, LifecycleCommand body) {
        requirePublisher(access);
        requireReason(body.reason());
        return transition(access, command, revisionId, body, "APPROVE",
                LifecycleState.PENDING_APPROVAL, LifecycleState.APPROVED,
                () -> {
                    requireStepUp(access, command, "APPROVE", revisionId,
                            body.expectedVersion(), body);
                    RevisionView current = required(access.tenantId(), revisionId);
                    if (current.authoredBy() == access.subjectId()) {
                        throw forbidden("HRIS_NAV_POLICY_MAKER_CANNOT_APPROVE");
                    }
                });
    }

    @Transactional
    public CommandReceipt publish(
            AccessContext access, CommandContext command,
            UUID revisionId, LifecycleCommand body) {
        requirePublisher(access);
        requireReason(body.reason());
        return execute(access, command, "PUBLISH", body.requestDigest(), body,
                () -> {
                    requireStepUp(access, command, "PUBLISH", revisionId,
                            body.expectedVersion(), body);
                    RevisionView current = required(access.tenantId(), revisionId);
                    if (current.authoredBy() == access.subjectId()) {
                        throw forbidden("HRIS_NAV_POLICY_MAKER_CANNOT_PUBLISH");
                    }
                    validateForScope(access.tenantId(), current.policy());
                },
                ignored -> {
                    if (!repository.publish(
                            access.tenantId(), revisionId, body.expectedVersion(),
                            access.subjectId(), evidenceDigest(body), now())) {
                        throw conflict("HRIS_NAV_POLICY_OBJECT_VERSION_CONFLICT");
                    }
                    RevisionView published = required(access.tenantId(), revisionId);
                    repository.activate(access.tenantId(), published, now());
                    repository.appendOutbox(
                            access.tenantId(), published, publishedEvent(published), now());
                    return published;
                });
    }

    @Transactional
    public CommandReceipt restore(
            AccessContext access, CommandContext command,
            UUID sourceRevisionId, RestoreCommand body) {
        requirePublisher(access);
        requireReason(body.reason());
        requireClientDigest(body.requestDigest());
        return execute(access, command, "RESTORE", body.requestDigest(), body,
                () -> {
                    requireStepUp(access, command, "RESTORE", sourceRevisionId,
                            body.expectedVersion(), body);
                    RevisionView source = required(access.tenantId(), sourceRevisionId);
                    if (source.authoredBy() == access.subjectId()) {
                        throw forbidden("HRIS_NAV_POLICY_MAKER_CANNOT_RESTORE");
                    }
                    validateForScope(access.tenantId(), source.policy());
                },
                ignored -> {
                    RevisionView source = required(access.tenantId(), sourceRevisionId);
                    RevisionView restored;
                    try {
                        restored = repository.restore(
                                access.tenantId(), source.revisionId(), UUID.randomUUID(),
                                body.expectedVersion(), access.subjectId(),
                                evidenceDigest(body), now());
                    } catch (IllegalStateException exception) {
                        throw conflict(exception.getMessage());
                    }
                    repository.activate(access.tenantId(), restored, now());
                    repository.appendOutbox(
                            access.tenantId(), restored, publishedEvent(restored), now());
                    return restored;
                });
    }

    @Transactional(readOnly = true)
    public EffectiveProjection projection(
            RuntimeContext context,
            String workbenchId) {
        requireWorkbench(workbenchId);
        requireRuntime(context);
        UUID legalEntity = context.requestedLegalEntityPublicId() == null
                ? null
                : legalEntityResolver.resolve(ownerRequest(
                        context.tenantId(), context.subjectId(), context.subjectPublicId(),
                        context.roles(), context.permissions(),
                        LegalEntityContextResolver.ContextSurface.OPERATIONS,
                        context.contextScopeKey(), context.requestedLegalEntityPublicId()));
        Set<String> installedNodeIds = eligibilityProvider.installedNodeIds(context.tenantId());
        Set<String> pageAuthorizedNodeIds = eligibilityProvider.pageAuthorizedNodeIds(
                permissions(context.permissions()));
        NavigationPolicyRolloutProvider.RuntimeEnrollment enrollment =
                rolloutProvider.enrollment(context.tenantId());
        RolloutMode enrolledMode = enrollment.mode();
        if (!enrollment.enrolled() || enrolledMode == RolloutMode.OFF) {
            return projectionWithoutEnforcement(
                    workbenchId,
                    enrollment.enrolled() ? EnforcementState.OFF : EnforcementState.NOT_ENROLLED,
                    installedNodeIds, pageAuthorizedNodeIds);
        }
        if (!context.revalidateAt().isAfter(now())) {
            if (enrolledMode == RolloutMode.ENFORCED) {
                return denied(workbenchId, EnforcementState.DENIED_STALE_CONTEXT,
                        "TRUSTED_CONTEXT_STALE");
            }
            return projectionWithoutEnforcement(
                    workbenchId, EnforcementState.SHADOW,
                    installedNodeIds, pageAuthorizedNodeIds);
        }
        RevisionView tenant = repository.findPublished(
                context.tenantId(), ScopeKind.TENANT, null).orElse(null);
        if (tenant == null) {
            if (enrolledMode == RolloutMode.ENFORCED) {
                return denied(workbenchId, EnforcementState.DENIED_MISSING_POLICY,
                        "TENANT_BASELINE_MISSING");
            }
            return projectionWithoutEnforcement(
                    workbenchId, EnforcementState.SHADOW,
                    installedNodeIds, pageAuthorizedNodeIds);
        }
        RevisionView overlay = legalEntity == null ? null : repository.findPublished(
                context.tenantId(), ScopeKind.LEGAL_ENTITY, legalEntity).orElse(null);
        if (enrolledMode == RolloutMode.SHADOW) {
            EffectiveProjection result = projectionWithoutEnforcement(
                    workbenchId, EnforcementState.SHADOW,
                    installedNodeIds, pageAuthorizedNodeIds);
            return new EffectiveProjection(
                    workbenchId, result.enforcementState(), false,
                    tenant.policyId(), tenant.revisionNumber(),
                    overlay == null ? null : overlay.revisionNumber(),
                    revisionPin(tenant, overlay), result.groups(), null, now());
        }
        if (tenant.rolloutMode() != RolloutMode.ENFORCED) {
            return denied(workbenchId, EnforcementState.DENIED_MISSING_POLICY,
                    "TENANT_POLICY_NOT_ENFORCEABLE");
        }
        if (overlay != null) {
            NavigationPolicyCanonical.validateRestrictiveOverlay(
                    tenant.policy(), overlay.policy());
        }
        return new EffectiveProjection(
                workbenchId, EnforcementState.ENFORCED, true,
                tenant.policyId(), tenant.revisionNumber(),
                overlay == null ? null : overlay.revisionNumber(),
                revisionPin(tenant, overlay),
                effectiveGroups(tenant.policy(), overlay == null ? null : overlay.policy(),
                        installedNodeIds, pageAuthorizedNodeIds),
                null, now());
    }

    private CommandReceipt transition(
            AccessContext access, CommandContext command, UUID revisionId,
            LifecycleCommand body, String action,
            LifecycleState expected, LifecycleState target) {
        return transition(access, command, revisionId, body, action, expected, target,
                () -> { });
    }

    private CommandReceipt transition(
            AccessContext access, CommandContext command, UUID revisionId,
            LifecycleCommand body, String action,
            LifecycleState expected, LifecycleState target,
            Runnable newMutationGuard) {
        requireClientDigest(body.requestDigest());
        return execute(access, command, action, body.requestDigest(), body,
                newMutationGuard,
                ignored -> {
                    if (!repository.transition(
                            access.tenantId(), revisionId, body.expectedVersion(), expected,
                            target, access.subjectId(), evidenceDigest(body), now())) {
                        throw conflict("HRIS_NAV_POLICY_OBJECT_VERSION_CONFLICT");
                    }
                    return required(access.tenantId(), revisionId);
                });
    }

    private CommandReceipt execute(
            AccessContext access, CommandContext command, String action,
            String clientRequestDigest, Object body,
            Function<UUID, RevisionView> mutation) {
        return execute(access, command, action, clientRequestDigest, body,
                () -> { }, mutation);
    }

    private CommandReceipt execute(
            AccessContext access, CommandContext command, String action,
            String clientRequestDigest, Object body,
            Runnable newMutationGuard,
            Function<UUID, RevisionView> mutation) {
        validateCommand(command);
        String serverDigest = digest(Map.of(
                "action", action,
                "body", body,
                "subjectPrincipalPublicId", access.subjectPublicId(),
                "contextScopeKey", access.contextScopeKey(),
                "clientRequestDigest", normalizeDigest(clientRequestDigest),
                "decisionRevision", command.expectedDecisionRevision() == null
                        ? "<none>" : command.expectedDecisionRevision()));
        repository.lockIdempotency(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey());
        var prior = repository.findReceipt(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey());
        if (prior.isPresent()) {
            if (!prior.get().requestDigest().equals(serverDigest)) {
                throw conflict("HRIS_NAV_POLICY_IDEMPOTENCY_PAYLOAD_MISMATCH");
            }
            return prior.get().receipt();
        }
        newMutationGuard.run();
        UUID receiptId = UUID.randomUUID();
        RevisionView result = mutation.apply(receiptId);
        OffsetDateTime createdAt = now();
        CommandReceipt receipt = new CommandReceipt(
                receiptId, action, result.policyId(), result.revisionId(),
                result.revisionNumber(), result.lifecycleState(), result.objectVersion(),
                normalizeDigest(clientRequestDigest), command.correlationId(), createdAt);
        repository.saveReceipt(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey(),
                serverDigest, receipt);
        repository.appendAudit(
                access.tenantId(), result, action, access.subjectId(),
                command.correlationId(), serverDigest, createdAt);
        return receipt;
    }

    private void validateForScope(long tenantId, PolicyDocument policy) {
        try {
            NavigationPolicyCanonical.validate(policy);
            if (policy.scopeKind() == ScopeKind.LEGAL_ENTITY) {
                RevisionView tenant = repository.findPublished(
                                tenantId, ScopeKind.TENANT, null)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "HRIS_NAV_POLICY_TENANT_BASELINE_REQUIRED"));
                NavigationPolicyCanonical.validateRestrictiveOverlay(tenant.policy(), policy);
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }
    }

    private void verifyPolicyScope(
            AccessContext access, PolicyDocument policy, UUID scopePublicId) {
        if (policy.scopeKind() == ScopeKind.TENANT) {
            if (scopePublicId != null || policy.legalEntityPublicId() != null) {
                throw forbidden("HRIS_NAV_POLICY_TENANT_SCOPE_MISMATCH");
            }
            return;
        }
        UUID verified = legalEntityResolver.resolve(ownerRequest(
                access.tenantId(), access.subjectId(), access.subjectPublicId(),
                access.roles(), access.permissions(),
                LegalEntityContextResolver.ContextSurface.MANAGEMENT,
                access.contextScopeKey(), scopePublicId));
        if (!verified.equals(scopePublicId)
                || !verified.equals(policy.legalEntityPublicId())) {
            throw forbidden("HRIS_NAV_POLICY_LEGAL_ENTITY_SCOPE_MISMATCH");
        }
    }

    private UUID resolveScope(
            AccessContext access, ScopeKind scopeKind, UUID scopePublicId) {
        if (scopeKind == ScopeKind.TENANT) {
            if (scopePublicId != null) throw forbidden("HRIS_NAV_POLICY_TENANT_SCOPE_MISMATCH");
            return null;
        }
        UUID verified = legalEntityResolver.resolve(ownerRequest(
                access.tenantId(), access.subjectId(), access.subjectPublicId(),
                access.roles(), access.permissions(),
                LegalEntityContextResolver.ContextSurface.MANAGEMENT,
                access.contextScopeKey(), scopePublicId));
        if (!verified.equals(scopePublicId)) {
            throw forbidden("HRIS_NAV_POLICY_LEGAL_ENTITY_SCOPE_MISMATCH");
        }
        return verified;
    }

    private List<EffectiveGroup> effectiveGroups(
            PolicyDocument tenant,
            PolicyDocument overlay,
            Set<String> installed,
            Set<String> authorized) {
        Map<String, GroupPolicy> overlayGroups = overlay == null ? Map.of()
                : overlay.groups().stream().collect(java.util.stream.Collectors.toMap(
                GroupPolicy::groupId, group -> group));
        List<EffectiveGroup> result = new ArrayList<>();
        for (GroupPolicy tenantGroup : tenant.groups()) {
            GroupPolicy selected = overlayGroups.getOrDefault(tenantGroup.groupId(), tenantGroup);
            if (!tenantGroup.enabled() || !selected.enabled()) continue;
            Map<String, NodePolicy> tenantNodes = tenantGroup.nodes().stream()
                    .collect(java.util.stream.Collectors.toMap(NodePolicy::nodeId, node -> node));
            List<EffectiveNode> nodes = selected.nodes().stream()
                    .filter(NodePolicy::enabled)
                    .filter(node -> tenantNodes.get(node.nodeId()).enabled())
                    .filter(node -> installed.contains(node.nodeId()))
                    .filter(node -> authorized.contains(node.nodeId()))
                    .sorted(Comparator.comparing(NodePolicy::displayOrder)
                            .thenComparing(NodePolicy::nodeId))
                    .map(node -> new EffectiveNode(node.nodeId(), node.displayOrder()))
                    .toList();
            if (nodes.isEmpty()) continue;
            String defaultNode = nodes.stream().anyMatch(node -> node.nodeId().equals(
                    selected.defaultNodeId())) ? selected.defaultNodeId() : nodes.getFirst().nodeId();
            result.add(new EffectiveGroup(
                    selected.groupId(), selected.displayOrder(), defaultNode, nodes));
        }
        result.sort(Comparator.comparing(EffectiveGroup::displayOrder)
                .thenComparing(EffectiveGroup::groupId));
        return List.copyOf(result);
    }

    private EffectiveProjection projectionWithoutEnforcement(
            String workbenchId, EnforcementState state,
            Set<String> installed, Set<String> authorized) {
        List<EffectiveGroup> groups = new ArrayList<>();
        int groupOrder = 0;
        for (Map.Entry<String, List<String>> entry
                : NavigationPolicyCanonical.GROUP_MEMBERS.entrySet()) {
            List<EffectiveNode> nodes = new ArrayList<>();
            int nodeOrder = 0;
            for (String nodeId : entry.getValue()) {
                if (installed.contains(nodeId) && authorized.contains(nodeId)) {
                    nodes.add(new EffectiveNode(nodeId, nodeOrder));
                }
                nodeOrder++;
            }
            if (!nodes.isEmpty()) {
                groups.add(new EffectiveGroup(
                        entry.getKey(), groupOrder, nodes.getFirst().nodeId(), nodes));
            }
            groupOrder++;
        }
        return new EffectiveProjection(
                workbenchId, state, false, null, null, null, null,
                groups, null, now());
    }

    private EffectiveProjection denied(
            String workbenchId, EnforcementState state, String reason) {
        return new EffectiveProjection(
                workbenchId, state, true, null, null, null, null,
                List.of(), reason, now());
    }

    private void requireViewer(AccessContext access) {
        validateAccess(access);
        if (!permissions(access.permissions()).contains(VIEW_PERMISSION)) {
            throw forbidden("HRIS_NAV_POLICY_VIEW_DENIED");
        }
    }

    private void requireAuthor(AccessContext access) {
        validateAccess(access);
        if (!permissions(access.permissions()).contains(UPDATE_PERMISSION)) {
            throw forbidden("HRIS_NAV_POLICY_UPDATE_DENIED");
        }
    }

    private void requirePublisher(AccessContext access) {
        validateAccess(access);
        if (!permissions(access.permissions()).contains(PUBLISH_PERMISSION)) {
            throw forbidden("HRIS_NAV_POLICY_PUBLISH_DENIED");
        }
    }

    private void validateAccess(AccessContext access) {
        if (access == null || access.tenantId() < 1 || access.subjectId() < 1
                || access.subjectPublicId() == null || access.contextKey() == null
                || access.contextKey().isBlank() || access.contextKey().length() > 500
                || access.contextScopeKey() == null
                || !access.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")
                || access.decisionRevision() == null || access.decisionRevision().isBlank()
                || access.revalidateAt() == null || !access.revalidateAt().isAfter(now())
                || !PURPOSE.equals(access.purposeCode())) {
            throw forbidden("HRIS_NAV_POLICY_TRUSTED_CONTEXT_REQUIRED");
        }
        Set<String> grants = permissions(access.permissions());
        if (!grants.contains("APP.HCM:VIEW") && !grants.contains("APP.HRIS:VIEW")) {
            throw forbidden("HRIS_NAV_POLICY_ENTITLEMENT_REQUIRED");
        }
        if (permissions(access.roles()).stream().anyMatch(role -> role.startsWith("PROVIDER"))) {
            throw forbidden("HRIS_NAV_POLICY_PROVIDER_IDENTITY_DENIED");
        }
    }

    private void requireRuntime(RuntimeContext context) {
        if (context == null || context.tenantId() < 1 || context.subjectId() < 1
                || context.subjectPublicId() == null
                || context.contextScopeKey() == null
                || !context.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")
                || context.revalidateAt() == null
                || (!permissions(context.permissions()).contains("APP.HCM:VIEW")
                && !permissions(context.permissions()).contains("APP.HRIS:VIEW"))) {
            throw forbidden("HRIS_NAV_POLICY_RUNTIME_CONTEXT_INVALID");
        }
    }

    private void validateCommand(CommandContext command) {
        if (command == null || command.idempotencyKey() == null
                || command.idempotencyKey().length() < 16
                || command.idempotencyKey().length() > 128
                || command.correlationId() == null) {
            throw invalid("HRIS_NAV_POLICY_COMMAND_CONTEXT_INVALID");
        }
    }

    private void requireStepUp(
            AccessContext access,
            CommandContext command,
            String action,
            UUID revisionId,
            long expectedVersion,
            Object canonicalPayload) {
        if (command.stepUpProof() == null || command.stepUpProof().isBlank()
                || command.expectedDecisionRevision() == null
                || !command.expectedDecisionRevision().equals(access.decisionRevision())
                || !stepUpVerifier.verify(new StepUpEvidenceVerifier.StepUpEvidence(
                        access.tenantId(), access.subjectId(), action, revisionId,
                        expectedVersion, access.contextKey(), access.contextScopeKey(),
                        command.idempotencyKey(), command.commandMethod(),
                        command.commandPath(), canonicalPayload,
                        command.expectedDecisionRevision(), command.stepUpProof(),
                        command.requestAuthority()))) {
            throw forbidden("HRIS_NAV_POLICY_FRESH_STEP_UP_REQUIRED");
        }
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank() || !reason.equals(reason.strip())) {
            throw invalid("HRIS_NAV_POLICY_HIGH_RISK_REASON_REQUIRED");
        }
    }

    private void requireWorkbench(String value) {
        if (!NavigationPolicyCanonical.WORKBENCH_ID.equals(value)) {
            throw invalid("HRIS_NAV_POLICY_WORKBENCH_INVALID");
        }
    }

    private void requireClientDigest(String value) {
        if (value == null || !normalizeDigest(value).matches("^[a-f0-9]{64}$")) {
            throw invalid("HRIS_NAV_POLICY_REQUEST_DIGEST_INVALID");
        }
    }

    private RevisionView required(long tenantId, UUID revisionId) {
        return repository.findByRevisionId(tenantId, revisionId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_NOT_AVAILABLE, "HRIS_NAV_POLICY_REVISION_NOT_FOUND"));
    }

    private String evidenceDigest(Object body) {
        return digest(body);
    }

    private String revisionPin(RevisionView tenant, RevisionView overlay) {
        Map<String, Object> pin = new LinkedHashMap<>();
        pin.put("tenantRevisionId", tenant.revisionId());
        pin.put("tenantDigest", tenant.payloadDigest());
        if (overlay != null) {
            pin.put("legalEntityRevisionId", overlay.revisionId());
            pin.put("legalEntityDigest", overlay.payloadDigest());
        }
        return "nav-" + digest(pin).substring(0, 40);
    }

    private String publishedEvent(RevisionView revision) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("policyId", revision.policyId());
        event.put("revision", revision.revisionNumber());
        event.put("scopeKind", revision.policy().scopeKind());
        event.put("legalEntityPublicId", revision.policy().legalEntityPublicId());
        event.put("rolloutMode", revision.rolloutMode());
        event.put("payloadDigest", revision.payloadDigest());
        event.put("publishedAt", now());
        event.put("eventSequence", revision.revisionNumber());
        return json(event);
    }

    private Set<String> permissions(String value) {
        if (value == null || value.isBlank() || value.length() > 32_000
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.trim())) return Set.of();
            result.add(token.toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private String normalizeDigest(String value) {
        return value != null && value.startsWith("sha256:")
                ? value.substring(7).toLowerCase(Locale.ROOT)
                : value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private LegalEntityContextResolver.OwnerRequest ownerRequest(
            long tenantId,
            long actorId,
            UUID personPublicId,
            String roles,
            String permissions,
            LegalEntityContextResolver.ContextSurface surface,
            String contextScopeKey,
            UUID requestedLegalEmployerPublicId) {
        return new LegalEntityContextResolver.OwnerRequest(
                tenantId, actorId, personPublicId, roles, permissions, surface,
                contextScopeKey, requestedLegalEmployerPublicId);
    }

    private String digest(Object value) {
        byte[] bytes;
        try {
            bytes = value instanceof String string
                    ? string.getBytes(StandardCharsets.UTF_8)
                    : objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRIS_NAV_POLICY_DIGEST_FAILED", exception);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRIS_NAV_POLICY_EVENT_SERIALIZATION_FAILED", exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    public record AccessContext(
            long tenantId,
            long subjectId,
            UUID subjectPublicId,
            String permissions,
            String roles,
            String contextKey,
            String contextScopeKey,
            String decisionRevision,
            OffsetDateTime revalidateAt,
            String purposeCode) {
    }

    public record RuntimeContext(
            long tenantId,
            long subjectId,
            UUID subjectPublicId,
            String permissions,
            String roles,
            String contextScopeKey,
            UUID requestedLegalEntityPublicId,
            OffsetDateTime revalidateAt) {
    }

    public record CommandContext(
            String idempotencyKey,
            UUID correlationId,
            String expectedDecisionRevision,
            String stepUpProof,
            String commandMethod,
            String commandPath,
            com.dwp.services.platform.security.HcmPlatformPepRegistry.RouteAuthority
                    requestAuthority) {
    }
}
