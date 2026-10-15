package com.dwp.services.people.hris.experience;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmEligibilityScopeKeys;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.security.ProductSurfaceEligibilityDtos;
import com.dwp.services.people.security.ProductSurfaceEligibilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class AuthHrisExperienceOriginReceiptAuthoritySourceTest {

    private static final long TENANT = 77L;
    private static final long USER = 19L;
    private static final UUID ACTOR = UUID.fromString(
            "00000000-0000-4000-8000-000000000077");
    private static final UUID SUBJECT = UUID.fromString(
            "00000000-0000-4000-8000-000000000078");
    private static final Instant NOW = Instant.parse("2026-10-15T03:30:00Z");
    private static final OffsetDateTime FRESH =
            OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC);
    private static final String AUTH_REVISION = "auth-current";
    private static final String POLICY_REVISION = "policy-current";
    private static final String CONTEXT_KEY = "psc-" + "1".repeat(64);
    private static final String ROUTE_GRANT = "grant-" + "2".repeat(32);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ProductSurfaceEligibilityService eligibility =
            mock(ProductSurfaceEligibilityService.class);
    private final HcmPopulationScopeService populations =
            mock(HcmPopulationScopeService.class);

    @BeforeEach
    void actor() {
        PeopleRequestContext.set(USER, TENANT, ACTOR, Set.of(), Set.of());
        accessMode("NORMAL");
    }

    @AfterEach
    void clear() {
        PeopleRequestContext.clear();
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
    }

    @Test
    void admitsAllSevenActionsAndAllElevenExactRouteProfiles() throws Exception {
        assertThat(AuthHrisExperienceOriginReceiptAuthoritySource.sealedRouteCount())
                .isEqualTo(7);
        List<Case> cases = allNormalCases();
        assertThat(cases).hasSize(11);
        assertThat(cases.stream().map(value -> value.request().action()).distinct())
                .hasSize(7);
        for (Case value : cases) {
            Fixture fixture = fixture("urn:dwp:acr:mfa");
            fixture.expect(value.authority());
            stubPeople(value);

            Optional<HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority> result =
                    fixture.source().resolve(value.request());

            assertThat(result).as(value.request().action() + '/' + value.request().purposeCode())
                    .isPresent();
            assertThat(result.orElseThrow().scope().self())
                    .isEqualTo("EMPLOYEE_SELF_SERVICE".equals(
                            value.request().purposeCode()));
            fixture.server().verify();
        }
    }

    @Test
    void admitsNormalHighRiskOnlyAsStepUpEligibleWithExactAssurance()
            throws Exception {
        Case value = assignmentPublishCase("NORMAL");
        Fixture fixture = fixture("urn:dwp:acr:mfa");
        fixture.expect(value.authority());
        when(eligibility.evaluate(any())).thenReturn(value.people());
        when(populations.requireOperations("READ")).thenReturn(value.population());

        assertThat(fixture.source().resolve(value.request())).isPresent();
        fixture.server().verify();

        Fixture blankAssurance = fixture("");
        blankAssurance.expect(value.authority());
        assertThat(blankAssurance.source().resolve(value.request())).isEmpty();
        blankAssurance.server().verify();
    }

    @Test
    void admitsElevatedHighRiskOnlyAsAllowedActive() throws Exception {
        accessMode("ELEVATED");
        for (Case value : List.of(
                assignmentPublishCase("ELEVATED"), employeeApplyCase("ELEVATED"))) {
            Fixture fixture = fixture("urn:dwp:acr:mfa");
            fixture.expect(value.authority());
            stubPeople(value);

            assertThat(fixture.source().resolve(value.request())).isPresent();
            fixture.server().verify();
        }
    }

    @Test
    void deniesStoredEvidenceOrSealedProfileDriftBeforeProjection()
            throws Exception {
        Case value = selfCase();
        Fixture fixture = fixture("urn:dwp:acr:mfa");
        when(eligibility.evaluate(any())).thenReturn(value.people());
        var drift = new HrisExperienceOriginReceiptAuthoritySource.OriginAuthorityRequest(
                value.request().tenantId(), value.request().actorUserId(),
                value.request().actorPersonPublicId(), value.request().commandType(),
                value.request().action(), value.request().targetType(),
                value.request().purposeCode(), value.request().expectedRouteContractKey(),
                "hcm.operations.people.update", value.request().expectedMethod(),
                value.request().expectedReadOnly(),
                value.request().storedAuthorizationRevision(),
                value.request().storedContextDigest(), value.request().subjectPublicId());

        assertThat(fixture.source().resolve(drift)).isEmpty();

        fixture.expect(value.authority());
        var contextDrift = new HrisExperienceOriginReceiptAuthoritySource
                .OriginAuthorityRequest(
                value.request().tenantId(), value.request().actorUserId(),
                value.request().actorPersonPublicId(), value.request().commandType(),
                value.request().action(), value.request().targetType(),
                value.request().purposeCode(), value.request().expectedRouteContractKey(),
                value.request().expectedCapabilityContractKey(),
                value.request().expectedMethod(), value.request().expectedReadOnly(),
                value.request().storedAuthorizationRevision(), "f".repeat(64),
                value.request().subjectPublicId());
        assertThat(fixture.source().resolve(contextDrift)).isEmpty();
        fixture.server().verify();
    }

    @Test
    void rejectsRiskAndModeDecisionSubstitution() throws Exception {
        Case medium = selfCase();
        assertJsonDenied(medium, node -> {
            node.put("decision", "STEP_UP_REQUIRED");
            node.put("reasonCode", "STEP_UP_REQUIRED");
            grant(node).put("activationState", "ELIGIBLE");
            node.put("requiredAssurance", "urn:dwp:acr:mfa");
            node.put("requestPolicyRef", "STEPUP-MGMT-HIGH-V1");
        });

        Case normalHigh = assignmentPublishCase("NORMAL");
        assertJsonDenied(normalHigh, node -> {
            node.put("decision", "ALLOWED");
            node.put("reasonCode", "ALLOWED");
            grant(node).put("activationState", "ACTIVE");
            node.putNull("requiredAssurance");
            node.putNull("requestPolicyRef");
        });

        accessMode("ELEVATED");
        Case elevatedHigh = assignmentPublishCase("ELEVATED");
        assertJsonDenied(elevatedHigh, node -> {
            node.put("decision", "STEP_UP_REQUIRED");
            node.put("reasonCode", "STEP_UP_REQUIRED");
            grant(node).put("activationState", "ELIGIBLE");
            node.put("requiredAssurance", "urn:dwp:acr:mfa");
            node.put("requestPolicyRef", "STEPUP-MGMT-HIGH-V1");
        });
    }

    @Test
    void rejectsStaleOrAmbiguousAuthEvidence() throws Exception {
        Case value = selfCase();
        String stale = OffsetDateTime.ofInstant(
                NOW.minusSeconds(1), ZoneOffset.UTC).toString();
        List<Consumer<ObjectNode>> invalid = List.of(
                node -> node.put("revalidateAt", stale),
                node -> node.put("validUntil", stale),
                node -> scope(node).put("validUntil", stale),
                node -> grant(node).put("validUntil", stale),
                node -> node.withArray("scopes").add(scope(node).deepCopy()),
                node -> node.withArray("effectiveGrants").add(grant(node).deepCopy()));
        for (Consumer<ObjectNode> mutation : invalid) {
            assertJsonDenied(value, mutation);
        }
    }

    @Test
    void rejectsEveryExactGrantAndSourceDimensionDrift() throws Exception {
        Case value = selfCase();
        List<Consumer<ObjectNode>> invalid = List.of(
                node -> node.put("reasonCode", "WRONG"),
                node -> node.put("accessSource", "RELATIONSHIP"),
                node -> node.put("appResourceKey", "DATA.HRIS_WORKFORCE"),
                node -> grant(node).put("grantKind", "POLICY"),
                node -> grant(node).put(
                        "capabilityContractKey", "hcm.operations.people.update"),
                node -> grant(node).put("resolvedCapabilityCode", "DATA.WRONG:UPDATE"),
                node -> grant(node).put("authorityMode", "PERMISSION"),
                node -> grant(node).putArray("predicatePolicyKeys"),
                node -> grant(node).put("responsibilityRequirement", "REQUIRED"),
                node -> grant(node).putObject("responsibility")
                        .put("code", "OWNER").put("resourceSetKey", "RS-HCM"),
                node -> grant(node).put("requiresProductEntitlement", true),
                node -> grant(node).put("readOnly", true),
                node -> grant(node).put("activationState", "ELIGIBLE"),
                node -> scope(node).put("key", "scope-" + "9".repeat(32)),
                node -> scope(node).put("kind", "TARGET_POPULATION"),
                node -> scope(node).put("isDefault", false),
                node -> scope(node).put("readOnly", true),
                node -> grant(node).putArray("scopeKeys")
                        .add("scope-" + "9".repeat(32)));
        for (Consumer<ObjectNode> mutation : invalid) {
            assertJsonDenied(value, mutation);
        }
    }

    @Test
    void strictJsonRejectsUnknownDuplicateTrailingAndScalarCoercion()
            throws Exception {
        Case value = selfCase();
        String valid = mapper.writeValueAsString(value.authority());
        ObjectNode unknown = mapper.valueToTree(value.authority());
        unknown.put("unsealed", true);
        List<String> invalid = List.of(
                mapper.writeValueAsString(unknown),
                valid.replaceFirst("\"decision\":\"ALLOWED\"",
                        "\"decision\":\"ALLOWED\",\"decision\":\"ALLOWED\""),
                valid + " {}",
                valid.replace("\"effectiveReadOnly\":false",
                        "\"effectiveReadOnly\":\"false\""),
                "{");
        for (String response : invalid) {
            Fixture fixture = fixture("urn:dwp:acr:mfa");
            fixture.expectRaw(response);
            assertThat(fixture.source().resolve(value.request())).isEmpty();
            fixture.server().verify();
        }
    }

    @Test
    void transportServerAndTimeoutFailuresAreOpaque() throws Exception {
        Case value = selfCase();
        Fixture serverFailure = fixture("urn:dwp:acr:mfa");
        serverFailure.expectFailure(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(serverFailure.source().resolve(value.request())).isEmpty();
        serverFailure.server().verify();

        Fixture timeout = fixture("urn:dwp:acr:mfa");
        timeout.expectFailure(request -> {
            throw new SocketTimeoutException("simulated timeout");
        });
        assertThat(timeout.source().resolve(value.request())).isEmpty();
        timeout.server().verify();
    }

    private void assertJsonDenied(Case value, Consumer<ObjectNode> mutation)
            throws Exception {
        ObjectNode response = mapper.valueToTree(value.authority());
        mutation.accept(response);
        Fixture fixture = fixture("urn:dwp:acr:mfa");
        fixture.expectRaw(mapper.writeValueAsString(response));
        stubPeople(value);
        assertThat(fixture.source().resolve(value.request())).isEmpty();
        fixture.server().verify();
    }

    private static ObjectNode grant(ObjectNode response) {
        return (ObjectNode) response.withArray("effectiveGrants").get(0);
    }

    private static ObjectNode scope(ObjectNode response) {
        return (ObjectNode) response.withArray("scopes").get(0);
    }

    private Case selfCase() {
        String command = "EMPLOYEE_CHANGE_CREATE";
        String action = "CREATE_EMPLOYEE_CHANGE_REQUEST";
        String target = "EMPLOYEE_CHANGE_REQUEST";
        String purpose = "EMPLOYEE_SELF_SERVICE";
        String route = "route.hcm.operations.employee-change-request-create.action";
        String capability = "hcm.personal.profile.update";
        return testCase(command, action, target, purpose, route, capability,
                "SELF", "SELF", "NORMAL", null);
    }

    private List<Case> allNormalCases() {
        HcmPopulationScopeService.ResolvedPopulation operations = population();
        return List.of(
                testCase("HRM_ASSIGNMENT_EVENT_CREATE", "CREATE_ASSIGNMENT_EVENT",
                        "EMPLOYMENT_EVENT", "HR_LIFECYCLE",
                        "route.hcm.operations.assignment-event-create.action",
                        "hcm.operations.assignment.manage",
                        "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                        "NORMAL", operations),
                testCase("HRM_ASSIGNMENT_EVENT_VALIDATE", "VALIDATE_ASSIGNMENT_EVENT",
                        "EMPLOYMENT_EVENT", "HR_LIFECYCLE",
                        "route.hcm.operations.assignment-event-validate.action",
                        "hcm.operations.assignment.manage",
                        "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                        "NORMAL", operations),
                testCase("HRM_ASSIGNMENT_EVENT_SUBMIT", "SUBMIT_ASSIGNMENT_EVENT",
                        "EMPLOYMENT_EVENT", "HR_LIFECYCLE",
                        "route.hcm.operations.assignment-event-submit.action",
                        "hcm.operations.assignment.manage",
                        "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                        "NORMAL", operations),
                testCase("HRM_ASSIGNMENT_EVENT_PUBLISH", "PUBLISH_ASSIGNMENT_EVENT",
                        "EMPLOYMENT_EVENT", "HR_LIFECYCLE",
                        "route.hcm.operations.assignment-event-publish.action",
                        "hcm.operations.people-change.approve",
                        "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                        "NORMAL", operations),
                employeeCase("CREATE", "HR_OPERATIONS", "NORMAL", operations),
                employeeCase("CREATE", "TEAM_MANAGEMENT", "NORMAL", operations),
                employeeCase("CREATE", "EMPLOYEE_SELF_SERVICE", "NORMAL", null),
                employeeCase("SUBMIT", "HR_OPERATIONS", "NORMAL", operations),
                employeeCase("SUBMIT", "TEAM_MANAGEMENT", "NORMAL", operations),
                employeeCase("SUBMIT", "EMPLOYEE_SELF_SERVICE", "NORMAL", null),
                testCase("EMPLOYEE_CHANGE_APPLY", "APPLY_EMPLOYEE_CHANGE_REQUEST",
                        "EMPLOYEE_CHANGE_REQUEST", "HR_LIFECYCLE",
                        "route.hcm.operations.employee-change-request-apply.action",
                        "hcm.operations.people-change.approve",
                        "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION",
                        "NORMAL", operations));
    }

    private Case employeeCase(
            String verb,
            String purpose,
            String mode,
            HcmPopulationScopeService.ResolvedPopulation population) {
        String capability = switch (purpose) {
            case "HR_OPERATIONS" -> "hcm.operations.people.update";
            case "TEAM_MANAGEMENT" -> "hcm.team.people-change.request";
            case "EMPLOYEE_SELF_SERVICE" -> "hcm.personal.profile.update";
            default -> throw new IllegalArgumentException("Unexpected purpose");
        };
        String source = switch (purpose) {
            case "HR_OPERATIONS" -> "WORKFORCE_TARGET_POPULATION";
            case "TEAM_MANAGEMENT" ->
                    "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION";
            case "EMPLOYEE_SELF_SERVICE" -> "SELF";
            default -> throw new IllegalArgumentException("Unexpected purpose");
        };
        String suffix = verb.toLowerCase();
        return testCase("EMPLOYEE_CHANGE_" + verb,
                verb + "_EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_CHANGE_REQUEST",
                purpose,
                "route.hcm.operations.employee-change-request-" + suffix + ".action",
                capability, source,
                "EMPLOYEE_SELF_SERVICE".equals(purpose) ? "SELF" : "TARGET_POPULATION",
                mode, population);
    }

    private HcmPopulationScopeService.ResolvedPopulation population() {
        var workforce = new HcmPopulationRepository.ActorWorkforce(
                91L, ACTOR, "Actor", "assignment-1", "Manager", "Operations",
                2L, 3L, 4L);
        var scope = new HcmPopulationRepository.PopulationScope(
                91L, "assignment-1", true, Set.of(),
                Set.of("DIRECTORY", "EMPLOYMENT"), "policy");
        return new HcmPopulationScopeService.ResolvedPopulation(
                workforce, scope,
                new HcmPopulationRepository.PopulationEvidence(1L, "population:7"));
    }

    private void stubPeople(Case value) {
        when(eligibility.evaluate(any())).thenReturn(value.people());
        if (value.population() == null) return;
        if ("TEAM_MANAGEMENT".equals(value.request().purposeCode())) {
            when(populations.requireTeam()).thenReturn(value.population());
        } else {
            when(populations.requireOperations("READ")).thenReturn(value.population());
        }
    }

    private Case assignmentPublishCase(String mode) {
        var population = population();
        return testCase("HRM_ASSIGNMENT_EVENT_PUBLISH",
                "PUBLISH_ASSIGNMENT_EVENT", "EMPLOYMENT_EVENT", "HR_LIFECYCLE",
                "route.hcm.operations.assignment-event-publish.action",
                "hcm.operations.people-change.approve",
                "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION", mode,
                population);
    }

    private Case employeeApplyCase(String mode) {
        return testCase("EMPLOYEE_CHANGE_APPLY", "APPLY_EMPLOYEE_CHANGE_REQUEST",
                "EMPLOYEE_CHANGE_REQUEST", "HR_LIFECYCLE",
                "route.hcm.operations.employee-change-request-apply.action",
                "hcm.operations.people-change.approve",
                "WORKFORCE_TARGET_POPULATION", "TARGET_POPULATION", mode,
                population());
    }

    private Case testCase(
            String command,
            String action,
            String target,
            String purpose,
            String route,
            String capability,
            String source,
            String kind,
            String mode,
            HcmPopulationScopeService.ResolvedPopulation population) {
        String rawScope = ProductSurfaceScopeKey.key(
                TENANT, USER, "hcm", "hcm.operations", source, kind);
        String relationshipRaw = population == null
                ? "self-worker:1" : population.relationshipRevision();
        String populationRaw = population == null
                ? "self:91:self-worker:1" : population.targetPopulationRevision();
        String relationship = "hcm-rel-"
                + HrisExperienceDigests.sha256(relationshipRaw).substring(0, 40);
        String populationRevision = "hcm-pop-"
                + HrisExperienceDigests.sha256(populationRaw).substring(0, 40);
        String derived = HcmEligibilityScopeKeys.derived(
                TENANT, USER, "hcm.operations", rawScope,
                relationshipRaw, populationRaw);
        var people = new ProductSurfaceEligibilityDtos.EligibilityResult(
                ProductSurfaceEligibilityDtos.Decision.ALLOWED, null,
                relationship, populationRevision,
                List.of(new ProductSurfaceEligibilityDtos.EligibleScope(
                        rawScope, derived, kind, "Current scope", true, false, FRESH)),
                FRESH, "hcm-evidence-current");
        boolean high = action.startsWith("PUBLISH_") || action.startsWith("APPLY_");
        boolean stepUp = high && "NORMAL".equals(mode);
        Capability expected = capability(capability);
        var grant = new AuthHrisExperienceOriginReceiptAuthoritySource.EffectiveGrant(
                "CAPABILITY", capability, expected.resolvedCode(), null, null,
                "PERMISSION_AND_RELATIONSHIP",
                List.of(AuthHrisExperienceOriginReceiptAuthoritySource
                        .AUTHORITY_PREDICATE),
                "NOT_REQUIRED", null, List.of(rawScope), false, false,
                stepUp ? "ELIGIBLE" : "ACTIVE", FRESH);
        var authority = new AuthHrisExperienceOriginReceiptAuthoritySource.AuthorityResult(
                stepUp ? "STEP_UP_REQUIRED" : "ALLOWED",
                stepUp ? "STEP_UP_REQUIRED" : "ALLOWED",
                AUTH_REVISION, POLICY_REVISION, CONTEXT_KEY, "hcm",
                "hcm.operations", "management", mode, "MANAGEMENT",
                expected.resourceKey(),
                List.of(grant),
                List.of(new AuthHrisExperienceOriginReceiptAuthoritySource.EffectiveScope(
                        rawScope, kind, "Current scope", true, false, FRESH)),
                ROUTE_GRANT, false, true, FRESH, null,
                stepUp ? "urn:dwp:acr:mfa" : null,
                stepUp ? "STEPUP-MGMT-HIGH-V1" : null, FRESH,
                "evidence-current");
        String decision = AuthHrisExperienceOriginReceiptAuthoritySource
                .gatewayDecisionRevision(
                        TENANT, USER, mode, AUTH_REVISION, POLICY_REVISION,
                        relationship, populationRevision, route, ROUTE_GRANT);
        long storedRevision = command.startsWith("HRM_ASSIGNMENT_EVENT_")
                ? HrisExperienceDigests.assignmentRevision(decision)
                : HrisExperienceDigests.employeeChangeRevision(decision);
        Map<String, Object> context = new LinkedHashMap<>();
        if (!command.startsWith("HRM_ASSIGNMENT_EVENT_")) {
            context.put("tenantId", TENANT);
            context.put("userId", USER);
            context.put("personPublicId", ACTOR);
            context.put("subjectPublicId", SUBJECT);
        }
        context.put("routeContractKey", route);
        context.put("capabilityContractKey", capability);
        context.put("decisionRevision", decision);
        context.put("contextKey", CONTEXT_KEY);
        context.put("scopeKey", derived);
        context.put("purposeCode", purpose);
        String contextDigest = HrisExperienceDigests.canonicalSha256(mapper, context);
        var request = new HrisExperienceOriginReceiptAuthoritySource
                .OriginAuthorityRequest(
                TENANT, USER, ACTOR, command, action, target, purpose, route,
                capability, "POST", false, storedRevision, contextDigest, SUBJECT);
        return new Case(request, authority, people, population);
    }

    private Capability capability(String contractKey) {
        return switch (contractKey) {
            case "hcm.operations.assignment.manage" -> new Capability(
                    "ACTION.HRIS_ASSIGNMENT_EVENT:MANAGE",
                    "ACTION.HRIS_ASSIGNMENT_EVENT");
            case "hcm.operations.people-change.approve" -> new Capability(
                    "ACTION.HRIS_WORKFORCE_CHANGE_APPROVE:APPROVE",
                    "ACTION.HRIS_WORKFORCE_CHANGE_APPROVE");
            case "hcm.operations.people.update" -> new Capability(
                    "DATA.HRIS_WORKFORCE:UPDATE", "DATA.HRIS_WORKFORCE");
            case "hcm.team.people-change.request" -> new Capability(
                    "ACTION.HRIS_PEOPLE_CHANGE_REQUEST:CREATE",
                    "ACTION.HRIS_PEOPLE_CHANGE_REQUEST");
            case "hcm.personal.profile.update" -> new Capability(
                    "DATA.HRIS_SELF_PROFILE:UPDATE", "DATA.HRIS_SELF_PROFILE");
            default -> throw new IllegalArgumentException("Unexpected capability");
        };
    }

    private Fixture fixture(String assurance) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var source = new AuthHrisExperienceOriginReceiptAuthoritySource(
                builder.baseUrl("http://auth").build(), mapper, eligibility,
                populations, "origin-secret", assurance,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(source, server);
    }

    private void accessMode(String mode) {
        HcmPepContext.Evidence evidence = mock(HcmPepContext.Evidence.class);
        when(evidence.activeAccessMode()).thenReturn(mode);
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set", evidence);
    }

    private record Case(
            HrisExperienceOriginReceiptAuthoritySource.OriginAuthorityRequest request,
            AuthHrisExperienceOriginReceiptAuthoritySource.AuthorityResult authority,
            ProductSurfaceEligibilityDtos.EligibilityResult people,
            HcmPopulationScopeService.ResolvedPopulation population) {
    }

    private record Capability(String resolvedCode, String resourceKey) {
    }

    private record Fixture(
            AuthHrisExperienceOriginReceiptAuthoritySource source,
            MockRestServiceServer server) {

        void expect(AuthHrisExperienceOriginReceiptAuthoritySource.AuthorityResult value)
                throws Exception {
            expectRaw(new ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(value));
        }

        void expectRaw(String value) {
            request().andRespond(withSuccess(value, MediaType.APPLICATION_JSON));
        }

        void expectFailure(ResponseCreator response) {
            request().andRespond(response);
        }

        private ResponseActions request() {
            return server.expect(once(), requestTo("http://auth"
                            + AuthHrisExperienceOriginReceiptAuthoritySource.AUTHORITY_PATH))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header(
                            AuthHrisExperienceOriginReceiptAuthoritySource
                                    .SERVICE_IDENTITY_HEADER,
                            AuthHrisExperienceOriginReceiptAuthoritySource.SERVICE_IDENTITY))
                    .andExpect(header(
                            AuthHrisExperienceOriginReceiptAuthoritySource
                                    .ORIGIN_TOKEN_HEADER,
                            "origin-secret"));
        }
    }
}
