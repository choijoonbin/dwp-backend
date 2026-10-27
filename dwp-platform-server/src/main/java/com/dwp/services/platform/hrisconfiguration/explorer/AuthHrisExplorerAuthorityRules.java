package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceScopeKey;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthorityRequest;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthoritySnapshot;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerReceiptAuthoritySource.CurrentExplorerReceiptAuthority;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource.CurrentReceiptAuthority;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource.ForwardedReceiptAuthority;

/** Pure origin bindings, revision seals and authority-shape validation. */
final class AuthHrisExplorerAuthorityRules {
    static final String EXPLORER_PAGE_ROUTE = "route.hcm.utility.explorer.page";
    static final String RECEIPT_ROUTE =
            "route.hcm.utility.explorer-command-receipt.data";
    static final String EXPLORER_PURPOSE = "HRIS_APP_DISCOVERY";
    static final String RECEIPT_PURPOSE = "COMMAND_STATUS";
    static final String PLATFORM_ORIGIN_PURPOSE = "HRIS_SYSTEM_ADMIN";
    static final String CONTROLLED_TRANSFER_ORIGIN_PURPOSE =
            "HRIS_INTEGRATION_ADMIN";
    static final String INTEGRATION_CONTROL_PLANE_ORIGIN_PURPOSE =
            "HRIS_INTEGRATION_ADMIN";
    static final String CONTROLLED_TRANSFER_ACTION =
            "CONTROLLED_TRANSFER_CREATE";

    static final String AUTHORITY_PATH =
            "/internal/auth/v1/product-surface-authority/evaluate";
    static final String IDENTITY_PATH =
            "/internal/identity/v1/tenants/{tenantId}/users/{userId}";
    static final String PEOPLE_PATH =
            "/internal/people/v1/product-surface-eligibility/evaluate";
    static final String PRODUCT_TOKEN_HEADER = "X-DWP-Product-Surface-Token";
    static final String IDENTITY_TOKEN_HEADER = "X-DWP-Identity-Sync-Token";
    static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    static final String USER_HEADER = "X-DWP-User-ID";
    static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    static final String PERSON_HEADER = "X-DWP-Person-Public-ID";
    static final String SERVICE_IDENTITY = "dwp-platform-server";
    static final String PRODUCT = "hcm";
    static final String UTILITY_SURFACE = "hcm.utility";
    static final String MANAGEMENT_SURFACE = "hcm.management";
    static final String SHELL_CAPABILITY = "hcm.app.shell.view";
    static final String RECEIPT_CAPABILITY = "hcm.command-receipt.view";
    static final String CONTROLLED_TRANSFER_CREATE_CAPABILITY =
            "hcm.integration.transfer.create";
    static final long MAX_TIMEOUT_MILLIS = 5_000L;
    static final Set<String> TERMINAL_AUTH_DECISIONS = Set.of(
            "ALLOWED", "APP_DENIED", "SURFACE_DENIED", "ROUTE_DENIED",
            "SCOPE_SELECTION_REQUIRED", "SCOPE_INVALID", "EXPIRED",
            "ACTIVATION_REQUIRED", "STEP_UP_REQUIRED", "SOD_CONFLICT",
            "SUPPORT_SCOPE_DENIED");
    static final Set<String> TERMINAL_PEOPLE_DECISIONS = Set.of(
            "ALLOWED", "SURFACE_DENIED", "SCOPE_INVALID", "AUTHORITY_UNAVAILABLE");

    static final Map<String, String> EXPLORER_ORIGIN_ROUTES_BY_ACTION = Map.of(
            JdbcHrisExplorerRepository.PREFERENCES_ACTION,
            "route.hcm.utility.explorer-preferences-update.action",
            JdbcHrisExplorerRepository.RECENT_ACTION,
            "route.hcm.utility.explorer-recent-create.action");
    static final Map<String, String> EXISTING_PLATFORM_ORIGIN_ROUTES_BY_ACTION =
            Map.ofEntries(
            Map.entry("configuration.version.create",
                    "route.hcm.configuration.configuration-version-create.action"),
            Map.entry("configuration.version.validate",
                    "route.hcm.configuration.configuration-version-validate.action"),
            Map.entry("configuration.version.simulate",
                    "route.hcm.configuration.configuration-version-simulate.action"),
            Map.entry("configuration.version.submit",
                    "route.hcm.configuration.configuration-version-submit.action"),
            Map.entry("configuration.version.approve",
                    "route.hcm.configuration.configuration-version-approve.action"),
            Map.entry("configuration.version.schedule",
                    "route.hcm.configuration.configuration-version-schedule.action"),
            Map.entry("configuration.version.publish",
                    "route.hcm.configuration.configuration-version-publish.action"),
            Map.entry("configuration.version.rollback",
                    "route.hcm.configuration.configuration-version-rollback.action"),
            Map.entry("configuration.version.retire",
                    "route.hcm.configuration.configuration-version-retire.action"),
            Map.entry("CREATE_DRAFT",
                    "route.hcm.configuration.navigation-policy-draft-create.action"),
            Map.entry("UPDATE_DRAFT",
                    "route.hcm.configuration.navigation-policy-draft-update.action"),
            Map.entry("VALIDATE",
                    "route.hcm.configuration.navigation-policy-draft-validate.action"),
            Map.entry("SUBMIT",
                    "route.hcm.configuration.navigation-policy-draft-submit.action"),
            Map.entry("APPROVE",
                    "route.hcm.configuration.navigation-policy-revision-approve.action"),
            Map.entry("PUBLISH",
                    "route.hcm.configuration.navigation-policy-revision-publish.action"),
            Map.entry("RESTORE",
                    "route.hcm.configuration.navigation-policy-revision-restore.action"),
            Map.entry(CONTROLLED_TRANSFER_ACTION,
                    "route.hcm.management.controlled-transfer-create.action"));
    static final Map<String, String> AUTOMATION_ORIGIN_ROUTES_BY_ACTION = Map.ofEntries(
            Map.entry("hris.action.sys.post.platform.v1.admin.hris.automations",
                    "route.hcm.configuration.automation-definition-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.runs",
                    "route.hcm.configuration.automation-run-queue.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.retry",
                    "route.hcm.configuration.automation-run-retry.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions",
                    "route.hcm.configuration.automation-version-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.validate",
                    "route.hcm.configuration.automation-version-validate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.submit",
                    "route.hcm.configuration.automation-version-submit.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.approve",
                    "route.hcm.configuration.automation-version-approve.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.activate",
                    "route.hcm.configuration.automation-version-activate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.schedules",
                    "route.hcm.configuration.automation-schedule-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.submit",
                    "route.hcm.configuration.automation-schedule-submit.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.approve",
                    "route.hcm.configuration.automation-schedule-approve.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.activate",
                    "route.hcm.configuration.automation-schedule-activate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.suspend",
                    "route.hcm.configuration.automation-schedule-suspend.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.cancel",
                    "route.hcm.configuration.automation-run-cancel.action"));
    static final Map<String, String> INTEGRATION_ORIGIN_ROUTES_BY_ACTION = Map.ofEntries(
            Map.entry("hris.action.sys.post.platform.v1.admin.hris.connectors",
                    "route.hcm.configuration.connector-definition-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.test",
                    "route.hcm.configuration.connector-test.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.dry-runs",
                    "route.hcm.configuration.connector-mapping-dry-run.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions",
                    "route.hcm.configuration.connector-execution-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.validate",
                    "route.hcm.configuration.connector-definition-validate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.submit",
                    "route.hcm.configuration.connector-definition-submit.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.approve",
                    "route.hcm.configuration.connector-definition-approve.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.activate",
                    "route.hcm.configuration.connector-definition-activate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings",
                    "route.hcm.configuration.connector-mapping-version-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.validate",
                    "route.hcm.configuration.connector-mapping-version-validate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.submit",
                    "route.hcm.configuration.connector-mapping-version-submit.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.approve",
                    "route.hcm.configuration.connector-mapping-version-approve.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.activate",
                    "route.hcm.configuration.connector-mapping-version-activate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.reconcile",
                    "route.hcm.configuration.connector-execution-reconcile.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.retry",
                    "route.hcm.configuration.connector-execution-retry.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.cancel",
                    "route.hcm.configuration.connector-execution-cancel.action"));
    static final Map<String, String> PLATFORM_ORIGIN_ROUTES_BY_ACTION =
            platformOriginRoutes();

    static Map<String, String> platformOriginRoutes() {
        Map<String, String> routes = new LinkedHashMap<>(
                EXISTING_PLATFORM_ORIGIN_ROUTES_BY_ACTION);
        AUTOMATION_ORIGIN_ROUTES_BY_ACTION.forEach((action, route) -> {
            if (routes.putIfAbsent(action, route) != null) {
                throw new IllegalStateException(
                        "Duplicate Platform receipt originating action: " + action);
            }
        });
        INTEGRATION_ORIGIN_ROUTES_BY_ACTION.forEach((action, route) -> {
            if (routes.putIfAbsent(action, route) != null) {
                throw new IllegalStateException(
                        "Duplicate Platform receipt originating action: " + action);
            }
        });
        return Map.copyOf(routes);
    }

    private final Clock clock;

    AuthHrisExplorerAuthorityRules(Clock clock) {
        this.clock = clock;
    }

    boolean validTerminal(AuthorityView result) {
        return result != null && TERMINAL_AUTH_DECISIONS.contains(result.decision())
                && canonical(result.authRevision(), 500)
                && canonical(result.policyRevision(), 500);
    }

    boolean validAllowed(
            AuthorityView result,
            AuthorityRequest request,
            String surfaceKey,
            String expectedContextKey) {
        OffsetDateTime now = now();
        if (!PRODUCT.equals(result.productKey()) || !surfaceKey.equals(result.surfaceKey())
                || !request.activeAccessMode().equals(result.accessMode())
                || !canonical(result.contextKey(), 500)
                || !result.contextKey().matches("^psc-[a-f0-9]{64}$")
                || (expectedContextKey != null
                && !expectedContextKey.equals(result.contextKey()))
                || !canonical(result.routeGrantRef(), 500)
                || result.requiresProductEligibility() == null
                || result.revalidateAt() == null || !result.revalidateAt().isAfter(now)
                || (result.validUntil() != null && !result.validUntil().isAfter(now))
                || result.scopes() == null || result.scopes().isEmpty()
                || result.effectiveGrants() == null || result.effectiveGrants().isEmpty()) {
            return false;
        }
        Set<String> scopeKeys = new LinkedHashSet<>();
        for (ScopeView scope : result.scopes()) {
            if (scope == null || !canonical(scope.key(), 500)
                    || !scope.key().matches("^scope-[a-f0-9]{32}$")
                    || !canonical(scope.kind(), 80) || scope.readOnly() == null
                    || (scope.validUntil() != null && !scope.validUntil().isAfter(now))
                    || !scopeKeys.add(scope.key())) return false;
        }
        for (GrantView grant : result.effectiveGrants()) {
            if (grant == null || !canonical(grant.grantKind(), 40)
                    || grant.scopeKeys() == null || grant.scopeKeys().isEmpty()
                    || !scopeKeys.containsAll(grant.scopeKeys())
                    || grant.readOnly() == null
                    || (grant.validUntil() != null && !grant.validUntil().isAfter(now))) {
                return false;
            }
        }
        return true;
    }

    boolean validEligibility(
            EligibilityView result,
            AuthorityRequest request,
            AuthorityView authority) {
        if (result == null || !TERMINAL_PEOPLE_DECISIONS.contains(result.decision())
                || !canonical(result.productRelationshipRevision(), 500)
                || !canonical(result.targetPopulationRevision(), 500)
                || result.scopes() == null) return false;
        if (!"ALLOWED".equals(result.decision())) return result.scopes().isEmpty();
        if (result.scopes().isEmpty() || result.revalidateAt() == null
                || !result.revalidateAt().isAfter(now())) return false;
        Set<String> sources = new LinkedHashSet<>();
        for (EligibleView scope : result.scopes()) {
            if (scope == null || !canonical(scope.sourceScopeKey(), 500)
                    || !canonical(scope.key(), 500) || !canonical(scope.kind(), 80)
                    || !canonical(scope.displayName(), 500)
                    || scope.isDefault() == null || scope.readOnly() == null
                    || scope.validUntil() == null || !scope.validUntil().isAfter(now())
                    || !sources.add(scope.sourceScopeKey())) return false;
        }
        Set<String> requested = authority.scopes().stream()
                .map(ScopeView::key).collect(Collectors.toUnmodifiableSet());
        return requested.containsAll(sources)
                && request.tenantId() > 0 && request.actorId() > 0;
    }

    void requireStableTerminal(ProofView baseline, ProofView candidate) {
        if (candidate == null || !sameAuthRevisions(baseline, candidate)) {
            throw unavailable("Explorer descendant authority is unavailable or changed");
        }
    }

    boolean sameAuthRevisions(ProofView left, ProofView right) {
        return left != null && right != null
                && left.authRevision().equals(right.authRevision())
                && left.policyRevision().equals(right.policyRevision());
    }

    boolean hasCapability(ProofView proof, String expectedCapability) {
        return proof.allowed() && proof.grants().stream().anyMatch(grant ->
                "CAPABILITY".equals(grant.grantKind())
                        && expectedCapability.equals(grant.capabilityContractKey())
                        && grant.scopeKeys() != null && !grant.scopeKeys().isEmpty()
                        && proof.scopes().stream().map(ScopeView::key)
                        .anyMatch(grant.scopeKeys()::contains));
    }

    boolean hasExactPlatformReceiptGrant(ProofView proof) {
        return proof.grants().size() == 1 && hasCapability(proof, RECEIPT_CAPABILITY)
                && "PERMISSION_AND_RELATIONSHIP".equals(
                proof.grants().getFirst().authorityMode())
                && proof.scopes().size() == 1
                && "TARGET_POPULATION".equals(proof.scopes().getFirst().kind())
                && proof.selectedScopeKey().equals(proof.scopes().getFirst().key())
                && proof.selectedScopeKey().matches("^scope-[a-f0-9]{32}$");
    }

    String platformReceiptScope(AuthorityRequest request) {
        return ProductSurfaceScopeKey.key(
                request.tenantId(), request.actorId(), PRODUCT, UTILITY_SURFACE,
                "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION");
    }

    <T extends ScopeView> T selectRawScope(List<T> scopes, String expectedScopeKey) {
        if (expectedScopeKey != null) {
            return scopes.stream().filter(scope -> expectedScopeKey.equals(scope.key()))
                    .findFirst().orElse(null);
        }
        List<T> defaults = scopes.stream()
                .filter(scope -> Boolean.TRUE.equals(scope.isDefault())).toList();
        if (scopes.size() == 1 && defaults.size() == 1) return scopes.getFirst();
        return defaults.size() == 1 ? defaults.getFirst() : null;
    }

    <T extends ScopeView> T selectDerivedScope(
            List<T> scopes, String expectedScopeKey) {
        if (expectedScopeKey != null) {
            if (!HcmEligibilityScopeKey.isCanonical(expectedScopeKey)) return null;
            return scopes.stream().filter(scope -> expectedScopeKey.equals(scope.key()))
                    .findFirst().orElse(null);
        }
        List<T> defaults = scopes.stream()
                .filter(scope -> Boolean.TRUE.equals(scope.isDefault())).toList();
        if (scopes.size() == 1 && defaults.size() == 1) return scopes.getFirst();
        return defaults.size() == 1 ? defaults.getFirst() : null;
    }

    OffsetDateTime authorityFreshness(
            OffsetDateTime forwarded,
            AuthorityView authority,
            EligibilityView eligibility,
            ScopeView selected) {
        List<OffsetDateTime> values = new ArrayList<>();
        values.add(forwarded);
        values.add(authority.revalidateAt());
        values.add(authority.validUntil());
        values.add(selected.validUntil());
        authority.effectiveGrants().forEach(grant -> values.add(grant.validUntil()));
        authority.scopes().forEach(scope -> values.add(scope.validUntil()));
        if (eligibility != null) {
            values.add(eligibility.revalidateAt());
            eligibility.scopes().forEach(scope -> values.add(scope.validUntil()));
        }
        return earliest(values.toArray(OffsetDateTime[]::new));
    }

    static String decisionRevision(
            long tenantId,
            long actorId,
            String activeAccessMode,
            String authRevision,
            String policyRevision,
            String relationshipRevision,
            String populationRevision,
            String surfaceKey,
            String routeContractKey,
            String routeGrantRef) {
        String compositeMaterial = String.join("\n",
                Long.toString(tenantId), Long.toString(actorId), activeAccessMode,
                Objects.toString(authRevision, ""), Objects.toString(policyRevision, ""),
                Objects.toString(relationshipRevision, ""),
                Objects.toString(populationRevision, ""), "");
        String composite = "psr-" + HrisExplorerAuthoritySeals.sha256Hex(compositeMaterial);
        String evaluationMaterial = String.join("\n",
                "dwp:product-surface-evaluation-revision:v1", composite,
                "PRODUCT", PRODUCT, surfaceKey, Objects.toString(routeContractKey, ""),
                Objects.toString(routeGrantRef, ""));
        return "psr-" + HrisExplorerAuthoritySeals.sha256Hex(evaluationMaterial);
    }

    static long numericRevision(String domain, String sourceRevision) {
        try {
            return HrisExplorerAuthoritySeals.numericRevision(domain, sourceRevision);
        } catch (IllegalArgumentException exception) {
            throw unavailable("Authority revision is missing", exception);
        }
    }

    static long authorityRevision(String domain, String sourceRevision) {
        try {
            return HrisExplorerAuthoritySeals.gatewayAuthorityRevision(
                    domain, sourceRevision);
        } catch (IllegalArgumentException exception) {
            throw unavailable("Authority revision is missing", exception);
        }
    }

    static long projectionRevision() {
        String tuples = deployedRouteKeys().stream().map(routeKey -> {
            HrisExplorerV43RouteAdapter.RouteSpec spec =
                    HrisExplorerV43RouteAdapter.routeSpec(routeKey);
            HrisExplorerDtos.ExplorerAuthorization authorization = spec.authorization();
            return String.join("\u001f", routeKey, spec.surfaceKey(),
                    authorization.routeContractKey(), authorization.capabilityMode().name(),
                    String.join("\u001e", authorization.capabilityKeys()));
        }).collect(Collectors.joining("\n"));
        return numericRevision("projection-v43",
                HrisExplorerAuthoritySeals.sha256Hex(tuples));
    }

    static List<String> deployedRouteKeys() {
        return HrisExplorerV43RouteAdapter.deployedRouteKeys().stream().sorted().toList();
    }

    boolean platformOrigin(HrisExplorerRepository.ReceiptAuthority stored) {
        return exactPlatformOriginPurpose(
                stored.originatingAction(), stored.purposeCode())
                && exactPlatformOrigin(stored.originatingAction(),
                stored.originRouteContractKey()) && validStoredOrigin(stored);
    }

    static boolean exactPlatformOriginPurpose(String action, String purposeCode) {
        if (action == null || purposeCode == null) return false;
        if (CONTROLLED_TRANSFER_ACTION.equals(action)) {
            return CONTROLLED_TRANSFER_ORIGIN_PURPOSE.equals(purposeCode);
        }
        if (INTEGRATION_ORIGIN_ROUTES_BY_ACTION.containsKey(action)) {
            return INTEGRATION_CONTROL_PLANE_ORIGIN_PURPOSE.equals(purposeCode);
        }
        return PLATFORM_ORIGIN_ROUTES_BY_ACTION.containsKey(action)
                && PLATFORM_ORIGIN_PURPOSE.equals(purposeCode);
    }

    boolean explorerOrigin(HrisExplorerRepository.ReceiptAuthority stored) {
        return EXPLORER_PURPOSE.equals(stored.purposeCode())
                && exactExplorerOrigin(stored.originatingAction(),
                stored.originRouteContractKey()) && validStoredOrigin(stored);
    }

    boolean validStoredOrigin(HrisExplorerRepository.ReceiptAuthority stored) {
        return canonical(stored.populationScopeDigest(), 64)
                && stored.populationScopeDigest().matches("^[a-f0-9]{64}$")
                && canonical(stored.authorityPopulationScopeDigest(), 64)
                && stored.authorityPopulationScopeDigest().matches("^[a-f0-9]{64}$")
                && stored.fieldPolicyRevision() > 0
                && stored.authorityFieldPolicyRevision() > 0
                && stored.fieldPolicyRevision()
                == HrisExplorerAuthoritySeals.publicRevision(
                stored.authorityFieldPolicyRevision())
                && stored.authorizationRevision() > 0
                && stored.authorityAuthorizationRevision() > 0
                && stored.authorizationRevision()
                == HrisExplorerAuthoritySeals.publicRevision(
                stored.authorityAuthorizationRevision())
                && canonical(stored.originContextKey(), 500)
                && stored.originContextKey().matches("^psc-[a-f0-9]{64}$")
                && HcmEligibilityScopeKey.isCanonical(stored.originContextScopeKey())
                && canonical(stored.originDecisionRevision(), 200)
                && stored.originDecisionRevision().matches("^psr-[a-f0-9]{64}$");
    }

    boolean validRequest(AuthorityRequest request) {
        return request != null && request.tenantId() > 0 && request.actorId() > 0
                && request.subjectPrincipalPublicId() != null
                && List.of("NORMAL", "ELEVATED").contains(request.activeAccessMode())
                && canonical(request.currentDecisionRevision(), 200)
                && request.currentDecisionRevision().matches("^psr-[a-f0-9]{64}$")
                && canonical(request.trustedRouteContractKey(), 240)
                && request.trustedRouteContractKey().startsWith("route.hcm.utility.explorer")
                && canonical(request.contextKey(), 500)
                && request.contextKey().matches("^psc-[a-f0-9]{64}$")
                && canonical(request.contextScopeKey(), 500)
                && validContextScopeKey(request.contextScopeKey())
                && request.revalidateAt() != null && request.revalidateAt().isAfter(now());
    }

    String originSurface(String routeContractKey) {
        if (!PLATFORM_ORIGIN_ROUTES_BY_ACTION.containsValue(routeContractKey)) {
            throw unavailable("Unsupported Platform receipt origin route");
        }
        return MANAGEMENT_SURFACE;
    }

    static boolean exactPlatformOrigin(String action, String routeContractKey) {
        return action != null && routeContractKey != null
                && routeContractKey.equals(PLATFORM_ORIGIN_ROUTES_BY_ACTION.get(action));
    }

    static boolean exactExplorerOrigin(String action, String routeContractKey) {
        return routeContractKey != null
                && routeContractKey.equals(EXPLORER_ORIGIN_ROUTES_BY_ACTION.get(action));
    }

    static boolean validContextScopeKey(String value) {
        return value.matches("^(?:scope-[a-f0-9]{32}|hcm-scope-[a-f0-9]{40})$");
    }

    OffsetDateTime earliest(OffsetDateTime... values) {
        OffsetDateTime value = null;
        for (OffsetDateTime candidate : values) {
            if (candidate != null && (value == null || candidate.isBefore(value))) {
                value = candidate;
            }
        }
        if (value == null || !value.isAfter(now())) {
            throw unavailable("Authority freshness is missing");
        }
        return value;
    }

    OffsetDateTime earliestNullable(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    static RestClient client(
            RestClient.Builder builder, String baseUrl, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.toMillis() > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "Explorer authority timeout must be between 1ms and 5s.");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    static String canonicalToken(String value) {
        return value == null ? "" : value.strip();
    }

    static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    static IllegalStateException unavailable(String message) {
        return new IllegalStateException(message);
    }

    static IllegalStateException unavailable(String message, Exception cause) {
        return new IllegalStateException(message, cause);
    }

    boolean hasActiveMutationGrant(
            List<? extends GrantView> grants, String scopeKey) {
        return grants.stream()
                .filter(grant -> grant.scopeKeys().contains(scopeKey))
                .filter(grant -> !Boolean.TRUE.equals(grant.readOnly()))
                .anyMatch(grant -> !"CAPABILITY".equals(grant.grantKind())
                        || "ACTIVE".equals(grant.activationState()));
    }

    interface ScopeView {
        String key();
        String kind();
        Boolean isDefault();
        Boolean readOnly();
        OffsetDateTime validUntil();
    }

    interface GrantView {
        String grantKind();
        String capabilityContractKey();
        String authorityMode();
        List<String> scopeKeys();
        Boolean readOnly();
        String activationState();
        OffsetDateTime validUntil();
    }

    interface AuthorityView {
        String decision();
        String authRevision();
        String policyRevision();
        String productKey();
        String surfaceKey();
        String accessMode();
        String contextKey();
        String routeGrantRef();
        Boolean requiresProductEligibility();
        OffsetDateTime validUntil();
        OffsetDateTime revalidateAt();
        List<? extends ScopeView> scopes();
        List<? extends GrantView> effectiveGrants();
    }

    interface EligibleView extends ScopeView {
        String sourceScopeKey();
        String displayName();
    }

    interface EligibilityView {
        String decision();
        String productRelationshipRevision();
        String targetPopulationRevision();
        OffsetDateTime revalidateAt();
        List<? extends EligibleView> scopes();
    }

    interface ProofView {
        boolean allowed();
        String authRevision();
        String policyRevision();
        String selectedScopeKey();
        List<? extends GrantView> grants();
        List<? extends ScopeView> scopes();
    }
}
