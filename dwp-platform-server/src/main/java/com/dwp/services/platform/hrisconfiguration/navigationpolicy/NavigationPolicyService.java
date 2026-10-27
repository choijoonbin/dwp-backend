package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySeals;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyRepository.CanonicalReceipt;

@Service
public class NavigationPolicyService {
    final NavigationPolicyDocuments documents;



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
        this.documents = new NavigationPolicyDocuments(repository, objectMapper, clock);
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse workspace(
            AccessContext access,
            String workbenchId,
            ScopeKind scopeKind,
            UUID scopePublicId) {
        requireViewer(access);
        documents.requireWorkbench(workbenchId);
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
        documents.requireClientDigest(body.requestDigest());
        if (body.expectedVersion() != 0) throw documents.conflict("HRIS_NAV_POLICY_CREATE_VERSION_CONFLICT");
        PolicyDocument policy = body.policy();
        verifyPolicyScope(access, policy, body.scopePublicId());
        documents.validateForScope(access.tenantId(), policy);
        String payloadDigest = documents.digest(policy);
        return execute(access, command, "CREATE_DRAFT", body.requestDigest(), body,
                ignored -> repository.createDraft(
                        access.tenantId(), UUID.randomUUID(), UUID.randomUUID(),
                        access.subjectId(), policy, payloadDigest, documents.now()));
    }

    @Transactional
    public CommandReceipt updateDraft(
            AccessContext access, CommandContext command, UUID revisionId, DraftCommand body) {
        requireAuthor(access);
        documents.requireClientDigest(body.requestDigest());
        RevisionView current = documents.required(access.tenantId(), revisionId);
        PolicyDocument policy = body.policy();
        verifyPolicyScope(access, policy, body.scopePublicId());
        if (current.lifecycleState() != LifecycleState.DRAFT
                || current.policy().scopeKind() != policy.scopeKind()
                || !java.util.Objects.equals(
                        current.policy().legalEntityPublicId(), policy.legalEntityPublicId())) {
            throw documents.conflict("HRIS_NAV_POLICY_DRAFT_IMMUTABLE_OR_SCOPE_MISMATCH");
        }
        documents.validateForScope(access.tenantId(), policy);
        return execute(access, command, "UPDATE_DRAFT", body.requestDigest(), body,
                ignored -> {
                    if (!repository.updateDraft(
                            access.tenantId(), revisionId, body.expectedVersion(), policy,
                            documents.digest(policy), documents.now())) {
                        throw documents.conflict("HRIS_NAV_POLICY_OBJECT_VERSION_CONFLICT");
                    }
                    return documents.required(access.tenantId(), revisionId);
                });
    }

    @Transactional
    public CommandReceipt validate(
            AccessContext access, CommandContext command,
            UUID revisionId, LifecycleCommand body) {
        requireAuthor(access);
        RevisionView current = documents.required(access.tenantId(), revisionId);
        documents.validateForScope(access.tenantId(), current.policy());
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
        documents.requireReason(body.reason());
        return transition(access, command, revisionId, body, "APPROVE",
                LifecycleState.PENDING_APPROVAL, LifecycleState.APPROVED,
                () -> {
                    requireStepUp(access, command, "APPROVE", revisionId,
                            body.expectedVersion(), body);
                    RevisionView current = documents.required(access.tenantId(), revisionId);
                    if (current.authoredBy() == access.subjectId()) {
                        throw documents.forbidden("HRIS_NAV_POLICY_MAKER_CANNOT_APPROVE");
                    }
                });
    }

    @Transactional
    public CommandReceipt publish(
            AccessContext access, CommandContext command,
            UUID revisionId, LifecycleCommand body) {
        requirePublisher(access);
        documents.requireReason(body.reason());
        return execute(access, command, "PUBLISH", body.requestDigest(), body,
                () -> {
                    requireStepUp(access, command, "PUBLISH", revisionId,
                            body.expectedVersion(), body);
                    RevisionView current = documents.required(access.tenantId(), revisionId);
                    if (current.authoredBy() == access.subjectId()) {
                        throw documents.forbidden("HRIS_NAV_POLICY_MAKER_CANNOT_PUBLISH");
                    }
                    documents.validateForScope(access.tenantId(), current.policy());
                },
                ignored -> {
                    if (!repository.publish(
                            access.tenantId(), revisionId, body.expectedVersion(),
                            access.subjectId(), documents.evidenceDigest(body), documents.now())) {
                        throw documents.conflict("HRIS_NAV_POLICY_OBJECT_VERSION_CONFLICT");
                    }
                    RevisionView published = documents.required(access.tenantId(), revisionId);
                    repository.activate(access.tenantId(), published, documents.now());
                    repository.appendOutbox(
                            access.tenantId(), published, documents.publishedEvent(published), documents.now());
                    return published;
                });
    }

    @Transactional
    public CommandReceipt restore(
            AccessContext access, CommandContext command,
            UUID sourceRevisionId, RestoreCommand body) {
        requirePublisher(access);
        documents.requireReason(body.reason());
        documents.requireClientDigest(body.requestDigest());
        return execute(access, command, "RESTORE", body.requestDigest(), body,
                () -> {
                    requireStepUp(access, command, "RESTORE", sourceRevisionId,
                            body.expectedVersion(), body);
                    RevisionView source = documents.required(access.tenantId(), sourceRevisionId);
                    if (source.authoredBy() == access.subjectId()) {
                        throw documents.forbidden("HRIS_NAV_POLICY_MAKER_CANNOT_RESTORE");
                    }
                    documents.validateForScope(access.tenantId(), source.policy());
                },
                ignored -> {
                    RevisionView source = documents.required(access.tenantId(), sourceRevisionId);
                    RevisionView restored;
                    try {
                        restored = repository.restore(
                                access.tenantId(), source.revisionId(), UUID.randomUUID(),
                                body.expectedVersion(), access.subjectId(),
                                documents.evidenceDigest(body), documents.now());
                    } catch (IllegalStateException exception) {
                        throw documents.conflict(exception.getMessage());
                    }
                    repository.activate(access.tenantId(), restored, documents.now());
                    repository.appendOutbox(
                            access.tenantId(), restored, documents.publishedEvent(restored), documents.now());
                    return restored;
                });
    }

    @Transactional(readOnly = true)
    public EffectiveProjection projection(
            RuntimeContext context,
            String workbenchId) {
        documents.requireWorkbench(workbenchId);
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
                documents.permissions(context.permissions()));
        NavigationPolicyRolloutProvider.RuntimeEnrollment enrollment =
                rolloutProvider.enrollment(context.tenantId());
        RolloutMode enrolledMode = enrollment.mode();
        if (!enrollment.enrolled() || enrolledMode == RolloutMode.OFF) {
            return projectionWithoutEnforcement(
                    workbenchId,
                    enrollment.enrolled() ? EnforcementState.OFF : EnforcementState.NOT_ENROLLED,
                    installedNodeIds, pageAuthorizedNodeIds);
        }
        if (!context.revalidateAt().isAfter(documents.now())) {
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
                    documents.revisionPin(tenant, overlay), result.groups(), null, documents.now());
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
                documents.revisionPin(tenant, overlay),
                effectiveGroups(tenant.policy(), overlay == null ? null : overlay.policy(),
                        installedNodeIds, pageAuthorizedNodeIds),
                null, documents.now());
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
        documents.requireClientDigest(body.requestDigest());
        return execute(access, command, action, body.requestDigest(), body,
                newMutationGuard,
                ignored -> {
                    if (!repository.transition(
                            access.tenantId(), revisionId, body.expectedVersion(), expected,
                            target, access.subjectId(), documents.evidenceDigest(body), documents.now())) {
                        throw documents.conflict("HRIS_NAV_POLICY_OBJECT_VERSION_CONFLICT");
                    }
                    return documents.required(access.tenantId(), revisionId);
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
        validateLegacyCommand(command);
        Map<String, Object> digestMaterial = new LinkedHashMap<>();
        digestMaterial.put("action", action);
        digestMaterial.put("body", body);
        digestMaterial.put("subjectPrincipalPublicId", access.subjectPublicId());
        digestMaterial.put("contextScopeKey", access.contextScopeKey());
        digestMaterial.put("clientRequestDigest", documents.normalizeDigest(clientRequestDigest));
        digestMaterial.put("decisionRevision", command.expectedDecisionRevision() == null
                ? "<none>" : command.expectedDecisionRevision());
        digestMaterial.put("fieldPolicyRevision", command.fieldPolicyRevision());
        digestMaterial.put("authorizationRevision", command.authorizationRevision());
        String serverDigest = documents.digest(digestMaterial);
        String legacyServerDigest = documents.digest(Map.of(
                "action", action,
                "body", body,
                "subjectPrincipalPublicId", access.subjectPublicId(),
                "contextScopeKey", access.contextScopeKey(),
                "clientRequestDigest", documents.normalizeDigest(clientRequestDigest),
                "decisionRevision", command.expectedDecisionRevision() == null
                        ? "<none>" : command.expectedDecisionRevision()));
        repository.lockIdempotency(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey());
        var prior = repository.findReceipt(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey());
        if (prior.isPresent()) {
            boolean exactReplay = prior.get().requestDigest().equals(serverDigest);
            boolean historicalReplay = prior.get().requestDigest().equals(legacyServerDigest)
                    && !repository.canonicalReceiptExists(
                    access.tenantId(), access.subjectPublicId(),
                    prior.get().receipt().receiptId());
            if (!exactReplay && !historicalReplay) {
                throw documents.conflict("HRIS_NAV_POLICY_IDEMPOTENCY_PAYLOAD_MISMATCH");
            }
            return prior.get().receipt();
        }
        validateCanonicalSeal(access, command, action);
        String authorityPopulationScopeDigest =
                HrisExplorerAuthoritySeals.populationScopeDigest(
                        access.contextScopeKey());
        newMutationGuard.run();
        UUID receiptId = UUID.randomUUID();
        RevisionView result = mutation.apply(receiptId);
        OffsetDateTime createdAt = documents.now();
        long publicFieldPolicyRevision = HrisExplorerAuthoritySeals.publicRevision(
                command.fieldPolicyRevision());
        long publicAuthorizationRevision = HrisExplorerAuthoritySeals.publicRevision(
                command.authorizationRevision());
        CommandReceipt receipt = new CommandReceipt(
                receiptId, action, result.policyId(), result.revisionId(),
                result.revisionNumber(), result.lifecycleState(), result.objectVersion(),
                documents.normalizeDigest(clientRequestDigest), command.correlationId(), createdAt);
        repository.saveReceipt(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey(),
                serverDigest, receipt);
        repository.saveCanonicalReceipt(access.tenantId(), new CanonicalReceipt(
                receipt.receiptId(), receipt.commandType(), action,
                access.subjectPublicId(), command.populationScopeDigest(),
                authorityPopulationScopeDigest,
                publicFieldPolicyRevision, command.fieldPolicyRevision(),
                access.purposeCode(), publicAuthorizationRevision,
                command.authorizationRevision(), command.originRouteContractKey(),
                command.originContextKey(), command.originContextScopeKey(),
                command.originDecisionRevision(), command.idempotencyKey(),
                serverDigest, receipt.policyId(), receipt.objectVersion(),
                "SUCCEEDED", receipt.policyId() + "/" + receipt.revisionId()
                + "/" + receipt.revisionNumber(), receipt.correlationId(),
                receipt.createdAt()));
        repository.appendAudit(
                access.tenantId(), result, action, access.subjectId(),
                command.correlationId(), serverDigest, createdAt);
        return receipt;
    }

    private void verifyPolicyScope(
            AccessContext access, PolicyDocument policy, UUID scopePublicId) {
        if (policy.scopeKind() == ScopeKind.TENANT) {
            if (scopePublicId != null || policy.legalEntityPublicId() != null) {
                throw documents.forbidden("HRIS_NAV_POLICY_TENANT_SCOPE_MISMATCH");
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
            throw documents.forbidden("HRIS_NAV_POLICY_LEGAL_ENTITY_SCOPE_MISMATCH");
        }
    }

    private UUID resolveScope(
            AccessContext access, ScopeKind scopeKind, UUID scopePublicId) {
        if (scopeKind == ScopeKind.TENANT) {
            if (scopePublicId != null) throw documents.forbidden("HRIS_NAV_POLICY_TENANT_SCOPE_MISMATCH");
            return null;
        }
        UUID verified = legalEntityResolver.resolve(ownerRequest(
                access.tenantId(), access.subjectId(), access.subjectPublicId(),
                access.roles(), access.permissions(),
                LegalEntityContextResolver.ContextSurface.MANAGEMENT,
                access.contextScopeKey(), scopePublicId));
        if (!verified.equals(scopePublicId)) {
            throw documents.forbidden("HRIS_NAV_POLICY_LEGAL_ENTITY_SCOPE_MISMATCH");
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
                groups, null, documents.now());
    }

    private EffectiveProjection denied(
            String workbenchId, EnforcementState state, String reason) {
        return new EffectiveProjection(
                workbenchId, state, true, null, null, null, null,
                List.of(), reason, documents.now());
    }

    private void requireViewer(AccessContext access) {
        validateAccess(access);
        if (!documents.permissions(access.permissions()).contains(VIEW_PERMISSION)) {
            throw documents.forbidden("HRIS_NAV_POLICY_VIEW_DENIED");
        }
    }

    private void requireAuthor(AccessContext access) {
        validateAccess(access);
        if (!documents.permissions(access.permissions()).contains(UPDATE_PERMISSION)) {
            throw documents.forbidden("HRIS_NAV_POLICY_UPDATE_DENIED");
        }
    }

    private void requirePublisher(AccessContext access) {
        validateAccess(access);
        if (!documents.permissions(access.permissions()).contains(PUBLISH_PERMISSION)) {
            throw documents.forbidden("HRIS_NAV_POLICY_PUBLISH_DENIED");
        }
    }

    private void validateAccess(AccessContext access) {
        if (access == null || access.tenantId() < 1 || access.subjectId() < 1
                || access.subjectPublicId() == null || access.contextKey() == null
                || access.contextKey().isBlank() || access.contextKey().length() > 500
                || access.contextScopeKey() == null
                || !access.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")
                || access.decisionRevision() == null || access.decisionRevision().isBlank()
                || access.revalidateAt() == null || !access.revalidateAt().isAfter(documents.now())
                || !PURPOSE.equals(access.purposeCode())) {
            throw documents.forbidden("HRIS_NAV_POLICY_TRUSTED_CONTEXT_REQUIRED");
        }
        Set<String> grants = documents.permissions(access.permissions());
        if (!grants.contains("APP.HCM:VIEW") && !grants.contains("APP.HRIS:VIEW")) {
            throw documents.forbidden("HRIS_NAV_POLICY_ENTITLEMENT_REQUIRED");
        }
        if (documents.permissions(access.roles()).stream().anyMatch(role -> role.startsWith("PROVIDER"))) {
            throw documents.forbidden("HRIS_NAV_POLICY_PROVIDER_IDENTITY_DENIED");
        }
    }

    private void requireRuntime(RuntimeContext context) {
        if (context == null || context.tenantId() < 1 || context.subjectId() < 1
                || context.subjectPublicId() == null
                || context.contextScopeKey() == null
                || !context.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")
                || context.revalidateAt() == null
                || (!documents.permissions(context.permissions()).contains("APP.HCM:VIEW")
                && !documents.permissions(context.permissions()).contains("APP.HRIS:VIEW"))) {
            throw documents.forbidden("HRIS_NAV_POLICY_RUNTIME_CONTEXT_INVALID");
        }
    }

    private void validateLegacyCommand(CommandContext command) {
        if (command == null || command.idempotencyKey() == null
                || command.idempotencyKey().length() < 16
                || command.idempotencyKey().length() > 128
                || command.correlationId() == null) {
            throw documents.invalid("HRIS_NAV_POLICY_COMMAND_CONTEXT_INVALID");
        }
    }

    private void validateCanonicalSeal(
            AccessContext access, CommandContext command, String action) {
        if (!originRoute(action).equals(command.originRouteContractKey())
                || !access.contextKey().equals(command.originContextKey())
                || !access.contextScopeKey().equals(command.originContextScopeKey())
                || !access.decisionRevision().equals(command.originDecisionRevision())
                || command.populationScopeDigest() == null
                || !command.populationScopeDigest().matches("^[a-f0-9]{64}$")
                || command.fieldPolicyRevision() < 1
                || command.authorizationRevision() < 1
                || command.requestAuthority() == null
                || !command.originRouteContractKey().equals(
                command.requestAuthority().routeContractKey())) {
            throw documents.invalid("HRIS_NAV_POLICY_COMMAND_CONTEXT_INVALID");
        }
    }

    private String originRoute(String action) {
        return switch (action) {
            case "CREATE_DRAFT" ->
                    "route.hcm.configuration.navigation-policy-draft-create.action";
            case "UPDATE_DRAFT" ->
                    "route.hcm.configuration.navigation-policy-draft-update.action";
            case "VALIDATE" ->
                    "route.hcm.configuration.navigation-policy-draft-validate.action";
            case "SUBMIT" ->
                    "route.hcm.configuration.navigation-policy-draft-submit.action";
            case "APPROVE" ->
                    "route.hcm.configuration.navigation-policy-revision-approve.action";
            case "PUBLISH" ->
                    "route.hcm.configuration.navigation-policy-revision-publish.action";
            case "RESTORE" ->
                    "route.hcm.configuration.navigation-policy-revision-restore.action";
            default -> "";
        };
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
            throw documents.forbidden("HRIS_NAV_POLICY_FRESH_STEP_UP_REQUIRED");
        }
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
                    requestAuthority,
            String originRouteContractKey,
            String originContextKey,
            String originContextScopeKey,
            String originDecisionRevision,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision) {
    }
}
