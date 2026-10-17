package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Objects;
import java.util.Set;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV3Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAuthorityDecisionProvider.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.EnforcementState;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.RuntimeContext;

/**
 * R6-C aggregate. V2 remains the historical PAGE/topology authority; V3 can add only the exact
 * v47 PAGE set and Auth-owned POLICY aliases after every binding and freshness check passes.
 */
@Service
public class NavigationPolicyAggregateV3Service {

    private static final Logger log = LoggerFactory.getLogger(
            NavigationPolicyAggregateV3Service.class);
    private static final String PRODUCT = "hcm";
    private static final String SURFACE = "hcm.personal";
    private static final String SELF = "SELF";

    private final NavigationPolicyAggregateV2Service v2;
    private final NavigationPolicyAuthorityDecisionProvider authority;
    private final NavigationPolicyEligibilityProvider eligibility;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean shadowAuthorityEvaluationEnabled;

    @Autowired
    public NavigationPolicyAggregateV3Service(
            NavigationPolicyAggregateV2Service v2,
            NavigationPolicyAuthorityDecisionProvider authority,
            NavigationPolicyEligibilityProvider eligibility,
            ObjectMapper objectMapper,
            @Value("${dwp.hris.navigation-policy.v3-shadow-authority-evaluation-enabled:false}")
            boolean shadowAuthorityEvaluationEnabled) {
        this(v2, authority, eligibility, objectMapper, Clock.systemUTC(),
                shadowAuthorityEvaluationEnabled);
    }

    NavigationPolicyAggregateV3Service(
            NavigationPolicyAggregateV2Service v2,
            NavigationPolicyAuthorityDecisionProvider authority,
            NavigationPolicyEligibilityProvider eligibility,
            ObjectMapper objectMapper,
            Clock clock,
            boolean shadowAuthorityEvaluationEnabled) {
        this.v2 = Objects.requireNonNull(v2);
        this.authority = Objects.requireNonNull(authority);
        this.eligibility = Objects.requireNonNull(eligibility);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.shadowAuthorityEvaluationEnabled = shadowAuthorityEvaluationEnabled;
    }

    @Transactional(readOnly = true)
    public EffectiveNavigationV3 projection(
            RuntimeContext context,
            AccessMode activeAccessMode) {
        EffectiveNavigation baseline = v2.projection(context);
        if (baseline.enforcementState() != EnforcementState.ENFORCED
                || !baseline.policyEnforced()) {
            if (baseline.enforcementState() == EnforcementState.SHADOW
                    && shadowAuthorityEvaluationEnabled) {
                shadowEvaluate(context, activeAccessMode);
            }
            return inactive(context, baseline);
        }
        requireTenantSelfContext(context, activeAccessMode);
        AuthorityRequest request = authorityRequest(context, activeAccessMode);
        NavigationPolicyAggregateV3Catalog.SealedAuthority sealed =
                NavigationPolicyAggregateV3Catalog.seal(
                        request, authority.evaluate(request), now());
        EffectiveNavigation effective = v2.projectionWithV47Paths(
                context, sealed.admittedPaths(),
                eligibility.v47PageAuthorizedPaths(
                        permissions(context.permissions())));
        requireStablePolicyBaseline(baseline, effective);
        if (!context.revalidateAt().isAfter(now())
                || !sealed.snapshot().freshUntil().isAfter(now())) {
            throw unavailable("HRIS_NAV_V3_AUTHORITY_STALE_AT_COMPLETION");
        }

        Map<String, RouteDecision> admittedByKey = new LinkedHashMap<>();
        sealed.admittedDecisions().forEach(decision -> admittedByKey.put(
                routeKey(decision.nodeId(), decision.path()), decision));
        Set<String> effectivePolicyKeys = effective.workbenches().stream()
                .flatMap(workbench -> workbench.groups().stream())
                .flatMap(group -> group.nodes().stream())
                .flatMap(node -> node.routeAliases().stream().map(
                        path -> routeKey(node.nodeId(), path)))
                .filter(key -> {
                    int separator = key.indexOf('\n');
                    return NavigationPolicyAggregateV3Catalog.routeFor(
                            key.substring(0, separator),
                            key.substring(separator + 1)) != null;
                })
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!admittedByKey.keySet().containsAll(effectivePolicyKeys)) {
            throw unavailable("HRIS_NAV_V3_EFFECTIVE_POLICY_WITHOUT_DECISION");
        }
        List<AdmittedPolicyRoute> admitted = sealed.admittedDecisions().stream()
                .filter(decision -> effectivePolicyKeys.contains(
                        routeKey(decision.nodeId(), decision.path())))
                .map(this::view)
                .toList();
        String pin = revisionPin(effective, sealed.snapshot(), admitted);
        OffsetDateTime evaluatedAt = now();
        if (!context.revalidateAt().isAfter(evaluatedAt)
                || !sealed.snapshot().freshUntil().isAfter(evaluatedAt)) {
            throw unavailable("HRIS_NAV_V3_AUTHORITY_STALE_AT_COMPLETION");
        }
        return new EffectiveNavigationV3(
                NavigationPolicyAggregateV3Dtos.SCHEMA_VERSION,
                effective.topologyRevision(), effective.topologySha256(),
                effective.iaRegisterSha256(), effective.shellRegisterSha256(),
                effective.taskGroupRegisterSha256(), effective.generatedIaSha256(),
                NavigationPolicyAggregateV3Catalog.REGISTRY_VERSION,
                NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM,
                NavigationPolicyRuntimeOwnerOverlay.REVISION,
                NavigationPolicyRuntimeOwnerOverlay.CHECKSUM,
                runtimeOwnerOverlayRecords(),
                effective.enforcementState(), true,
                effective.tenantPolicyId(), effective.tenantRevisionId(),
                effective.tenantRevisionNumber(), pin,
                context.contextScopeKey(), request.expectedScopeKey(),
                sealed.snapshot().snapshotRevision(),
                sealed.snapshot().decisionRevision(),
                sealed.snapshot().authRevision(),
                sealed.snapshot().policyRevision(),
                sealed.snapshot().relationshipRevision(),
                sealed.snapshot().freshUntil(), sealed.snapshot().decisions().size(),
                admitted, effective.workbenches(), null, evaluatedAt);
    }

    /** One current V3 projection owns sidebar, landing and deep-link authority. */
    @Transactional(readOnly = true)
    public DeepLinkDecisionV3 deepLinkDecision(
            RuntimeContext context,
            AccessMode activeAccessMode,
            String nodeId,
            String requestedPath,
            String presentedRevisionPin) {
        EffectiveNavigationV3 current = projection(context, activeAccessMode);
        if (!safePath(requestedPath)) {
            return deniedDeepLink(
                    nodeId, requestedPath, current.revisionPin(), "PATH_INVALID");
        }
        if (current.enforcementState() != EnforcementState.ENFORCED
                || !current.policyEnforced() || current.revisionPin() == null
                || presentedRevisionPin == null
                || !presentedRevisionPin.matches("^nav-v3-[a-f0-9]{64}$")
                || !constantTimeEquals(
                current.revisionPin(), presentedRevisionPin)) {
            return deniedDeepLink(
                    nodeId, requestedPath, current.revisionPin(),
                    "NODE_OR_PIN_DENIED");
        }
        EffectiveNode node = current.workbenches().stream()
                .flatMap(workbench -> workbench.groups().stream())
                .flatMap(group -> group.nodes().stream())
                .filter(candidate -> candidate.nodeId().equals(nodeId))
                .findFirst().orElse(null);
        if (node == null || !node.routeAliases().contains(requestedPath)) {
            return deniedDeepLink(
                    nodeId, requestedPath, current.revisionPin(),
                    "ROUTE_NOT_ADMITTED");
        }
        boolean policyRoute = current.admittedPolicyRoutes().stream().anyMatch(
                decision -> decision.nodeId().equals(nodeId)
                        && decision.path().equals(requestedPath));
        if (!policyRoute && !eligibility.v47PageAuthorizedPath(
                nodeId, requestedPath, permissions(context.permissions()))) {
            return deniedDeepLink(
                    nodeId, requestedPath, current.revisionPin(),
                    "EXACT_PAGE_ROUTE_DENIED");
        }
        OffsetDateTime completedAt = now();
        if (!context.revalidateAt().isAfter(completedAt)
                || current.authorityFreshUntil() == null
                || !current.authorityFreshUntil().isAfter(completedAt)) {
            return deniedDeepLink(
                    nodeId, requestedPath, current.revisionPin(),
                    "AUTHORITY_STALE_AT_COMPLETION");
        }
        return new DeepLinkDecisionV3(
                true, nodeId, requestedPath, requestedPath,
                NavigationPolicyTopologySuccessor.CANONICAL_ROUTES.get(nodeId),
                current.revisionPin(), null);
    }

    private EffectiveNavigationV3 inactive(
            RuntimeContext context,
            EffectiveNavigation baseline) {
        return new EffectiveNavigationV3(
                NavigationPolicyAggregateV3Dtos.SCHEMA_VERSION,
                baseline.topologyRevision(), baseline.topologySha256(),
                baseline.iaRegisterSha256(), baseline.shellRegisterSha256(),
                baseline.taskGroupRegisterSha256(), baseline.generatedIaSha256(),
                NavigationPolicyAggregateV3Catalog.REGISTRY_VERSION,
                NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM,
                NavigationPolicyRuntimeOwnerOverlay.REVISION,
                NavigationPolicyRuntimeOwnerOverlay.CHECKSUM,
                runtimeOwnerOverlayRecords(),
                baseline.enforcementState(), baseline.policyEnforced(),
                baseline.tenantPolicyId(), baseline.tenantRevisionId(),
                baseline.tenantRevisionNumber(), null,
                context == null ? null : context.contextScopeKey(), null,
                null, null, null, null, null, null, 0,
                List.of(), baseline.workbenches(), baseline.denialReason(),
                baseline.evaluatedAt());
    }

    private void shadowEvaluate(
            RuntimeContext context,
            AccessMode activeAccessMode) {
        try {
            requireTenantSelfContext(context, activeAccessMode);
            AuthorityRequest request = authorityRequest(context, activeAccessMode);
            NavigationPolicyAggregateV3Catalog.seal(
                    request, authority.evaluate(request), now());
        } catch (RuntimeException exception) {
            // Shadow evaluation is diagnostic only and must never expose a POLICY route or
            // change the static PAGE projection. Avoid logging identity or authority material.
            log.warn("HRIS_NAV_V3_SHADOW_AUTHORITY_EVALUATION_FAILED");
        }
    }

    private AuthorityRequest authorityRequest(
            RuntimeContext context,
            AccessMode activeAccessMode) {
        String selfScopeKey = ProductSurfaceScopeKey.key(
                context.tenantId(), context.subjectId(), PRODUCT, SURFACE, SELF, SELF);
        return new AuthorityRequest(
                context.tenantId(), context.subjectId(), activeAccessMode,
                context.subjectPublicId(), selfScopeKey,
                NavigationPolicyAggregateV3Catalog.REGISTRY_VERSION,
                NavigationPolicyAggregateV3Catalog.REGISTRY_CHECKSUM, null);
    }

    private void requireTenantSelfContext(
            RuntimeContext context,
            AccessMode activeAccessMode) {
        if (context == null || context.requestedLegalEntityPublicId() != null
                || activeAccessMode == null || context.tenantId() < 1
                || context.subjectId() < 1 || context.subjectPublicId() == null
                || context.contextScopeKey() == null
                || !context.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")
                || context.revalidateAt() == null
                || !context.revalidateAt().isAfter(now())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN, "HRIS_NAV_V3_TENANT_SELF_CONTEXT_INVALID");
        }
    }

    private void requireStablePolicyBaseline(
            EffectiveNavigation baseline,
            EffectiveNavigation effective) {
        if (effective.enforcementState() != EnforcementState.ENFORCED
                || !effective.policyEnforced()
                || !Objects.equals(
                baseline.tenantPolicyId(), effective.tenantPolicyId())
                || !Objects.equals(
                baseline.tenantRevisionId(), effective.tenantRevisionId())
                || !Objects.equals(
                baseline.tenantRevisionNumber(), effective.tenantRevisionNumber())
                || effective.legalEntityRevisionId() != null
                || effective.legalEntityRevisionNumber() != null) {
            throw unavailable("HRIS_NAV_V3_POLICY_CHANGED_DURING_EVALUATION");
        }
    }

    private AdmittedPolicyRoute view(RouteDecision decision) {
        ScopeBinding scope = decision.scopeBinding();
        return new AdmittedPolicyRoute(
                decision.nodeId(), decision.path(), decision.routeContractKey(),
                decision.profileKey(), decision.targetBinding(),
                new AuthorityScopeBinding(
                        scope.kind(), scope.key(), scope.personPublicId()),
                decision.decision(), decision.routeGrantRef(),
                decision.decisionRevision(), decision.authRevision(),
                decision.policyRevision(), decision.relationshipRevision(),
                decision.freshUntil());
    }

    private String revisionPin(
            EffectiveNavigation effective,
            AuthoritySnapshot snapshot,
            List<AdmittedPolicyRoute> admitted) {
        Map<String, AdmittedPolicyRoute> policyByKey = admitted.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        value -> routeKey(value.nodeId(), value.path()), value -> value));
        ArrayList<List<Object>> tuples = new ArrayList<>();
        effective.workbenches().stream()
                .flatMap(workbench -> workbench.groups().stream())
                .flatMap(group -> group.nodes().stream())
                .forEach(node -> node.routeAliases().forEach(path -> {
                    AdmittedPolicyRoute decision = policyByKey.get(
                            routeKey(node.nodeId(), path));
                    if (decision == null) {
                        tuples.add(List.of("PAGE", node.nodeId(), path));
                    } else {
                        tuples.add(List.of(
                                "POLICY", decision.nodeId(), decision.path(),
                                decision.routeContractKey(), decision.profileKey(),
                                decision.targetBinding(), decision.scopeBinding().kind(),
                                decision.scopeBinding().key(),
                                decision.scopeBinding().personPublicId(),
                                decision.decision(), decision.routeGrantRef(),
                                decision.decisionRevision(), decision.authRevision(),
                                decision.policyRevision(), decision.relationshipRevision(),
                                decision.freshUntil()));
                    }
                }));
        tuples.sort(Comparator.comparing(value -> value.stream()
                .map(Object::toString).collect(
                        java.util.stream.Collectors.joining("\n"))));
        LinkedHashMap<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", NavigationPolicyAggregateV3Dtos.SCHEMA_VERSION);
        material.put("v2AggregatePin", effective.revisionPin());
        material.put("authorizationRegistryVersion", snapshot.registryVersion());
        material.put("authorizationRegistryChecksum", snapshot.registryChecksum());
        material.put("runtimeOwnerOverlayRevision",
                NavigationPolicyRuntimeOwnerOverlay.REVISION);
        material.put("runtimeOwnerOverlayChecksum",
                NavigationPolicyRuntimeOwnerOverlay.CHECKSUM);
        material.put("runtimeOwnerOverlayRecords", runtimeOwnerOverlayRecords());
        material.put("authoritySnapshotRevision", snapshot.snapshotRevision());
        material.put("authorityDecisionRevision", snapshot.decisionRevision());
        material.put("authorityAuthRevision", snapshot.authRevision());
        material.put("authorityPolicyRevision", snapshot.policyRevision());
        material.put("authorityRelationshipRevision", snapshot.relationshipRevision());
        material.put("authorityFreshUntil", snapshot.freshUntil());
        material.put("effectiveRouteTuples", tuples);
        return "nav-v3-" + digest(material);
    }

    private List<RuntimeOwnerOverlayRecord> runtimeOwnerOverlayRecords() {
        return NavigationPolicyRuntimeOwnerOverlay.RECORDS.stream().map(record ->
                new RuntimeOwnerOverlayRecord(
                        record.nodeId(), record.historicalOwner(),
                        record.effectiveRuntimeOwner(), record.canonicalPath(),
                        record.compatibilityPath(), record.routeContractKey(),
                        new RuntimeOwnerFirstReadBinding(
                                record.method(), record.gatewayPath(), record.serviceKey(),
                                record.servicePath()),
                        record.registryVersion(), record.registryChecksum(),
                        record.evidenceDigest())).toList();
    }

    private DeepLinkDecisionV3 deniedDeepLink(
            String nodeId,
            String requestedPath,
            String currentPin,
            String reason) {
        return new DeepLinkDecisionV3(
                false, nodeId, requestedPath, null, null, currentPin, reason);
    }

    private boolean safePath(String path) {
        return path != null && !path.isBlank() && path.equals(path.strip())
                && path.length() <= 1_000 && path.startsWith("/")
                && !path.contains("%") && !path.contains("#")
                && !path.contains("//") && !path.contains("/./")
                && !path.contains("/../") && !path.endsWith("/.")
                && !path.endsWith("/..") && path.indexOf('\r') < 0
                && path.indexOf('\n') < 0;
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
            throw new IllegalStateException("HRIS_NAV_V3_DIGEST_FAILED", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String routeKey(String nodeId, String path) {
        return nodeId + '\n' + path;
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
