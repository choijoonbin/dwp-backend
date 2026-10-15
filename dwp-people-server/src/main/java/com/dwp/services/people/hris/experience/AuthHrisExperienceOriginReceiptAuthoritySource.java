package com.dwp.services.people.hris.experience;

import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmEligibilityScopeKeys;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.security.ProductSurfaceEligibilityDtos;
import com.dwp.services.people.security.ProductSurfaceEligibilityService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Re-evaluates the sealed HRM-008/HRM-010 originating action rather than
 * treating the neutral receipt GET grant as authority over its result.
 */
@Component
@ConditionalOnProperty(name = "dwp.hris.experience.enabled", havingValue = "true")
final class AuthHrisExperienceOriginReceiptAuthoritySource
        implements HrisExperienceOriginReceiptAuthoritySource {

    static final String AUTHORITY_PATH =
            "/internal/auth/v1/product-surface-authority/evaluate";
    static final String ORIGIN_TOKEN_HEADER = "X-DWP-Hris-Origin-Authority-Token";
    static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    static final String SERVICE_IDENTITY = "dwp-people-server";
    static final String PRODUCT = "hcm";
    static final String SURFACE = "hcm.operations";
    static final String HIGH_RISK_POLICY = "STEPUP-MGMT-HIGH-V1";
    static final String AUTHORITY_PREDICATE =
            "predicate.hcm-employee-change-authority.v1";

    private static final long MAX_TIMEOUT_MILLIS = 5_000L;
    private static final String CONTEXT_KEY_PATTERN = "psc-[0-9a-f]{64}";
    private static final String ROUTE_GRANT_PATTERN = "grant-[0-9a-f]{32}";
    private static final String DIGEST_PATTERN = "[0-9a-f]{64}";
    private static final Set<String> SELF_FIELDS =
            Set.of("DIRECTORY", "PERSON_PROFILE", "CONTACT");

    private static final Map<RouteKey, OriginRoute> ROUTES = Map.ofEntries(
            route("HRM_ASSIGNMENT_EVENT_CREATE", "CREATE_ASSIGNMENT_EVENT",
                    "EMPLOYMENT_EVENT",
                    "route.hcm.operations.assignment-event-create.action",
                    profile("HR_LIFECYCLE", "hcm.operations.assignment.manage",
                            "ACTION.HRIS_ASSIGNMENT_EVENT:MANAGE",
                            "ACTION.HRIS_ASSIGNMENT_EVENT",
                            "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                            Risk.MEDIUM)),
            route("HRM_ASSIGNMENT_EVENT_VALIDATE", "VALIDATE_ASSIGNMENT_EVENT",
                    "EMPLOYMENT_EVENT",
                    "route.hcm.operations.assignment-event-validate.action",
                    profile("HR_LIFECYCLE", "hcm.operations.assignment.manage",
                            "ACTION.HRIS_ASSIGNMENT_EVENT:MANAGE",
                            "ACTION.HRIS_ASSIGNMENT_EVENT",
                            "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                            Risk.MEDIUM)),
            route("HRM_ASSIGNMENT_EVENT_SUBMIT", "SUBMIT_ASSIGNMENT_EVENT",
                    "EMPLOYMENT_EVENT",
                    "route.hcm.operations.assignment-event-submit.action",
                    profile("HR_LIFECYCLE", "hcm.operations.assignment.manage",
                            "ACTION.HRIS_ASSIGNMENT_EVENT:MANAGE",
                            "ACTION.HRIS_ASSIGNMENT_EVENT",
                            "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                            Risk.MEDIUM)),
            route("HRM_ASSIGNMENT_EVENT_PUBLISH", "PUBLISH_ASSIGNMENT_EVENT",
                    "EMPLOYMENT_EVENT",
                    "route.hcm.operations.assignment-event-publish.action",
                    profile("HR_LIFECYCLE", "hcm.operations.people-change.approve",
                            "ACTION.HRIS_WORKFORCE_CHANGE_APPROVE:APPROVE",
                            "ACTION.HRIS_WORKFORCE_CHANGE_APPROVE",
                            "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                            Risk.HIGH)),
            route("EMPLOYEE_CHANGE_CREATE", "CREATE_EMPLOYEE_CHANGE_REQUEST",
                    "EMPLOYEE_CHANGE_REQUEST",
                    "route.hcm.operations.employee-change-request-create.action",
                    profile("HR_OPERATIONS", "hcm.operations.people.update",
                            "DATA.HRIS_WORKFORCE:UPDATE", "DATA.HRIS_WORKFORCE",
                            "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                            Risk.MEDIUM),
                    profile("TEAM_MANAGEMENT", "hcm.team.people-change.request",
                            "ACTION.HRIS_PEOPLE_CHANGE_REQUEST:CREATE",
                            "ACTION.HRIS_PEOPLE_CHANGE_REQUEST",
                            "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                            "TARGET_POPULATION", Risk.MEDIUM),
                    profile("EMPLOYEE_SELF_SERVICE", "hcm.personal.profile.update",
                            "DATA.HRIS_SELF_PROFILE:UPDATE", "DATA.HRIS_SELF_PROFILE",
                            "SELF", "SELF", Risk.MEDIUM)),
            route("EMPLOYEE_CHANGE_SUBMIT", "SUBMIT_EMPLOYEE_CHANGE_REQUEST",
                    "EMPLOYEE_CHANGE_REQUEST",
                    "route.hcm.operations.employee-change-request-submit.action",
                    profile("HR_OPERATIONS", "hcm.operations.people.update",
                            "DATA.HRIS_WORKFORCE:UPDATE", "DATA.HRIS_WORKFORCE",
                            "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                            Risk.MEDIUM),
                    profile("TEAM_MANAGEMENT", "hcm.team.people-change.request",
                            "ACTION.HRIS_PEOPLE_CHANGE_REQUEST:CREATE",
                            "ACTION.HRIS_PEOPLE_CHANGE_REQUEST",
                            "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                            "TARGET_POPULATION", Risk.MEDIUM),
                    profile("EMPLOYEE_SELF_SERVICE", "hcm.personal.profile.update",
                            "DATA.HRIS_SELF_PROFILE:UPDATE", "DATA.HRIS_SELF_PROFILE",
                            "SELF", "SELF", Risk.MEDIUM)),
            route("EMPLOYEE_CHANGE_APPLY", "APPLY_EMPLOYEE_CHANGE_REQUEST",
                    "EMPLOYEE_CHANGE_REQUEST",
                    "route.hcm.operations.employee-change-request-apply.action",
                    profile("HR_LIFECYCLE", "hcm.operations.people-change.approve",
                            "ACTION.HRIS_WORKFORCE_CHANGE_APPROVE:APPROVE",
                            "ACTION.HRIS_WORKFORCE_CHANGE_APPROVE",
                            "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                            Risk.HIGH)));

    private final RestClient auth;
    private final ProductSurfaceEligibilityService eligibility;
    private final HcmPopulationScopeService populations;
    private final ObjectMapper strictJson;
    private final ObjectMapper canonicalJson;
    private final String token;
    private final String requiredAssurance;
    private final Clock clock;

    @Autowired
    AuthHrisExperienceOriginReceiptAuthoritySource(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            ProductSurfaceEligibilityService eligibility,
            HcmPopulationScopeService populations,
            @Value("${dwp.hris.experience.origin-authority.auth-url:http://localhost:8001}")
            String authUrl,
            @Value("${dwp.hris.experience.origin-authority.token:}") String token,
            @Value("${dwp.hris.experience.origin-authority.timeout:PT2S}")
            Duration timeout,
            @Value("${dwp.hris.experience.origin-authority.required-assurance:}")
            String requiredAssurance) {
        this(client(builder, authUrl, timeout), objectMapper, eligibility, populations,
                token, requiredAssurance, Clock.systemUTC());
    }

    AuthHrisExperienceOriginReceiptAuthoritySource(
            RestClient auth,
            ObjectMapper objectMapper,
            ProductSurfaceEligibilityService eligibility,
            HcmPopulationScopeService populations,
            String token,
            String requiredAssurance,
            Clock clock) {
        this.auth = Objects.requireNonNull(auth);
        this.eligibility = Objects.requireNonNull(eligibility);
        this.populations = Objects.requireNonNull(populations);
        this.token = canonicalToken(token);
        this.requiredAssurance = canonicalOptional(requiredAssurance, 500);
        this.clock = Objects.requireNonNull(clock);
        this.canonicalJson = Objects.requireNonNull(objectMapper);
        this.strictJson = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public Optional<CurrentOriginAuthority> resolve(OriginAuthorityRequest request) {
        try {
            return resolveClosed(request);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<CurrentOriginAuthority> resolveClosed(OriginAuthorityRequest request) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (!validRequest(request, actor) || token.isBlank()) return Optional.empty();

        OriginRoute route = ROUTES.get(new RouteKey(
                request.commandType(), request.action(), request.targetType()));
        OriginProfile profile = route == null
                ? null : route.profiles().get(request.purposeCode());
        if (profile == null || !sealedRequest(request, route, profile)) {
            return Optional.empty();
        }

        HcmPepContext.Evidence currentRequest = HcmPepContext.current();
        AccessMode mode = currentRequest == null
                ? null : AccessMode.parse(currentRequest.activeAccessMode());
        if (mode == null) return Optional.empty();

        String sourceScopeKey = ProductSurfaceScopeKey.key(
                request.tenantId(), request.actorUserId(), PRODUCT, SURFACE,
                profile.scopeSource(), profile.scopeKind());
        OffsetDateTime now = now();
        AuthorityResult authority = evaluateAuth(request, route, sourceScopeKey, mode);
        if (!validAuthority(authority, route, profile, sourceScopeKey, mode, now)) {
            return Optional.empty();
        }

        ProductSurfaceEligibilityDtos.EligibilityResult people = eligibility.evaluate(
                new ProductSurfaceEligibilityDtos.EvaluateRequest(
                        request.tenantId(), request.actorUserId(), PRODUCT, SURFACE,
                        ProductSurfaceEligibilityDtos.AccessMode.valueOf(mode.name()), now,
                        List.of(new ProductSurfaceEligibilityDtos.CandidateScope(
                                sourceScopeKey, profile.scopeKind())), null));
        ProductSurfaceEligibilityDtos.EligibleScope selected =
                validPeople(people, profile, sourceScopeKey, now);
        if (selected == null) return Optional.empty();

        CurrentOriginScope scope = currentScope(
                request, profile, sourceScopeKey, selected, people, authority, now);
        if (scope == null) return Optional.empty();

        String decisionRevision = gatewayDecisionRevision(
                request.tenantId(), request.actorUserId(), mode.name(),
                authority.authRevision(), authority.policyRevision(),
                people.productRelationshipRevision(), people.targetPopulationRevision(),
                route.routeContractKey(), authority.routeGrantRef());
        long authorizationRevision = assignmentFamily(route)
                ? HrisExperienceDigests.assignmentRevision(decisionRevision)
                : HrisExperienceDigests.employeeChangeRevision(decisionRevision);
        String contextDigest = contextDigest(
                request, route, profile, authority, decisionRevision,
                selected.key());
        if (authorizationRevision != request.storedAuthorizationRevision()
                || !contextDigest.equals(request.storedContextDigest())) {
            return Optional.empty();
        }

        return Optional.of(new CurrentOriginAuthority(
                request.tenantId(), request.actorUserId(), request.actorPersonPublicId(),
                request.commandType(), request.action(), request.targetType(),
                request.purposeCode(), route.routeContractKey(), profile.capability(),
                route.method(), route.readOnly(), authorizationRevision,
                contextDigest, scope));
    }

    private AuthorityResult evaluateAuth(
            OriginAuthorityRequest request,
            OriginRoute route,
            String sourceScopeKey,
            AccessMode mode) {
        try {
            String response = auth.post().uri(AUTHORITY_PATH)
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .header(ORIGIN_TOKEN_HEADER, token)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new EvaluateRequest(
                            request.tenantId(), request.actorUserId(), PRODUCT, SURFACE,
                            mode.name(), route.routeContractKey(), null,
                            sourceScopeKey, null, null, List.of()))
                    .retrieve().body(String.class);
            if (response == null || response.isBlank()) return null;
            return strictJson.readValue(response, AuthorityResult.class);
        } catch (JsonProcessingException | RestClientException exception) {
            return null;
        }
    }

    private boolean validAuthority(
            AuthorityResult value,
            OriginRoute route,
            OriginProfile profile,
            String sourceScopeKey,
            AccessMode mode,
            OffsetDateTime now) {
        if (value == null || !canonical(value.authRevision(), 500)
                || !canonical(value.policyRevision(), 500)
                || !canonical(value.contextKey(), 500)
                || !value.contextKey().matches(CONTEXT_KEY_PATTERN)
                || !PRODUCT.equals(value.productKey())
                || !SURFACE.equals(value.surfaceKey())
                || !"management".equals(value.plane())
                || !mode.name().equals(value.accessMode())
                || !"MANAGEMENT".equals(value.accessSource())
                || !profile.resourceKey().equals(value.appResourceKey())
                || !canonical(value.routeGrantRef(), 500)
                || !value.routeGrantRef().matches(ROUTE_GRANT_PATTERN)
                || !Boolean.FALSE.equals(value.effectiveReadOnly())
                || !Boolean.TRUE.equals(value.requiresProductEligibility())
                || value.expiredAt() != null
                || !future(value.revalidateAt(), now)
                || !futureWhenPresent(value.validUntil(), now)
                || value.scopes() == null || value.scopes().size() != 1
                || value.effectiveGrants() == null
                || value.effectiveGrants().size() != 1) {
            return false;
        }
        boolean decision = switch (profile.risk()) {
            case MEDIUM -> "ALLOWED".equals(value.decision())
                    && "ALLOWED".equals(value.reasonCode())
                    && value.requiredAssurance() == null
                    && value.requestPolicyRef() == null;
            case HIGH -> switch (mode) {
                case NORMAL -> "STEP_UP_REQUIRED".equals(value.decision())
                        && "STEP_UP_REQUIRED".equals(value.reasonCode())
                        && !requiredAssurance.isBlank()
                        && requiredAssurance.equals(value.requiredAssurance())
                        && HIGH_RISK_POLICY.equals(value.requestPolicyRef());
                case ELEVATED -> "ALLOWED".equals(value.decision())
                        && "ALLOWED".equals(value.reasonCode())
                        && value.requiredAssurance() == null
                        && value.requestPolicyRef() == null;
            };
        };
        if (!decision) return false;

        EffectiveScope scope = value.scopes().getFirst();
        if (scope == null || !sourceScopeKey.equals(scope.key())
                || !profile.scopeKind().equals(scope.kind())
                || !Boolean.TRUE.equals(scope.isDefault())
                || !Boolean.FALSE.equals(scope.readOnly())
                || !futureWhenPresent(scope.validUntil(), now)) {
            return false;
        }
        EffectiveGrant grant = value.effectiveGrants().getFirst();
        String activation = profile.risk() == Risk.HIGH && mode == AccessMode.NORMAL
                ? "ELIGIBLE" : "ACTIVE";
        return grant != null
                && "CAPABILITY".equals(grant.grantKind())
                && profile.capability().equals(grant.capabilityContractKey())
                && profile.resolvedCapabilityCode().equals(
                        grant.resolvedCapabilityCode())
                && grant.accessPolicyKey() == null
                && grant.policyDecisionRef() == null
                && "PERMISSION_AND_RELATIONSHIP".equals(grant.authorityMode())
                && List.of(AUTHORITY_PREDICATE).equals(
                        grant.predicatePolicyKeys())
                && "NOT_REQUIRED".equals(grant.responsibilityRequirement())
                && grant.responsibility() == null
                && Boolean.FALSE.equals(grant.requiresProductEntitlement())
                && Boolean.FALSE.equals(grant.readOnly())
                && activation.equals(grant.activationState())
                && List.of(sourceScopeKey).equals(grant.scopeKeys())
                && futureWhenPresent(grant.validUntil(), now);
    }

    private ProductSurfaceEligibilityDtos.EligibleScope validPeople(
            ProductSurfaceEligibilityDtos.EligibilityResult value,
            OriginProfile profile,
            String sourceScopeKey,
            OffsetDateTime now) {
        if (value == null
                || value.decision() != ProductSurfaceEligibilityDtos.Decision.ALLOWED
                || !canonical(value.productRelationshipRevision(), 500)
                || !canonical(value.targetPopulationRevision(), 500)
                || !future(value.revalidateAt(), now)
                || value.scopes() == null || value.scopes().size() != 1) {
            return null;
        }
        ProductSurfaceEligibilityDtos.EligibleScope selected = value.scopes().getFirst();
        if (selected == null || !sourceScopeKey.equals(selected.sourceScopeKey())
                || !HcmEligibilityScopeKey.isCanonical(selected.key())
                || !profile.scopeKind().equals(selected.kind())
                || !selected.isDefault() || selected.readOnly()
                || !futureWhenPresent(selected.validUntil(), now)) {
            return null;
        }
        return selected;
    }

    private CurrentOriginScope currentScope(
            OriginAuthorityRequest request,
            OriginProfile profile,
            String sourceScopeKey,
            ProductSurfaceEligibilityDtos.EligibleScope selected,
            ProductSurfaceEligibilityDtos.EligibilityResult people,
            AuthorityResult authority,
            OffsetDateTime now) {
        if ("SELF".equals(profile.scopeKind())) {
            // The owner adapter derived this key from the active person/workforce
            // linkage before aggregating its raw revisions for transport. Do not
            // attempt to reverse or infer that private linkage here.
            return new CurrentOriginScope(
                    null, "self:" + selected.key(), SELF_FIELDS, sourceScopeKey,
                    selected.key(), people.productRelationshipRevision(),
                    people.targetPopulationRevision(), earliest(now,
                    authority.revalidateAt(), authority.validUntil(),
                    authority.scopes().getFirst().validUntil(),
                    authority.effectiveGrants().getFirst().validUntil(),
                    people.revalidateAt(), selected.validUntil()));
        }

        HcmPopulationScopeService.ResolvedPopulation population =
                "TEAM_MANAGEMENT".equals(profile.purpose())
                        ? populations.requireTeam()
                        : populations.requireOperations("READ");
        String relationshipRevision = population.relationshipRevision();
        String targetPopulationRevision = population.targetPopulationRevision();
        if (!aggregateRevision("hcm-rel-", relationshipRevision)
                .equals(people.productRelationshipRevision())
                || !aggregateRevision("hcm-pop-", targetPopulationRevision)
                .equals(people.targetPopulationRevision())) {
            return null;
        }
        String expectedDerived = HcmEligibilityScopeKeys.derived(
                request.tenantId(), request.actorUserId(), SURFACE, sourceScopeKey,
                relationshipRevision, targetPopulationRevision);
        if (!expectedDerived.equals(selected.key())) return null;
        return new CurrentOriginScope(
                population, targetPopulationRevision, population.scope().fieldGroups(),
                sourceScopeKey, selected.key(), people.productRelationshipRevision(),
                people.targetPopulationRevision(), earliest(now,
                authority.revalidateAt(), authority.validUntil(),
                authority.scopes().getFirst().validUntil(),
                authority.effectiveGrants().getFirst().validUntil(),
                people.revalidateAt(), selected.validUntil()));
    }

    private String contextDigest(
            OriginAuthorityRequest request,
            OriginRoute route,
            OriginProfile profile,
            AuthorityResult authority,
            String decisionRevision,
            String derivedScopeKey) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (!assignmentFamily(route)) {
            context.put("tenantId", request.tenantId());
            context.put("userId", request.actorUserId());
            context.put("personPublicId", request.actorPersonPublicId());
            context.put("subjectPublicId", request.subjectPublicId());
        }
        context.put("routeContractKey", route.routeContractKey());
        context.put("capabilityContractKey", profile.capability());
        context.put("decisionRevision", decisionRevision);
        context.put("contextKey", authority.contextKey());
        context.put("scopeKey", derivedScopeKey);
        context.put("purposeCode", profile.purpose());
        return HrisExperienceDigests.canonicalSha256(canonicalJson, context);
    }

    private boolean validRequest(
            OriginAuthorityRequest request,
            PeopleRequestContext.Actor actor) {
        return request != null
                && request.tenantId() > 0 && request.actorUserId() > 0
                && request.tenantId() == actor.tenantId()
                && request.actorUserId() == actor.userId()
                && request.actorPersonPublicId() != null
                && request.actorPersonPublicId().equals(actor.personPublicId())
                && request.subjectPublicId() != null
                && request.storedAuthorizationRevision() > 0
                && request.storedContextDigest() != null
                && request.storedContextDigest().matches(DIGEST_PATTERN)
                && canonical(request.commandType(), 100)
                && canonical(request.action(), 100)
                && canonical(request.targetType(), 100)
                && canonical(request.purposeCode(), 100);
    }

    private boolean sealedRequest(
            OriginAuthorityRequest request,
            OriginRoute route,
            OriginProfile profile) {
        return route.routeContractKey().equals(request.expectedRouteContractKey())
                && profile.capability().equals(
                request.expectedCapabilityContractKey())
                && route.method().equals(request.expectedMethod())
                && route.readOnly() == request.expectedReadOnly();
    }

    static int sealedRouteCount() {
        return ROUTES.size();
    }

    static String gatewayDecisionRevision(
            long tenantId,
            long actorUserId,
            String activeAccessMode,
            String authRevision,
            String policyRevision,
            String productRelationshipRevision,
            String targetPopulationRevision,
            String routeContractKey,
            String routeGrantRef) {
        String composite = "psr-" + HrisExperienceDigests.sha256(String.join("\n",
                Long.toString(tenantId), Long.toString(actorUserId), activeAccessMode,
                authRevision, policyRevision, productRelationshipRevision,
                targetPopulationRevision, ""));
        return "psr-" + HrisExperienceDigests.sha256(String.join("\n",
                "dwp:product-surface-evaluation-revision:v1", composite,
                "PRODUCT", PRODUCT, SURFACE, routeContractKey, routeGrantRef));
    }

    private static boolean assignmentFamily(OriginRoute route) {
        return route.key().commandType().startsWith("HRM_ASSIGNMENT_EVENT_");
    }

    private static String aggregateRevision(String prefix, String raw) {
        return prefix + HrisExperienceDigests.sha256(raw).substring(0, 40);
    }

    private OffsetDateTime earliest(
            OffsetDateTime now,
            OffsetDateTime... values) {
        OffsetDateTime earliest = null;
        for (OffsetDateTime candidate : values) {
            if (candidate != null && (earliest == null || candidate.isBefore(earliest))) {
                earliest = candidate;
            }
        }
        if (!future(earliest, now)) throw new IllegalStateException("stale authority");
        return earliest;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static boolean future(OffsetDateTime value, OffsetDateTime now) {
        return value != null && value.isAfter(now);
    }

    private static boolean futureWhenPresent(
            OffsetDateTime value,
            OffsetDateTime now) {
        return value == null || value.isAfter(now);
    }

    private static RestClient client(
            RestClient.Builder builder,
            String baseUrl,
            Duration timeout) {
        if (!canonical(baseUrl, 2_048) || timeout == null || timeout.isZero()
                || timeout.isNegative() || timeout.toMillis() > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "HRIS origin authority URL/timeout is invalid.");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private static String canonicalToken(String value) {
        return canonicalOptional(value, 4_096);
    }

    private static String canonicalOptional(String value, int maximum) {
        if (value == null || value.isBlank()) return "";
        if (!canonical(value, maximum)) {
            throw new IllegalArgumentException("HRIS origin authority value is not canonical.");
        }
        return value;
    }

    private static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static Map.Entry<RouteKey, OriginRoute> route(
            String commandType,
            String action,
            String targetType,
            String routeContractKey,
            OriginProfile... profiles) {
        RouteKey key = new RouteKey(commandType, action, targetType);
        Map<String, OriginProfile> byPurpose = new LinkedHashMap<>();
        for (OriginProfile profile : profiles) {
            if (byPurpose.putIfAbsent(profile.purpose(), profile) != null) {
                throw new IllegalStateException("Duplicate origin purpose.");
            }
        }
        return Map.entry(key, new OriginRoute(
                key, routeContractKey, "POST", false, Map.copyOf(byPurpose)));
    }

    private static OriginProfile profile(
            String purpose,
            String capability,
            String resolvedCapabilityCode,
            String resourceKey,
            String scopeSource,
            String scopeKind,
            Risk risk) {
        return new OriginProfile(
                purpose, capability, resolvedCapabilityCode, resourceKey,
                scopeSource, scopeKind, risk);
    }

    private enum AccessMode {
        NORMAL,
        ELEVATED;

        static AccessMode parse(String value) {
            try {
                return value == null ? null : valueOf(value);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }

    private enum Risk {
        MEDIUM,
        HIGH
    }

    private record RouteKey(String commandType, String action, String targetType) {
    }

    private record OriginRoute(
            RouteKey key,
            String routeContractKey,
            String method,
            boolean readOnly,
            Map<String, OriginProfile> profiles) {
    }

    private record OriginProfile(
            String purpose,
            String capability,
            String resolvedCapabilityCode,
            String resourceKey,
            String scopeSource,
            String scopeKind,
            Risk risk) {
    }

    private record EvaluateRequest(
            long tenantId,
            long actorId,
            String productKey,
            String surfaceKey,
            String activeAccessMode,
            String routeContractKey,
            String contextKey,
            String contextScopeKey,
            String supportSessionRef,
            String supportRevision,
            List<String> supportScopes) {
    }

    record AuthorityResult(
            String decision,
            String reasonCode,
            String authRevision,
            String policyRevision,
            String contextKey,
            String productKey,
            String surfaceKey,
            String plane,
            String accessMode,
            String accessSource,
            String appResourceKey,
            List<EffectiveGrant> effectiveGrants,
            List<EffectiveScope> scopes,
            String routeGrantRef,
            Boolean effectiveReadOnly,
            Boolean requiresProductEligibility,
            OffsetDateTime validUntil,
            OffsetDateTime expiredAt,
            String requiredAssurance,
            String requestPolicyRef,
            OffsetDateTime revalidateAt,
            String evidenceRef) {
    }

    record EffectiveGrant(
            String grantKind,
            String capabilityContractKey,
            String resolvedCapabilityCode,
            String accessPolicyKey,
            String policyDecisionRef,
            String authorityMode,
            List<String> predicatePolicyKeys,
            String responsibilityRequirement,
            Responsibility responsibility,
            List<String> scopeKeys,
            Boolean requiresProductEntitlement,
            Boolean readOnly,
            String activationState,
            OffsetDateTime validUntil) {
    }

    record Responsibility(String code, String resourceSetKey) {
    }

    record EffectiveScope(
            String key,
            String kind,
            String displayName,
            Boolean isDefault,
            Boolean readOnly,
            OffsetDateTime validUntil) {
    }
}
