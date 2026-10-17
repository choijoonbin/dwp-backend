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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.EnforcementState;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.RolloutMode;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.RuntimeContext;

/**
 * Produces one atomic seven-workbench projection. Visibility is only a restriction and never an
 * authorization grant; every node still requires an exact trusted PAGE/query capability.
 */
@Service
public class NavigationPolicyAggregateV2Service {

    private final NavigationPolicyAggregateV2Repository repository;
    private final LegalEntityContextResolver legalEntityResolver;
    private final NavigationPolicyEligibilityProvider eligibilityProvider;
    private final NavigationPolicyRolloutProvider rolloutProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public NavigationPolicyAggregateV2Service(
            NavigationPolicyAggregateV2Repository repository,
            LegalEntityContextResolver legalEntityResolver,
            NavigationPolicyEligibilityProvider eligibilityProvider,
            NavigationPolicyRolloutProvider rolloutProvider,
            ObjectMapper objectMapper) {
        this(repository, legalEntityResolver, eligibilityProvider, rolloutProvider,
                objectMapper, Clock.systemUTC());
    }

    NavigationPolicyAggregateV2Service(
            NavigationPolicyAggregateV2Repository repository,
            LegalEntityContextResolver legalEntityResolver,
            NavigationPolicyEligibilityProvider eligibilityProvider,
            NavigationPolicyRolloutProvider rolloutProvider,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.legalEntityResolver = legalEntityResolver;
        this.eligibilityProvider = eligibilityProvider;
        this.rolloutProvider = rolloutProvider;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EffectiveNavigation projection(RuntimeContext context) {
        return projection(context, Map.of());
    }

    /**
     * Internal bridge used only by aggregate-v3 after it has verified one exact Auth snapshot.
     * Public aggregate-v2 never supplies POLICY paths and therefore remains byte-for-byte
     * behavior compatible with R6-A.
     */
    @Transactional(readOnly = true)
    EffectiveNavigation projectionWithPolicyPaths(
            RuntimeContext context,
            Map<String, List<String>> admittedPolicyPaths) {
        return projection(context,
                NavigationPolicyAggregateV3Catalog.sealPolicyPaths(admittedPolicyPaths));
    }

    private EffectiveNavigation projection(
            RuntimeContext context,
            Map<String, List<String>> admittedPolicyPaths) {
        requireRuntime(context);
        NavigationPolicyRolloutProvider.RuntimeEnrollment enrollment =
                rolloutProvider.enrollment(context.tenantId());
        RolloutMode mode = enrollment.mode();
        if (enrollment.enrolled() && mode == RolloutMode.ENFORCED
                && !context.revalidateAt().isAfter(now())) {
            return denied(EnforcementState.DENIED_STALE_CONTEXT,
                    "TRUSTED_CONTEXT_STALE");
        }
        Set<String> installed = eligibilityProvider.installedNodeIds(context.tenantId());
        Set<String> deployed = eligibilityProvider.deployedNodeIds(context.tenantId());
        Set<String> trustedPermissions = permissions(context.permissions());
        Map<String, List<String>> authorizedPaths =
                ServerNavigationPolicyEligibilityProvider.sealAuthorizedPaths(
                        eligibilityProvider.pageAuthorizedPaths(trustedPermissions));
        authorizedPaths = mergeAuthorizedPaths(authorizedPaths, admittedPolicyPaths);

        if (!enrollment.enrolled() || mode == RolloutMode.OFF) {
            return unrestricted(
                    enrollment.enrolled() ? EnforcementState.OFF
                            : EnforcementState.NOT_ENROLLED,
                    installed, deployed, authorizedPaths, null, null);
        }
        UUID requestedLegalEntity = context.requestedLegalEntityPublicId();
        UUID legalEntity = requestedLegalEntity == null ? null
                : legalEntityResolver.resolve(new LegalEntityContextResolver.OwnerRequest(
                context.tenantId(), context.subjectId(), context.subjectPublicId(),
                context.roles(), context.permissions(),
                LegalEntityContextResolver.ContextSurface.OPERATIONS,
                context.contextScopeKey(), requestedLegalEntity));
        if (requestedLegalEntity != null && !requestedLegalEntity.equals(legalEntity)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN, "HRIS_NAV_V2_LEGAL_ENTITY_SCOPE_MISMATCH");
        }
        if (!context.revalidateAt().isAfter(now())) {
            return mode == RolloutMode.ENFORCED
                    ? denied(EnforcementState.DENIED_STALE_CONTEXT,
                    "TRUSTED_CONTEXT_STALE")
                    : unrestricted(EnforcementState.SHADOW, installed, deployed,
                    authorizedPaths, null, null);
        }

        PublishedAggregate tenant = repository.findPublished(
                context.tenantId(), ScopeKind.TENANT, null).orElse(null);
        PublishedAggregate overlay = legalEntity == null ? null
                : repository.findPublished(context.tenantId(), ScopeKind.LEGAL_ENTITY,
                legalEntity).orElse(null);
        if (tenant == null) {
            return mode == RolloutMode.ENFORCED
                    ? denied(EnforcementState.DENIED_MISSING_POLICY,
                    "TENANT_AGGREGATE_BASELINE_MISSING")
                    : unrestricted(EnforcementState.SHADOW, installed, deployed,
                    authorizedPaths, null, null);
        }
        if (legalEntity != null && overlay == null) {
            return mode == RolloutMode.ENFORCED
                    ? denied(EnforcementState.DENIED_MISSING_POLICY,
                    "LEGAL_ENTITY_AGGREGATE_OVERLAY_MISSING")
                    : unrestricted(EnforcementState.SHADOW, installed, deployed,
                    authorizedPaths, tenant, null);
        }
        if (!validPublished(tenant, ScopeKind.TENANT, null)
                || overlay != null && !validPublished(
                overlay, ScopeKind.LEGAL_ENTITY, legalEntity)) {
            return mode == RolloutMode.ENFORCED
                    ? denied(EnforcementState.DENIED_MISSING_POLICY,
                    "AGGREGATE_POLICY_INVALID")
                    : unrestricted(EnforcementState.SHADOW, installed, deployed,
                    authorizedPaths, tenant, overlay);
        }
        if (mode == RolloutMode.SHADOW) {
            return unrestricted(EnforcementState.SHADOW, installed, deployed,
                    authorizedPaths, tenant, overlay);
        }
        if (tenant.policy().rolloutMode() != RolloutMode.ENFORCED) {
            return denied(EnforcementState.DENIED_MISSING_POLICY,
                    "TENANT_AGGREGATE_NOT_ENFORCEABLE");
        }
        if (overlay != null) {
            try {
                NavigationPolicyAggregateV2Canonical.validateRestrictiveOverlay(
                        tenant.policy(), overlay.policy());
            } catch (IllegalArgumentException exception) {
                return denied(EnforcementState.DENIED_MISSING_POLICY,
                        "LEGAL_ENTITY_OVERLAY_INVALID");
            }
        }
        List<EffectiveWorkbench> workbenches = effectiveWorkbenches(
                tenant.policy(), overlay == null ? null : overlay.policy(),
                installed, deployed, authorizedPaths);
        OffsetDateTime evaluatedAt = now();
        if (!context.revalidateAt().isAfter(evaluatedAt)) {
            return denied(EnforcementState.DENIED_STALE_CONTEXT,
                    "TRUSTED_CONTEXT_STALE_AT_COMPLETION");
        }
        return new EffectiveNavigation(
                SCHEMA_VERSION, NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256,
                NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256,
                EnforcementState.ENFORCED, true,
                tenant.policyId(), tenant.revisionId(), tenant.revisionNumber(),
                overlay == null ? null : overlay.revisionId(),
                overlay == null ? null : overlay.revisionNumber(),
                revisionPin(tenant, overlay, workbenches),
                workbenches,
                null, evaluatedAt);
    }

    private Map<String, List<String>> mergeAuthorizedPaths(
            Map<String, List<String>> pagePaths,
            Map<String, List<String>> policyPaths) {
        if (policyPaths == null || policyPaths.isEmpty()) return pagePaths;
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>(pagePaths);
        policyPaths.forEach((nodeId, paths) -> {
            ArrayList<String> merged = new ArrayList<>(
                    result.getOrDefault(nodeId, List.of()));
            paths.forEach(path -> {
                if (!merged.contains(path)) merged.add(path);
            });
            result.put(nodeId, List.copyOf(merged));
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    /** Same immutable pin and node set must authorize sidebar, landing and deep-link decisions. */
    public boolean permitsDeepLink(
            EffectiveNavigation projection, String presentedRevisionPin, String nodeId) {
        if (projection == null || nodeId == null || nodeId.isBlank()
                || projection.denialReason() != null || !projection.policyEnforced()
                || projection.enforcementState() != EnforcementState.ENFORCED
                || projection.revisionPin() == null
                || presentedRevisionPin == null || presentedRevisionPin.isBlank()
                || !presentedRevisionPin.matches("^nav-v2-[a-f0-9]{64}$")
                || !constantTimeEquals(projection.revisionPin(), presentedRevisionPin)) return false;
        return projection.workbenches().stream()
                .flatMap(workbench -> workbench.groups().stream())
                .flatMap(group -> group.nodes().stream())
                .anyMatch(node -> node.nodeId().equals(nodeId));
    }

    @Transactional(readOnly = true)
    public DeepLinkDecision deepLinkDecision(
            RuntimeContext context,
            String nodeId,
            String requestedPath,
            String presentedRevisionPin) {
        EffectiveNavigation current = projection(context);
        if (!safePath(requestedPath)
                || NavigationPolicyAggregateV2Canonical.EXCLUDED_PATHS.stream()
                .anyMatch(excluded -> requestedPath.equals(excluded)
                        || requestedPath.startsWith(excluded + "?")
                        || requestedPath.startsWith(excluded + "/"))) {
            return deniedDeepLink(nodeId, current.revisionPin(), "PATH_EXCLUDED");
        }
        if (!permitsDeepLink(current, presentedRevisionPin, nodeId)) {
            return deniedDeepLink(nodeId, current.revisionPin(), "NODE_OR_PIN_DENIED");
        }
        EffectiveNode effectiveNode = current.workbenches().stream()
                .flatMap(workbench -> workbench.groups().stream())
                .flatMap(group -> group.nodes().stream())
                .filter(node -> node.nodeId().equals(nodeId))
                .findFirst().orElse(null);
        if (effectiveNode == null || !effectiveNode.routeAliases().contains(requestedPath)) {
            return deniedDeepLink(nodeId, current.revisionPin(), "ROUTE_NOT_ADMITTED");
        }
        if (!eligibilityProvider.pageAuthorizedPath(
                nodeId, requestedPath, permissions(context.permissions()))) {
            return deniedDeepLink(nodeId, current.revisionPin(),
                    "EXACT_PAGE_ROUTE_DENIED");
        }
        if (current.policyEnforced() && !context.revalidateAt().isAfter(now())) {
            return deniedDeepLink(nodeId, current.revisionPin(),
                    "TRUSTED_CONTEXT_STALE_AT_COMPLETION");
        }
        String canonical = NavigationPolicyAggregateV2Canonical.CANONICAL_ROUTES.get(nodeId);
        String admittedTarget = effectiveNode.routeAliases().contains(canonical)
                ? canonical : requestedPath;
        return new DeepLinkDecision(
                true, nodeId, admittedTarget,
                current.revisionPin(), null);
    }

    private DeepLinkDecision deniedDeepLink(
            String nodeId, String currentPin, String reason) {
        return new DeepLinkDecision(false, nodeId, null, currentPin, reason);
    }

    private boolean safePath(String path) {
        return path != null && !path.isBlank() && path.equals(path.strip())
                && path.length() <= 1000 && path.startsWith("/")
                && !path.contains("%") && !path.contains("#")
                && !path.contains("//") && !path.contains("/./")
                && !path.contains("/../") && !path.endsWith("/.")
                && !path.endsWith("/..") && path.indexOf('\r') < 0
                && path.indexOf('\n') < 0;
    }

    private boolean validPublished(
            PublishedAggregate revision,
            ScopeKind expectedScope,
            UUID expectedLegalEntity) {
        if (revision.policyId() == null || revision.revisionId() == null
                || revision.revisionNumber() < 1 || revision.publishedAt() == null
                || revision.payloadDigest() == null
                || !revision.payloadDigest().matches("^[a-f0-9]{64}$")
                || !NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION.equals(
                revision.topologyRevision())
                || !NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256.equals(
                revision.topologySha256())
                || !NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256.equals(
                revision.iaRegisterSha256())
                || !NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256.equals(
                revision.shellRegisterSha256())
                || !NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256.equals(
                revision.taskGroupRegisterSha256())
                || !NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256.equals(
                revision.generatedIaSha256())
                || revision.policy().scopeKind() != expectedScope
                || !java.util.Objects.equals(
                revision.policy().legalEntityPublicId(), expectedLegalEntity)) return false;
        try {
            NavigationPolicyAggregateV2Canonical.validate(revision.policy());
            return revision.payloadDigest().equals(digest(revision.policy()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private EffectiveNavigation unrestricted(
            EnforcementState state,
            Set<String> installed,
            Set<String> deployed,
            Map<String, List<String>> authorizedPaths,
            PublishedAggregate tenant,
            PublishedAggregate overlay) {
        AggregatePolicyDocument canonical =
                NavigationPolicyAggregateV2Canonical.canonicalPolicy(
                        ScopeKind.TENANT, null, RolloutMode.OFF);
        List<EffectiveWorkbench> workbenches = effectiveWorkbenches(
                canonical, null, installed, deployed, authorizedPaths);
        return new EffectiveNavigation(
                SCHEMA_VERSION, NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256,
                NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256,
                state, false,
                tenant == null ? null : tenant.policyId(),
                tenant == null ? null : tenant.revisionId(),
                tenant == null ? null : tenant.revisionNumber(),
                overlay == null ? null : overlay.revisionId(),
                overlay == null ? null : overlay.revisionNumber(),
                tenant == null ? null : revisionPin(tenant, overlay, workbenches),
                workbenches,
                null, now());
    }

    private EffectiveNavigation denied(EnforcementState state, String reason) {
        return new EffectiveNavigation(
                SCHEMA_VERSION, NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256,
                NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256,
                state, true, null, null, null, null, null, null,
                List.of(), reason, now());
    }

    private List<EffectiveWorkbench> effectiveWorkbenches(
            AggregatePolicyDocument tenant,
            AggregatePolicyDocument overlay,
            Set<String> installed,
            Set<String> deployed,
            Map<String, List<String>> authorizedPaths) {
        Map<String, WorkbenchPolicy> tenantPolicies = policies(tenant);
        Map<String, WorkbenchPolicy> overlayPolicies = overlay == null
                ? Map.of() : policies(overlay);
        List<EffectiveWorkbench> result = new ArrayList<>();
        for (NavigationPolicyAggregateV2Canonical.Workbench canonical
                : NavigationPolicyAggregateV2Canonical.WORKBENCHES) {
            WorkbenchPolicy baseline = tenantPolicies.get(canonical.id());
            WorkbenchPolicy restricted = overlayPolicies.getOrDefault(
                    canonical.id(), baseline);
            if (!baseline.enabled() || !restricted.enabled()) continue;
            Set<String> baselineGroups = Set.copyOf(baseline.enabledGroupIds());
            Set<String> restrictedGroups = Set.copyOf(restricted.enabledGroupIds());
            Set<String> baselineNodes = Set.copyOf(baseline.enabledNodeIds());
            Set<String> restrictedNodes = Set.copyOf(restricted.enabledNodeIds());
            List<EffectiveGroup> groups = new ArrayList<>();
            for (int groupOrder = 0; groupOrder < canonical.groups().size(); groupOrder++) {
                NavigationPolicyAggregateV2Canonical.Group group =
                        canonical.groups().get(groupOrder);
                if (!baselineGroups.contains(group.id())
                        || !restrictedGroups.contains(group.id())) continue;
                List<EffectiveNode> nodes = new ArrayList<>();
                for (int nodeOrder = 0; nodeOrder < group.nodeIds().size(); nodeOrder++) {
                    String nodeId = group.nodeIds().get(nodeOrder);
                    List<String> routeAliases = authorizedPaths.getOrDefault(
                            nodeId, List.of());
                    if (baselineNodes.contains(nodeId) && restrictedNodes.contains(nodeId)
                            && installed.contains(nodeId) && deployed.contains(nodeId)
                            && ServerNavigationPolicyEligibilityProvider
                            .IMPLEMENTATION_NODE_IDS.contains(nodeId)
                            && !routeAliases.isEmpty()) {
                        nodes.add(new EffectiveNode(nodeId, nodeOrder, routeAliases));
                    }
                }
                if (nodes.isEmpty()) continue;
                String defaultNode = nodes.stream().anyMatch(node -> node.nodeId().equals(
                        group.defaultNodeId())) ? group.defaultNodeId()
                        : nodes.getFirst().nodeId();
                groups.add(new EffectiveGroup(
                        group.id(), groupOrder, defaultNode, nodes));
            }
            if (groups.isEmpty()) continue;
            String landing = groups.stream()
                    .flatMap(group -> group.nodes().stream())
                    .filter(node -> node.nodeId().equals(canonical.defaultNodeId()))
                    .map(EffectiveNode::nodeId).findFirst()
                    .orElse(groups.getFirst().nodes().getFirst().nodeId());
            result.add(new EffectiveWorkbench(
                    canonical.id(), canonical.displayOrder(), landing, groups));
        }
        return List.copyOf(result);
    }

    private Map<String, WorkbenchPolicy> policies(AggregatePolicyDocument policy) {
        LinkedHashMap<String, WorkbenchPolicy> result = new LinkedHashMap<>();
        policy.workbenches().forEach(workbench -> result.put(
                workbench.workbenchId(), workbench));
        return Map.copyOf(result);
    }

    private String revisionPin(
            PublishedAggregate tenant,
            PublishedAggregate overlay,
            List<EffectiveWorkbench> workbenches) {
        LinkedHashMap<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("topologyRevision", NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION);
        material.put("topologySha256", NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256);
        material.put("iaRegisterSha256", NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256);
        material.put("shellRegisterSha256", NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256);
        material.put("taskGroupRegisterSha256", NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256);
        material.put("generatedIaSha256", NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256);
        material.put("tenantRevisionId", tenant.revisionId());
        material.put("tenantRevisionNumber", tenant.revisionNumber());
        material.put("tenantDigest", tenant.payloadDigest());
        if (overlay != null) {
            material.put("legalEntityRevisionId", overlay.revisionId());
            material.put("legalEntityRevisionNumber", overlay.revisionNumber());
            material.put("legalEntityDigest", overlay.payloadDigest());
        }
        List<List<Object>> effectivePaths = workbenches.stream()
                .flatMap(workbench -> workbench.groups().stream())
                .flatMap(group -> group.nodes().stream())
                .map(node -> List.<Object>of(node.nodeId(), node.routeAliases()))
                .toList();
        material.put("effectivePaths", effectivePaths);
        return "nav-v2-" + digest(material);
    }

    private void requireRuntime(RuntimeContext context) {
        if (context == null || context.tenantId() < 1 || context.subjectId() < 1
                || context.subjectPublicId() == null
                || context.contextScopeKey() == null
                || !context.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")
                || context.revalidateAt() == null
                || (!permissions(context.permissions()).contains("APP.HCM:VIEW")
                && !permissions(context.permissions()).contains("APP.HRIS:VIEW"))) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "HRIS_NAV_V2_RUNTIME_CONTEXT_INVALID");
        }
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

    private boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String digest(Object value) {
        try {
            byte[] source = objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("HRIS_NAV_V2_DIGEST_FAILED", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
