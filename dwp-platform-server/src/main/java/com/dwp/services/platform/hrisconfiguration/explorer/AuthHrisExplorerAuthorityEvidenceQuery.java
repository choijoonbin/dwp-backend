package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthorityRequest;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthoritySnapshot;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerReceiptAuthoritySource.CurrentExplorerReceiptAuthority;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource.CurrentReceiptAuthority;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource.ForwardedReceiptAuthority;

/** Reproduces current Gateway Auth/People authority for Explorer and shared receipts. */
@Component
@ConditionalOnExpression(
        "'${dwp.hris.explorer.enabled:false}' == 'true' || "
                + "'${dwp.hris.controlled-transfer.enabled:false}' == 'true' || "
                + "'${dwp.hris.automation-control-plane.enabled:false}' == 'true' || "
                + "'${dwp.hris.integration-control-plane.enabled:false}' == 'true'")
final class AuthHrisExplorerAuthorityEvidenceQuery
        implements HrisExplorerAuthorityEvidenceQuery,
        HrisExplorerReceiptAuthoritySource,
        HrisPlatformReceiptAuthoritySource {

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

    private static final String AUTHORITY_PATH =
            "/internal/auth/v1/product-surface-authority/evaluate";
    private static final String IDENTITY_PATH =
            "/internal/identity/v1/tenants/{tenantId}/users/{userId}";
    private static final String PEOPLE_PATH =
            "/internal/people/v1/product-surface-eligibility/evaluate";
    private static final String PRODUCT_TOKEN_HEADER = "X-DWP-Product-Surface-Token";
    private static final String IDENTITY_TOKEN_HEADER = "X-DWP-Identity-Sync-Token";
    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String PERSON_HEADER = "X-DWP-Person-Public-ID";
    private static final String SERVICE_IDENTITY = "dwp-platform-server";
    private static final String PRODUCT = "hcm";
    private static final String UTILITY_SURFACE = "hcm.utility";
    private static final String MANAGEMENT_SURFACE = "hcm.management";
    private static final String SHELL_CAPABILITY = "hcm.app.shell.view";
    private static final String RECEIPT_CAPABILITY = "hcm.command-receipt.view";
    private static final String CONTROLLED_TRANSFER_CREATE_CAPABILITY =
            "hcm.integration.transfer.create";
    private static final long MAX_TIMEOUT_MILLIS = 5_000L;
    private static final Set<String> TERMINAL_AUTH_DECISIONS = Set.of(
            "ALLOWED", "APP_DENIED", "SURFACE_DENIED", "ROUTE_DENIED",
            "SCOPE_SELECTION_REQUIRED", "SCOPE_INVALID", "EXPIRED",
            "ACTIVATION_REQUIRED", "STEP_UP_REQUIRED", "SOD_CONFLICT",
            "SUPPORT_SCOPE_DENIED");
    private static final Set<String> TERMINAL_PEOPLE_DECISIONS = Set.of(
            "ALLOWED", "SURFACE_DENIED", "SCOPE_INVALID", "AUTHORITY_UNAVAILABLE");

    static final Map<String, String> EXPLORER_ORIGIN_ROUTES_BY_ACTION = Map.of(
            JdbcHrisExplorerRepository.PREFERENCES_ACTION,
            "route.hcm.utility.explorer-preferences-update.action",
            JdbcHrisExplorerRepository.RECENT_ACTION,
            "route.hcm.utility.explorer-recent-create.action");
    private static final Map<String, String> EXISTING_PLATFORM_ORIGIN_ROUTES_BY_ACTION =
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

    private static Map<String, String> platformOriginRoutes() {
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

    private final RestClient auth;
    private final RestClient people;
    private final String productSurfaceToken;
    private final String identitySyncToken;
    private final String peopleServiceToken;
    private final Clock clock;
    private final ObjectMapper strictJson;

    @Autowired
    AuthHrisExplorerAuthorityEvidenceQuery(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dwp.hris.explorer.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.hris.explorer.people-url:http://localhost:8003}") String peopleUrl,
            @Value("${dwp.hris.explorer.product-surface-token:}")
            String productSurfaceToken,
            @Value("${dwp.hris.explorer.identity-sync-token:}")
            String identitySyncToken,
            @Value("${dwp.hris.explorer.people-service-token:}")
            String peopleServiceToken,
            @Value("${dwp.hris.explorer.authority-timeout:PT2S}") Duration timeout) {
        this(client(builder, authUrl, timeout), client(builder, peopleUrl, timeout),
                productSurfaceToken, identitySyncToken, peopleServiceToken,
                Clock.systemUTC(), objectMapper);
    }

    AuthHrisExplorerAuthorityEvidenceQuery(
            RestClient auth,
            RestClient people,
            String productSurfaceToken,
            String identitySyncToken,
            String peopleServiceToken,
            Clock clock,
            ObjectMapper objectMapper) {
        this.auth = Objects.requireNonNull(auth);
        this.people = Objects.requireNonNull(people);
        this.productSurfaceToken = canonicalToken(productSurfaceToken);
        this.identitySyncToken = canonicalToken(identitySyncToken);
        this.peopleServiceToken = canonicalToken(peopleServiceToken);
        this.clock = Objects.requireNonNull(clock);
        this.strictJson = Objects.requireNonNull(objectMapper).copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public Optional<AuthoritySnapshot> resolve(AuthorityRequest request) {
        if (!validRequest(request)
                || !(EXPLORER_PURPOSE.equals(request.requestPurposeCode())
                || (RECEIPT_PURPOSE.equals(request.requestPurposeCode())
                && RECEIPT_ROUTE.equals(request.trustedRouteContractKey())))) {
            return Optional.empty();
        }
        if (!identityMatches(request)) return Optional.empty();

        RouteProof current = resolveRoute(
                request, UTILITY_SURFACE, request.trustedRouteContractKey(),
                request.contextKey(), request.contextScopeKey(), request.revalidateAt());
        if (!current.allowed() || !current.peopleIntersected()
                || !hasCapability(current, SHELL_CAPABILITY)
                || !request.currentDecisionRevision().equals(current.decisionRevision())) {
            return Optional.empty();
        }
        RouteProof page = EXPLORER_PAGE_ROUTE.equals(request.trustedRouteContractKey())
                ? current : resolveRoute(request, UTILITY_SURFACE, EXPLORER_PAGE_ROUTE,
                request.contextKey(), request.contextScopeKey(), request.revalidateAt());
        if (!page.allowed() || !page.peopleIntersected()
                || !hasCapability(page, SHELL_CAPABILITY)
                || !sameAuthRevisions(current, page)) {
            return Optional.empty();
        }

        Map<String, RouteProof> firstPass = new LinkedHashMap<>();
        for (String routeKey : deployedRouteKeys()) {
            HrisExplorerV43RouteAdapter.RouteSpec spec =
                    HrisExplorerV43RouteAdapter.routeSpec(routeKey);
            RouteProof proof = resolveRoute(
                    request, spec.surfaceKey(),
                    spec.authorization().routeContractKey(), null, null, null);
            requireStableTerminal(page, proof);
            if (proof.allowed() && !hasCapability(
                    proof, spec.authorization().capabilityKeys().getFirst())) {
                throw unavailable("Explorer descendant authority evidence is incomplete");
            }
            firstPass.put(routeKey, proof);
        }

        // Recheck every terminal outcome so a mid-scan Auth or People change
        // cannot leave an early stale allow in the projection.
        for (String routeKey : deployedRouteKeys()) {
            HrisExplorerV43RouteAdapter.RouteSpec spec =
                    HrisExplorerV43RouteAdapter.routeSpec(routeKey);
            RouteProof repeated = resolveRoute(
                    request, spec.surfaceKey(),
                    spec.authorization().routeContractKey(), null, null, null);
            requireStableTerminal(page, repeated);
            if (!firstPass.get(routeKey).stabilitySeal().equals(
                    repeated.stabilitySeal())) {
                throw unavailable("Explorer descendant authority changed during evaluation");
            }
        }

        RouteProof finalCurrent = resolveRoute(
                request, UTILITY_SURFACE, request.trustedRouteContractKey(),
                request.contextKey(), request.contextScopeKey(), request.revalidateAt());
        RouteProof finalPage = EXPLORER_PAGE_ROUTE.equals(request.trustedRouteContractKey())
                ? finalCurrent : resolveRoute(request, UTILITY_SURFACE,
                EXPLORER_PAGE_ROUTE, request.contextKey(), request.contextScopeKey(),
                request.revalidateAt());
        if (!current.stabilitySeal().equals(finalCurrent.stabilitySeal())
                || !page.stabilitySeal().equals(finalPage.stabilitySeal())) {
            throw unavailable("Explorer root authority changed during route evaluation");
        }

        Map<String, HrisExplorerDtos.ExplorerAuthorization> allowed =
                new LinkedHashMap<>();
        firstPass.forEach((routeKey, proof) -> {
            if (proof.allowed()) {
                allowed.put(routeKey, HrisExplorerV43RouteAdapter.authorization(routeKey));
            }
        });
        String populationDigest = HrisExplorerAuthoritySeals.populationScopeDigest(
                current.selectedScopeKey());
        OffsetDateTime freshUntil = firstPass.values().stream()
                .filter(RouteProof::allowed)
                .map(RouteProof::freshUntil)
                .reduce(earliest(current.freshUntil(), page.freshUntil()), this::earliest);
        return Optional.of(new AuthoritySnapshot(
                request.tenantId(), request.subjectPrincipalPublicId(),
                current.decisionRevision(), current.contextKey(),
                current.selectedScopeKey(), EXPLORER_PURPOSE, populationDigest,
                authorityRevision("field-policy", current.policyRevision()),
                authorityRevision("authorization", current.authRevision()),
                numericRevision("entitlement", current.authRevision()),
                projectionRevision(), freshUntil, Map.copyOf(allowed)));
    }

    @Override
    public Optional<CurrentExplorerReceiptAuthority> resolveExplorerOrigin(
            AuthorityRequest request,
            UUID receiptId,
            HrisExplorerRepository.ReceiptAuthority stored) {
        if (!validRequest(request) || receiptId == null || stored == null
                || !RECEIPT_PURPOSE.equals(request.requestPurposeCode())
                || !RECEIPT_ROUTE.equals(request.trustedRouteContractKey())
                || !request.subjectPrincipalPublicId().equals(stored.subjectPrincipalPublicId())
                || !explorerOrigin(stored) || !identityMatches(request)) {
            return Optional.empty();
        }
        RouteProof origin = resolveRoute(
                request, UTILITY_SURFACE, stored.originRouteContractKey(),
                stored.originContextKey(), stored.originContextScopeKey(), null);
        if (!origin.allowed() || !origin.peopleIntersected()
                || !hasCapability(origin, SHELL_CAPABILITY)
                || !stored.originDecisionRevision().equals(origin.decisionRevision())
                || authorityRevision("field-policy", origin.policyRevision())
                != stored.authorityFieldPolicyRevision()
                || authorityRevision("authorization", origin.authRevision())
                != stored.authorityAuthorizationRevision()) {
            return Optional.empty();
        }
        String authorityDigest = HrisExplorerAuthoritySeals.populationScopeDigest(
                origin.selectedScopeKey());
        if (!authorityDigest.equals(stored.authorityPopulationScopeDigest())) {
            return Optional.empty();
        }
        return Optional.of(new CurrentExplorerReceiptAuthority(
                request.tenantId(), request.subjectPrincipalPublicId(),
                stored.originatingAction(), authorityDigest,
                stored.authorityFieldPolicyRevision(), stored.purposeCode(),
                stored.authorityAuthorizationRevision(), stored.originRouteContractKey(),
                stored.originContextKey(), stored.originContextScopeKey(),
                origin.decisionRevision(), origin.freshUntil()));
    }

    @Override
    public Optional<ForwardedReceiptAuthority> resolveForwardedReceipt(
            AuthorityRequest request) {
        if (!validRequest(request)
                || !RECEIPT_PURPOSE.equals(request.requestPurposeCode())
                || !RECEIPT_ROUTE.equals(request.trustedRouteContractKey())
                || !platformReceiptScope(request).equals(request.contextScopeKey())
                || !identityMatches(request)) {
            return Optional.empty();
        }
        RouteProof receipt = resolveRoute(
                request, UTILITY_SURFACE, RECEIPT_ROUTE,
                request.contextKey(), request.contextScopeKey(), request.revalidateAt());
        if (!receipt.allowed() || receipt.peopleIntersected()
                || !hasExactPlatformReceiptGrant(receipt)
                || !request.currentDecisionRevision().equals(receipt.decisionRevision())) {
            return Optional.empty();
        }
        return Optional.of(new ForwardedReceiptAuthority(
                request.tenantId(), request.subjectPrincipalPublicId(),
                receipt.contextKey(), receipt.selectedScopeKey(),
                receipt.decisionRevision(), receipt.freshUntil()));
    }

    @Override
    public Optional<CurrentReceiptAuthority> resolve(
            AuthorityRequest request,
            UUID receiptId,
            HrisExplorerRepository.ReceiptAuthority stored) {
        Optional<ForwardedReceiptAuthority> forwarded = resolveForwardedReceipt(request);
        if (forwarded.isEmpty() || receiptId == null || stored == null
                || !request.subjectPrincipalPublicId().equals(stored.subjectPrincipalPublicId())
                || !platformOrigin(stored)) {
            return Optional.empty();
        }
        return resolvePlatformOrigin(request, stored, forwarded.get());
    }

    @Override
    public Optional<CurrentReceiptAuthority> resolveAfterExplorerForwarded(
            AuthorityRequest request,
            AuthoritySnapshot explorerAuthority,
            UUID receiptId,
            HrisExplorerRepository.ReceiptAuthority stored) {
        if (!validRequest(request) || explorerAuthority == null
                || receiptId == null || stored == null
                || !RECEIPT_PURPOSE.equals(request.requestPurposeCode())
                || !RECEIPT_ROUTE.equals(request.trustedRouteContractKey())
                || !request.subjectPrincipalPublicId().equals(stored.subjectPrincipalPublicId())
                || !platformOrigin(stored)
                || explorerAuthority.tenantId() != request.tenantId()
                || !request.subjectPrincipalPublicId().equals(
                explorerAuthority.subjectPrincipalPublicId())
                || !request.contextKey().equals(explorerAuthority.contextKey())
                || !request.contextScopeKey().equals(
                explorerAuthority.contextScopeKey())
                || !request.currentDecisionRevision().equals(
                explorerAuthority.currentDecisionRevision())
                || !request.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")
                || explorerAuthority.freshUntil() == null
                || !explorerAuthority.freshUntil().isAfter(now())
                || !identityMatches(request)) {
            return Optional.empty();
        }
        String selector = platformReceiptScope(request);
        RouteProof receipt = resolveRoute(
                request, UTILITY_SURFACE, RECEIPT_ROUTE,
                null, selector, null);
        if (!receipt.allowed() || receipt.peopleIntersected()
                || !hasExactPlatformReceiptGrant(receipt)
                || !selector.equals(receipt.selectedScopeKey())) {
            return Optional.empty();
        }
        ForwardedReceiptAuthority internal = new ForwardedReceiptAuthority(
                request.tenantId(), request.subjectPrincipalPublicId(),
                receipt.contextKey(), receipt.selectedScopeKey(),
                receipt.decisionRevision(), receipt.freshUntil());
        return resolvePlatformOrigin(request, stored, internal);
    }

    private Optional<CurrentReceiptAuthority> resolvePlatformOrigin(
            AuthorityRequest request,
            HrisExplorerRepository.ReceiptAuthority stored,
            ForwardedReceiptAuthority platformReceipt) {
        RouteProof origin = resolveRoute(
                request, originSurface(stored.originRouteContractKey()),
                stored.originRouteContractKey(), stored.originContextKey(),
                stored.originContextScopeKey(), null);
        if (!origin.allowed() || !origin.peopleIntersected()
                || (CONTROLLED_TRANSFER_ACTION.equals(stored.originatingAction())
                && !hasCapability(origin, CONTROLLED_TRANSFER_CREATE_CAPABILITY))
                || !stored.originDecisionRevision().equals(origin.decisionRevision())
                || authorityRevision("field-policy", origin.policyRevision())
                != stored.authorityFieldPolicyRevision()
                || authorityRevision("authorization", origin.authRevision())
                != stored.authorityAuthorizationRevision()) {
            return Optional.empty();
        }
        String authorityDigest = HrisExplorerAuthoritySeals.populationScopeDigest(
                origin.selectedScopeKey());
        if (!authorityDigest.equals(stored.authorityPopulationScopeDigest())) {
            return Optional.empty();
        }
        return Optional.of(new CurrentReceiptAuthority(
                request.tenantId(), request.subjectPrincipalPublicId(),
                stored.originatingAction(), authorityDigest,
                stored.authorityFieldPolicyRevision(), stored.purposeCode(),
                stored.authorityAuthorizationRevision(), stored.originRouteContractKey(),
                stored.originContextKey(), stored.originContextScopeKey(),
                origin.decisionRevision(), platformReceipt.contextKey(),
                platformReceipt.contextScopeKey(), platformReceipt.decisionRevision(),
                earliest(platformReceipt.freshUntil(), origin.freshUntil())));
    }

    private RouteProof resolveRoute(
            AuthorityRequest request,
            String surfaceKey,
            String routeContractKey,
            String expectedContextKey,
            String expectedScopeKey,
            OffsetDateTime forwardedFreshUntil) {
        AuthorityResult result = evaluate(
                request, surfaceKey, routeContractKey, expectedContextKey, expectedScopeKey);
        if (!validTerminal(result)) {
            throw unavailable("Auth returned malformed Explorer authority");
        }
        if (!"ALLOWED".equals(result.decision())) {
            return RouteProof.denied(
                    result.decision(), result.authRevision(), result.policyRevision());
        }
        if (!validAllowed(result, request, surfaceKey, expectedContextKey)) {
            throw unavailable("Auth returned incomplete Explorer authority");
        }
        if (Boolean.TRUE.equals(result.requiresProductEligibility())) {
            EligibilityResult eligibility = evaluatePeople(request, result, expectedScopeKey);
            if (!validEligibility(eligibility, request, result)) {
                throw unavailable("People returned malformed Explorer authority");
            }
            if ("AUTHORITY_UNAVAILABLE".equals(eligibility.decision())) {
                throw unavailable("People Explorer authority is unavailable");
            }
            if (!"ALLOWED".equals(eligibility.decision())) {
                return RouteProof.denied(
                        "PEOPLE_" + eligibility.decision(), result.authRevision(),
                        result.policyRevision(), eligibility.productRelationshipRevision(),
                        eligibility.targetPopulationRevision());
            }
            Intersection intersection = intersect(result, eligibility, expectedScopeKey);
            OffsetDateTime freshUntil = authorityFreshness(
                    forwardedFreshUntil, result, eligibility, intersection.selectedScope());
            String revision = decisionRevision(
                    request.tenantId(), request.actorId(), request.activeAccessMode(),
                    result.authRevision(), result.policyRevision(),
                    eligibility.productRelationshipRevision(),
                    eligibility.targetPopulationRevision(), surfaceKey,
                    routeContractKey, result.routeGrantRef());
            return RouteProof.allowed(
                    result, intersection.grants(), intersection.scopes(),
                    intersection.selectedScope().key(),
                    eligibility.productRelationshipRevision(),
                    eligibility.targetPopulationRevision(), revision, freshUntil, true);
        }
        EffectiveScope selected = selectRawScope(result.scopes(), expectedScopeKey);
        if (selected == null) throw unavailable("Auth scope selection is unavailable");
        OffsetDateTime freshUntil = authorityFreshness(
                forwardedFreshUntil, result, null, selected);
        String revision = decisionRevision(
                request.tenantId(), request.actorId(), request.activeAccessMode(),
                result.authRevision(), result.policyRevision(), null, null,
                surfaceKey, routeContractKey, result.routeGrantRef());
        return RouteProof.allowed(
                result, result.effectiveGrants(), result.scopes(), selected.key(),
                null, null, revision, freshUntil, false);
    }

    Intersection intersect(
            AuthorityResult authority,
            EligibilityResult eligibility,
            String expectedScopeKey) {
        Map<String, EffectiveScope> rawByKey;
        Map<String, EligibleScope> eligibleBySource;
        try {
            rawByKey = authority.scopes().stream().collect(Collectors.toUnmodifiableMap(
                    EffectiveScope::key, Function.identity()));
            eligibleBySource = eligibility.scopes().stream().collect(
                    Collectors.toUnmodifiableMap(EligibleScope::sourceScopeKey,
                            Function.identity()));
        } catch (RuntimeException exception) {
            throw unavailable("Explorer scope evidence is ambiguous", exception);
        }
        List<EffectiveGrant> grants = new ArrayList<>();
        for (EffectiveGrant grant : authority.effectiveGrants()) {
            List<String> rebound = grant.scopeKeys().stream()
                    .map(eligibleBySource::get).filter(Objects::nonNull)
                    .map(EligibleScope::key).distinct().toList();
            if (!rebound.isEmpty()) grants.add(grant.withScopeKeys(rebound));
        }
        if (grants.isEmpty()) throw unavailable("People removed every Explorer grant");
        Set<String> used = grants.stream().flatMap(grant -> grant.scopeKeys().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, DerivedAlias> derived = new LinkedHashMap<>();
        for (EligibleScope eligible : eligibility.scopes()) {
            EffectiveScope raw = rawByKey.get(eligible.sourceScopeKey());
            if (raw == null || !raw.kind().equals(eligible.kind())
                    || !HcmEligibilityScopeKey.isCanonical(eligible.key())) {
                throw unavailable("People scope is not current canonical evidence");
            }
            if (!used.contains(eligible.key())) continue;
            EffectiveScope value = new EffectiveScope(
                    eligible.key(), eligible.kind(), eligible.displayName(),
                    eligible.isDefault(), Boolean.TRUE.equals(raw.readOnly())
                    || Boolean.TRUE.equals(eligible.readOnly()),
                    earliestNullable(raw.validUntil(), eligible.validUntil()));
            DerivedAlias alias = new DerivedAlias(
                    value, Boolean.TRUE.equals(eligible.readOnly()));
            DerivedAlias previous = derived.putIfAbsent(value.key(), alias);
            if (previous != null) {
                derived.put(value.key(), mergeAlias(previous, alias));
            }
        }
        List<EffectiveScope> scopes = normalizeReadOnly(
                derived.values().stream().map(DerivedAlias::scope).toList(), grants);
        if (derived.isEmpty() || grants.stream().anyMatch(grant ->
                grant.scopeKeys().isEmpty()
                        || !scopes.stream().map(EffectiveScope::key).toList()
                        .containsAll(grant.scopeKeys()))) {
            throw unavailable("Explorer grant and scope intersection is not closed");
        }
        EffectiveScope selected = selectDerivedScope(
                scopes, expectedScopeKey);
        if (selected == null) {
            throw unavailable("Current People scope selection is unavailable");
        }
        return new Intersection(List.copyOf(grants), scopes, selected);
    }

    private DerivedAlias mergeAlias(DerivedAlias left, DerivedAlias right) {
        EffectiveScope leftScope = left.scope();
        EffectiveScope rightScope = right.scope();
        if (!leftScope.key().equals(rightScope.key())
                || !leftScope.kind().equals(rightScope.kind())
                || !Objects.equals(leftScope.displayName(), rightScope.displayName())
                || !leftScope.isDefault().equals(rightScope.isDefault())
                || left.ownerReadOnly() != right.ownerReadOnly()) {
            throw unavailable("People scope aliases disagree");
        }
        return new DerivedAlias(new EffectiveScope(
                leftScope.key(), leftScope.kind(), leftScope.displayName(),
                leftScope.isDefault(),
                Boolean.TRUE.equals(leftScope.readOnly())
                        && Boolean.TRUE.equals(rightScope.readOnly()),
                earliestNullable(leftScope.validUntil(), rightScope.validUntil())),
                left.ownerReadOnly());
    }

    private List<EffectiveScope> normalizeReadOnly(
            List<EffectiveScope> scopes, List<EffectiveGrant> grants) {
        return scopes.stream().map(scope -> new EffectiveScope(
                scope.key(), scope.kind(), scope.displayName(), scope.isDefault(),
                Boolean.TRUE.equals(scope.readOnly())
                        || !hasActiveMutationGrant(grants, scope.key()),
                scope.validUntil())).toList();
    }

    private boolean hasActiveMutationGrant(
            List<EffectiveGrant> grants, String scopeKey) {
        return grants.stream()
                .filter(grant -> grant.scopeKeys().contains(scopeKey))
                .filter(grant -> !Boolean.TRUE.equals(grant.readOnly()))
                .anyMatch(grant -> !"CAPABILITY".equals(grant.grantKind())
                        || "ACTIVE".equals(grant.activationState()));
    }

    private EligibilityResult evaluatePeople(
            AuthorityRequest request,
            AuthorityResult authority,
            String contextScopeKey) {
        if (peopleServiceToken.isBlank()) {
            throw unavailable("Explorer People integration is unconfigured");
        }
        List<CandidateScope> candidates = authority.scopes().stream()
                .map(scope -> new CandidateScope(scope.key(), scope.kind())).toList();
        try {
            String response = people.post().uri(PEOPLE_PATH)
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header(SERVICE_TOKEN_HEADER, peopleServiceToken)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .header(USER_HEADER, Long.toString(request.actorId()))
                    .header(TENANT_HEADER, Long.toString(request.tenantId()))
                    .header(PERSON_HEADER, request.subjectPrincipalPublicId().toString())
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new EligibilityRequest(
                            request.tenantId(), request.actorId(), PRODUCT,
                            authority.surfaceKey(), request.activeAccessMode(), now(),
                            candidates, contextScopeKey))
                    .retrieve().body(String.class);
            return parse(response, EligibilityResult.class, "People eligibility");
        } catch (RestClientException exception) {
            throw unavailable("Explorer People integration failed", exception);
        }
    }

    private AuthorityResult evaluate(
            AuthorityRequest request,
            String surfaceKey,
            String routeContractKey,
            String contextKey,
            String contextScopeKey) {
        if (productSurfaceToken.isBlank()) {
            throw unavailable("Explorer Auth integration is unconfigured");
        }
        try {
            String response = auth.post().uri(AUTHORITY_PATH)
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .header(PRODUCT_TOKEN_HEADER, productSurfaceToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new EvaluateRequest(
                            request.tenantId(), request.actorId(), PRODUCT,
                            surfaceKey, request.activeAccessMode(), routeContractKey,
                            contextKey, contextScopeKey, null, null, List.of()))
                    .retrieve().body(String.class);
            return parse(response, AuthorityResult.class, "Auth authority");
        } catch (RestClientException exception) {
            throw unavailable("Explorer Auth integration failed", exception);
        }
    }

    private boolean identityMatches(AuthorityRequest request) {
        if (identitySyncToken.isBlank()) {
            throw unavailable("Explorer Identity integration is unconfigured");
        }
        try {
            String response = auth.get()
                    .uri(IDENTITY_PATH, request.tenantId(), request.actorId())
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .header(IDENTITY_TOKEN_HEADER, identitySyncToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
            IdentitySubject subject = parse(response, IdentitySubject.class,
                    "Identity subject");
            return subject.tenantId() != null && subject.tenantId() == request.tenantId()
                    && subject.userId() != null && subject.userId() == request.actorId()
                    && request.subjectPrincipalPublicId().equals(subject.personPublicId())
                    && "ACTIVE".equals(subject.status())
                    && "TENANT".equals(subject.identityPlane());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return false;
            throw unavailable("Explorer Identity integration failed", exception);
        } catch (RestClientException exception) {
            throw unavailable("Explorer Identity integration failed", exception);
        }
    }

    private <T> T parse(String response, Class<T> type, String source) {
        if (response == null || response.isBlank()) {
            throw unavailable(source + " response is empty");
        }
        try {
            return strictJson.readValue(response, type);
        } catch (JsonProcessingException exception) {
            throw unavailable(source + " response is not a closed contract", exception);
        }
    }

    private boolean validTerminal(AuthorityResult result) {
        return result != null && TERMINAL_AUTH_DECISIONS.contains(result.decision())
                && canonical(result.authRevision(), 500)
                && canonical(result.policyRevision(), 500);
    }

    private boolean validAllowed(
            AuthorityResult result,
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
        for (EffectiveScope scope : result.scopes()) {
            if (scope == null || !canonical(scope.key(), 500)
                    || !scope.key().matches("^scope-[a-f0-9]{32}$")
                    || !canonical(scope.kind(), 80) || scope.readOnly() == null
                    || (scope.validUntil() != null && !scope.validUntil().isAfter(now))
                    || !scopeKeys.add(scope.key())) return false;
        }
        for (EffectiveGrant grant : result.effectiveGrants()) {
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

    private boolean validEligibility(
            EligibilityResult result,
            AuthorityRequest request,
            AuthorityResult authority) {
        if (result == null || !TERMINAL_PEOPLE_DECISIONS.contains(result.decision())
                || !canonical(result.productRelationshipRevision(), 500)
                || !canonical(result.targetPopulationRevision(), 500)
                || result.scopes() == null) return false;
        if (!"ALLOWED".equals(result.decision())) return result.scopes().isEmpty();
        if (result.scopes().isEmpty() || result.revalidateAt() == null
                || !result.revalidateAt().isAfter(now())) return false;
        Set<String> sources = new LinkedHashSet<>();
        for (EligibleScope scope : result.scopes()) {
            if (scope == null || !canonical(scope.sourceScopeKey(), 500)
                    || !canonical(scope.key(), 500) || !canonical(scope.kind(), 80)
                    || !canonical(scope.displayName(), 500)
                    || scope.isDefault() == null || scope.readOnly() == null
                    || scope.validUntil() == null || !scope.validUntil().isAfter(now())
                    || !sources.add(scope.sourceScopeKey())) return false;
        }
        Set<String> requested = authority.scopes().stream()
                .map(EffectiveScope::key).collect(Collectors.toUnmodifiableSet());
        return requested.containsAll(sources)
                && request.tenantId() > 0 && request.actorId() > 0;
    }

    private void requireStableTerminal(RouteProof baseline, RouteProof candidate) {
        if (candidate == null || !sameAuthRevisions(baseline, candidate)) {
            throw unavailable("Explorer descendant authority is unavailable or changed");
        }
    }

    private boolean sameAuthRevisions(RouteProof left, RouteProof right) {
        return left != null && right != null
                && left.authRevision().equals(right.authRevision())
                && left.policyRevision().equals(right.policyRevision());
    }

    private boolean hasCapability(RouteProof proof, String expectedCapability) {
        return proof.allowed() && proof.grants().stream().anyMatch(grant ->
                "CAPABILITY".equals(grant.grantKind())
                        && expectedCapability.equals(grant.capabilityContractKey())
                        && grant.scopeKeys() != null && !grant.scopeKeys().isEmpty()
                        && proof.scopes().stream().map(EffectiveScope::key)
                        .anyMatch(grant.scopeKeys()::contains));
    }

    private boolean hasExactPlatformReceiptGrant(RouteProof proof) {
        return proof.grants().size() == 1 && hasCapability(proof, RECEIPT_CAPABILITY)
                && "PERMISSION_AND_RELATIONSHIP".equals(
                proof.grants().getFirst().authorityMode())
                && proof.scopes().size() == 1
                && "TARGET_POPULATION".equals(proof.scopes().getFirst().kind())
                && proof.selectedScopeKey().equals(proof.scopes().getFirst().key())
                && proof.selectedScopeKey().matches("^scope-[a-f0-9]{32}$");
    }

    private String platformReceiptScope(AuthorityRequest request) {
        return ProductSurfaceScopeKey.key(
                request.tenantId(), request.actorId(), PRODUCT, UTILITY_SURFACE,
                "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION");
    }

    private EffectiveScope selectRawScope(List<EffectiveScope> scopes, String expectedScopeKey) {
        if (expectedScopeKey != null) {
            return scopes.stream().filter(scope -> expectedScopeKey.equals(scope.key()))
                    .findFirst().orElse(null);
        }
        List<EffectiveScope> defaults = scopes.stream()
                .filter(scope -> Boolean.TRUE.equals(scope.isDefault())).toList();
        if (scopes.size() == 1 && defaults.size() == 1) return scopes.getFirst();
        return defaults.size() == 1 ? defaults.getFirst() : null;
    }

    private EffectiveScope selectDerivedScope(
            List<EffectiveScope> scopes, String expectedScopeKey) {
        if (expectedScopeKey != null) {
            if (!HcmEligibilityScopeKey.isCanonical(expectedScopeKey)) return null;
            return scopes.stream().filter(scope -> expectedScopeKey.equals(scope.key()))
                    .findFirst().orElse(null);
        }
        List<EffectiveScope> defaults = scopes.stream()
                .filter(scope -> Boolean.TRUE.equals(scope.isDefault())).toList();
        if (scopes.size() == 1 && defaults.size() == 1) return scopes.getFirst();
        return defaults.size() == 1 ? defaults.getFirst() : null;
    }

    private OffsetDateTime authorityFreshness(
            OffsetDateTime forwarded,
            AuthorityResult authority,
            EligibilityResult eligibility,
            EffectiveScope selected) {
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

    private static List<String> deployedRouteKeys() {
        return HrisExplorerV43RouteAdapter.deployedRouteKeys().stream().sorted().toList();
    }

    private boolean platformOrigin(HrisExplorerRepository.ReceiptAuthority stored) {
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

    private boolean explorerOrigin(HrisExplorerRepository.ReceiptAuthority stored) {
        return EXPLORER_PURPOSE.equals(stored.purposeCode())
                && exactExplorerOrigin(stored.originatingAction(),
                stored.originRouteContractKey()) && validStoredOrigin(stored);
    }

    private boolean validStoredOrigin(HrisExplorerRepository.ReceiptAuthority stored) {
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

    private boolean validRequest(AuthorityRequest request) {
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

    private String originSurface(String routeContractKey) {
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

    private static boolean validContextScopeKey(String value) {
        return value.matches("^(?:scope-[a-f0-9]{32}|hcm-scope-[a-f0-9]{40})$");
    }

    private OffsetDateTime earliest(OffsetDateTime... values) {
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

    private OffsetDateTime earliestNullable(OffsetDateTime left, OffsetDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static RestClient client(
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

    private static String canonicalToken(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static IllegalStateException unavailable(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException unavailable(String message, Exception cause) {
        return new IllegalStateException(message, cause);
    }

    private record EvaluateRequest(
            long tenantId, long actorId, String productKey, String surfaceKey,
            String activeAccessMode, String routeContractKey, String contextKey,
            String contextScopeKey, String supportSessionRef, String supportRevision,
            List<String> supportScopes) {
    }

    private record EligibilityRequest(
            long tenantId, long actorId, String productKey, String surfaceKey,
            String activeAccessMode, OffsetDateTime evaluatedAt,
            List<CandidateScope> candidateScopes, String contextScopeKey) {
    }

    private record CandidateScope(String key, String kind) {
    }

    record AuthorityResult(
            String decision, String reasonCode, String authRevision, String policyRevision,
            String contextKey, String productKey, String surfaceKey, String plane,
            String accessMode, String accessSource, String appResourceKey,
            List<EffectiveGrant> effectiveGrants, List<EffectiveScope> scopes,
            String routeGrantRef, Boolean effectiveReadOnly,
            Boolean requiresProductEligibility, OffsetDateTime validUntil,
            OffsetDateTime expiredAt, String requiredAssurance, String requestPolicyRef,
            OffsetDateTime revalidateAt, String evidenceRef) {
    }

    record EffectiveGrant(
            String grantKind, String capabilityContractKey, String resolvedCapabilityCode,
            String accessPolicyKey, String policyDecisionRef, String authorityMode,
            List<String> predicatePolicyKeys, String responsibilityRequirement,
            Responsibility responsibility, List<String> scopeKeys,
            Boolean requiresProductEntitlement, Boolean readOnly,
            String activationState, OffsetDateTime validUntil) {

        EffectiveGrant withScopeKeys(List<String> values) {
            return new EffectiveGrant(
                    grantKind, capabilityContractKey, resolvedCapabilityCode,
                    accessPolicyKey, policyDecisionRef, authorityMode,
                    predicatePolicyKeys, responsibilityRequirement, responsibility,
                    List.copyOf(values), requiresProductEntitlement, readOnly,
                    activationState, validUntil);
        }
    }

    record Responsibility(String code, String resourceSetKey) {
    }

    record EffectiveScope(
            String key, String kind, String displayName, Boolean isDefault,
            Boolean readOnly, OffsetDateTime validUntil) {
    }

    record EligibilityResult(
            String decision, String reasonCode, String productRelationshipRevision,
            String targetPopulationRevision, List<EligibleScope> scopes,
            OffsetDateTime revalidateAt, String evidenceRef) {
    }

    record EligibleScope(
            String sourceScopeKey, String key, String kind, String displayName,
            Boolean isDefault, Boolean readOnly, OffsetDateTime validUntil) {
    }

    private record IdentitySubject(
            Long tenantId, Long userId, UUID publicId, UUID personPublicId,
            String displayName, String email, String jobTitle, String department,
            String status, String identityPlane, List<String> roles,
            List<UUID> groupRefs, List<String> permissionKeys) {
    }

    record Intersection(
            List<EffectiveGrant> grants, List<EffectiveScope> scopes,
            EffectiveScope selectedScope) {
    }

    private record DerivedAlias(EffectiveScope scope, boolean ownerReadOnly) {
    }

    private record RouteProof(
            boolean allowed, String decision, String authRevision, String policyRevision,
            String contextKey, List<EffectiveGrant> grants, List<EffectiveScope> scopes,
            String selectedScopeKey, String relationshipRevision,
            String populationRevision, String decisionRevision,
            OffsetDateTime freshUntil, boolean peopleIntersected) {

        private static RouteProof denied(
                String decision, String authRevision, String policyRevision) {
            return denied(decision, authRevision, policyRevision, null, null);
        }

        private static RouteProof denied(
                String decision, String authRevision, String policyRevision,
                String relationshipRevision, String populationRevision) {
            return new RouteProof(false, decision, authRevision, policyRevision,
                    null, List.of(), List.of(), null, relationshipRevision,
                    populationRevision, null, null,
                    relationshipRevision != null || populationRevision != null);
        }

        private static RouteProof allowed(
                AuthorityResult authority, List<EffectiveGrant> grants,
                List<EffectiveScope> scopes, String selectedScopeKey,
                String relationshipRevision, String populationRevision,
                String decisionRevision, OffsetDateTime freshUntil,
                boolean peopleIntersected) {
            return new RouteProof(true, authority.decision(), authority.authRevision(),
                    authority.policyRevision(), authority.contextKey(), List.copyOf(grants),
                    List.copyOf(scopes), selectedScopeKey, relationshipRevision,
                    populationRevision, decisionRevision, freshUntil, peopleIntersected);
        }

        private String stabilitySeal() {
            return HrisExplorerAuthoritySeals.sha256Hex(String.join("\n",
                    Objects.toString(decision, ""), Objects.toString(authRevision, ""),
                    Objects.toString(policyRevision, ""),
                    Objects.toString(relationshipRevision, ""),
                    Objects.toString(populationRevision, ""),
                    Objects.toString(selectedScopeKey, ""),
                    Objects.toString(decisionRevision, "")));
        }
    }
}
