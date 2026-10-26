package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthorityRequest;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.CONTEXT;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.DECISION;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.NOW;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.SCOPE;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.SUBJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthHrisExplorerAuthorityEvidenceQueryTest {

    private static final String AUTH_URL = "http://auth.test";
    private static final String PEOPLE_URL = "http://people.test";
    private static final String RAW_SCOPE = "scope-" + "9".repeat(32);
    private static final String RAW_SCOPE_ALIAS = "scope-" + "8".repeat(32);
    private static final String RELATIONSHIP_REVISION = "relationship-v1";
    private static final String POPULATION_REVISION = "population-v1";
    private static final String DERIVED_SCOPE = HcmEligibilityScopeKey.derived(
            42, 84, "hcm.utility", RAW_SCOPE,
            RELATIONSHIP_REVISION, POPULATION_REVISION);
    private static final String PAY_SCOPE =
            "scope-0b4a9f69ff6219b40b0c9c48e7576271";
    private static final String PAYROLL_LEGAL_ENTITY_SCOPE =
            "scope-312eb5f15464a0a9e9ab4b6aaa4c1f19";
    private static final String PAY_DERIVED_SCOPE =
            "hcm-scope-3b21f50dc0bc6974f09f6bcb2b987f677ce5b7a5";
    private static final String PLATFORM_RECEIPT_SCOPE = ProductSurfaceScopeKey.key(
            42, 84, "hcm", "hcm.utility",
            "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION");
    private static final String PLATFORM_RECEIPT_CONTEXT = "psc-" + "6".repeat(64);
    private static final String PLATFORM_RECEIPT_DECISION =
            AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                    42, 84, "NORMAL", "auth-platform-receipt-v1",
                    "policy-platform-receipt-v1", null, null, "hcm.utility",
                    AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE,
                    "route-grant:platform-command-receipt");
    private static final String ORIGIN_RAW_SCOPE = "scope-" + "4".repeat(32);
    private static final String ORIGIN_DERIVED_SCOPE = HcmEligibilityScopeKey.derived(
            42, 84, "hcm.management", ORIGIN_RAW_SCOPE,
            RELATIONSHIP_REVISION, POPULATION_REVISION);
    private static final String ORIGIN_CONTEXT = "psc-" + "5".repeat(64);
    private static final String ORIGIN_ROUTE =
            "route.hcm.configuration.configuration-version-publish.action";
    private static final String CONTROLLED_TRANSFER_ROUTE =
            "route.hcm.management.controlled-transfer-create.action";
    private static final String ORIGIN_DECISION =
            AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                    42, 84, "NORMAL", "auth-origin-v1", "policy-origin-v1",
                    RELATIONSHIP_REVISION, POPULATION_REVISION, "hcm.management",
                    ORIGIN_ROUTE, "route-grant:configuration-publish");

    private static final Map<String, String> EXPECTED_ORIGINS = Map.ofEntries(
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
            Map.entry("CONTROLLED_TRANSFER_CREATE", CONTROLLED_TRANSFER_ROUTE),
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
                    "route.hcm.configuration.automation-run-cancel.action"),
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

    @Test
    void acceptsExactlyTheFortySevenProducerActionRoutePairs() {
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                .PLATFORM_ORIGIN_ROUTES_BY_ACTION)
                .containsExactlyInAnyOrderEntriesOf(EXPECTED_ORIGINS);
        EXPECTED_ORIGINS.forEach((action, route) ->
                assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                        .exactPlatformOrigin(action, route)).isTrue());
    }

    @Test
    void automationReceiptBridgeContainsExactlyTheFourteenSealedPairs() {
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                .AUTOMATION_ORIGIN_ROUTES_BY_ACTION)
                .hasSize(14)
                .allSatisfy((action, route) -> {
                    assertThat(action).startsWith("hris.action.sys.post.platform.");
                    assertThat(EXPECTED_ORIGINS).containsEntry(action, route);
                    assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                            .exactPlatformOrigin(action, route)).isTrue();
                    assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                            .exactPlatformOriginPurpose(
                                    action, "HRIS_SYSTEM_ADMIN")).isTrue();
                });
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                .AUTOMATION_ORIGIN_ROUTES_BY_ACTION.values())
                .doesNotHaveDuplicates();
    }

    @Test
    void integrationReceiptBridgeContainsExactlyTheSixteenSealedPairs() {
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                .INTEGRATION_ORIGIN_ROUTES_BY_ACTION)
                .hasSize(16)
                .allSatisfy((action, route) -> {
                    assertThat(action).startsWith("hris.action.sys.post.platform.");
                    assertThat(EXPECTED_ORIGINS).containsEntry(action, route);
                    assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                            .exactPlatformOrigin(action, route)).isTrue();
                    assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                            .exactPlatformOriginPurpose(
                                    action, "HRIS_INTEGRATION_ADMIN")).isTrue();
                    assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                            .exactPlatformOriginPurpose(
                                    action, "HRIS_SYSTEM_ADMIN")).isFalse();
                });
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery
                .INTEGRATION_ORIGIN_ROUTES_BY_ACTION.values())
                .doesNotHaveDuplicates();
    }

    @Test
    void rejectsUnknownAndCrossPairedOriginSeals() {
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOrigin(
                "configuration.version.publish",
                EXPECTED_ORIGINS.get("configuration.version.create"))).isFalse();
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOrigin(
                "PUBLISH", EXPECTED_ORIGINS.get("RESTORE"))).isFalse();
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOrigin(
                "UNKNOWN", EXPECTED_ORIGINS.get("PUBLISH"))).isFalse();
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOrigin(
                "PUBLISH", null)).isFalse();
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOrigin(
                null, CONTROLLED_TRANSFER_ROUTE)).isFalse();
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOrigin(
                "hris.action.sys.post.platform.v1.admin.hris.automations",
                "route.hcm.configuration.automation-run-cancel.action")).isFalse();
    }

    @Test
    void controlledTransferHasItsOwnClosedPurposeWhileLegacyOriginsStaySystemAdmin() {
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOriginPurpose(
                "CONTROLLED_TRANSFER_CREATE", "HRIS_INTEGRATION_ADMIN")).isTrue();
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOriginPurpose(
                "CONTROLLED_TRANSFER_CREATE", "HRIS_SYSTEM_ADMIN")).isFalse();
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOriginPurpose(
                "configuration.version.publish", "HRIS_SYSTEM_ADMIN")).isTrue();
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOriginPurpose(
                "configuration.version.publish", "HRIS_INTEGRATION_ADMIN")).isFalse();
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.exactPlatformOriginPurpose(
                null, "HRIS_INTEGRATION_ADMIN")).isFalse();
    }

    @Test
    void frontendProjectionAndEntitlementRevisionsArePositiveAndJavascriptSafe() {
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.numericRevision(
                "entitlement", "auth-revision-v1"))
                .isBetween(1L,
                        HrisExplorerAuthoritySeals.MAX_JSON_SAFE_INTEGER);
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.projectionRevision())
                .isBetween(1L,
                        HrisExplorerAuthoritySeals.MAX_JSON_SAFE_INTEGER);
    }

    @Test
    void storedAuthorityRevisionsExactlyMatchGatewayDerivation() {
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                "field-policy", "policy-v38"))
                .isEqualTo(4663670355146917040L);
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                "authorization", "auth-v38"))
                .isEqualTo(1991387878219104048L);
        assertThat(HrisExplorerAuthoritySeals.publicRevision(
                4663670355146917040L)).isEqualTo(6948340445824176L);
        assertThat(HrisExplorerAuthoritySeals.publicRevision(
                1991387878219104048L)).isEqualTo(796842921344816L);
        assertThat(HrisExplorerAuthoritySeals.publicRevision(7L))
                .isEqualTo(HrisExplorerAuthoritySeals.publicRevision(
                        7L + (1L << 53)));
    }

    @Test
    void decisionRevisionMatchesIndependentGatewayGoldenVector() {
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                42, 84, "NORMAL", "auth-v1", "policy-v1",
                RELATIONSHIP_REVISION, POPULATION_REVISION, "hcm.utility",
                AuthHrisExplorerAuthorityEvidenceQuery.EXPLORER_PAGE_ROUTE,
                "route-grant:explorer-page"))
                .isEqualTo("psr-27d15e4364725a4e5a2eda8c9a6a6dc3"
                        + "46b4eaac22d7254daf7604539393fce9");
    }

    @Test
    void peopleFreeReceiptDecisionRevisionMatchesIndependentGatewayGoldenVector() {
        assertThat(AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                42, 84, "NORMAL", "auth-platform-receipt-v1",
                "policy-platform-receipt-v1", null, null, "hcm.utility",
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE,
                "route-grant:platform-command-receipt"))
                .isEqualTo("psr-2ccfb6a15194a945e0d991696e938e809"
                        + "3a0f4b7c47e5681d84ceea38c35855e");
    }

    @Test
    void platformReceiptPhaseOneRebindsTheExactCanonicalSelectorWithoutPeople() {
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(AUTH_URL);
        RestClient.Builder peopleBuilder = RestClient.builder().baseUrl(PEOPLE_URL);
        MockRestServiceServer authServer =
                MockRestServiceServer.bindTo(authBuilder).build();
        MockRestServiceServer peopleServer =
                MockRestServiceServer.bindTo(peopleBuilder).build();
        AuthHrisExplorerAuthorityEvidenceQuery query = query(
                authBuilder.build(), peopleBuilder.build());
        expectIdentity(authServer);
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey").value(
                        AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE))
                .andExpect(jsonPath("$.contextScopeKey").value(
                        PLATFORM_RECEIPT_SCOPE))
                .andRespond(withSuccess(
                        allowedPlatformReceipt(), MediaType.APPLICATION_JSON));

        Optional<HrisPlatformReceiptAuthoritySource.ForwardedReceiptAuthority> resolved =
                query.resolveForwardedReceipt(platformReceiptRequest());

        assertThat(resolved).contains(
                new HrisPlatformReceiptAuthoritySource.ForwardedReceiptAuthority(
                        42, SUBJECT, PLATFORM_RECEIPT_CONTEXT,
                        PLATFORM_RECEIPT_SCOPE, PLATFORM_RECEIPT_DECISION,
                        NOW.plusMinutes(5)));
        authServer.verify();
        peopleServer.verify();
    }

    @Test
    void explorerForwardedPlatformReceiptSeparatelyReprovesReceiptAndStoredOrigin() {
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(AUTH_URL);
        RestClient.Builder peopleBuilder = RestClient.builder().baseUrl(PEOPLE_URL);
        MockRestServiceServer authServer =
                MockRestServiceServer.bindTo(authBuilder).build();
        MockRestServiceServer peopleServer =
                MockRestServiceServer.bindTo(peopleBuilder).build();
        AuthHrisExplorerAuthorityEvidenceQuery query = query(
                authBuilder.build(), peopleBuilder.build());
        expectIdentity(authServer);
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey").value(
                        AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE))
                .andExpect(jsonPath("$.contextScopeKey").value(
                        PLATFORM_RECEIPT_SCOPE))
                .andRespond(withSuccess(
                        allowedPlatformReceipt(), MediaType.APPLICATION_JSON));
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey").value(ORIGIN_ROUTE))
                .andExpect(jsonPath("$.contextKey").value(ORIGIN_CONTEXT))
                .andExpect(jsonPath("$.contextScopeKey").value(
                        ORIGIN_DERIVED_SCOPE))
                .andRespond(withSuccess(
                        allowedManagementOrigin(), MediaType.APPLICATION_JSON));
        peopleServer.expect(requestTo(PEOPLE_URL
                        + "/internal/people/v1/product-surface-eligibility/evaluate"))
                .andRespond(withSuccess(
                        allowedManagementPeople(), MediaType.APPLICATION_JSON));
        long exactField = AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                "field-policy", "policy-origin-v1");
        long exactAuthorization = AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                "authorization", "auth-origin-v1");
        HrisExplorerRepository.ReceiptAuthority stored =
                new HrisExplorerRepository.ReceiptAuthority(
                        SUBJECT, "configuration.version.publish", "b".repeat(64),
                        HrisExplorerAuthoritySeals.populationScopeDigest(
                                ORIGIN_DERIVED_SCOPE),
                        HrisExplorerAuthoritySeals.publicRevision(exactField), exactField,
                        AuthHrisExplorerAuthorityEvidenceQuery.PLATFORM_ORIGIN_PURPOSE,
                        HrisExplorerAuthoritySeals.publicRevision(exactAuthorization),
                        exactAuthorization, ORIGIN_ROUTE, ORIGIN_CONTEXT,
                        ORIGIN_DERIVED_SCOPE, ORIGIN_DECISION);

        Optional<HrisPlatformReceiptAuthoritySource.CurrentReceiptAuthority> resolved =
                query.resolveAfterExplorerForwarded(
                        dualReceiptRequest(), HrisExplorerTestFixtures.snapshot(),
                        UUID.fromString("00000000-0000-4000-8000-000000000942"),
                        stored);

        assertThat(resolved).isPresent().get().satisfies(value -> {
            assertThat(value.platformReceiptContextKey())
                    .isEqualTo(PLATFORM_RECEIPT_CONTEXT)
                    .isNotEqualTo(CONTEXT);
            assertThat(value.platformReceiptContextScopeKey())
                    .isEqualTo(PLATFORM_RECEIPT_SCOPE)
                    .isNotEqualTo(SCOPE);
            assertThat(value.platformReceiptDecisionRevision())
                    .isEqualTo(PLATFORM_RECEIPT_DECISION)
                    .isNotEqualTo(DECISION);
            assertThat(value.originContextKey()).isEqualTo(ORIGIN_CONTEXT);
            assertThat(value.originContextScopeKey()).isEqualTo(
                    ORIGIN_DERIVED_SCOPE);
            assertThat(value.originDecisionRevision()).isEqualTo(ORIGIN_DECISION);
            assertThat(value.fieldPolicyRevision()).isEqualTo(exactField);
            assertThat(value.authorizationRevision()).isEqualTo(exactAuthorization);
        });
        authServer.verify();
        peopleServer.verify();
    }

    @Test
    void controlledTransferReceiptIntersectsCurrentReaderAndCurrentOriginAuthority() {
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(AUTH_URL);
        RestClient.Builder peopleBuilder = RestClient.builder().baseUrl(PEOPLE_URL);
        MockRestServiceServer authServer =
                MockRestServiceServer.bindTo(authBuilder).build();
        MockRestServiceServer peopleServer =
                MockRestServiceServer.bindTo(peopleBuilder).build();
        AuthHrisExplorerAuthorityEvidenceQuery query = query(
                authBuilder.build(), peopleBuilder.build());
        expectIdentity(authServer);
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey").value(
                        AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE))
                .andRespond(withSuccess(
                        allowedPlatformReceipt(), MediaType.APPLICATION_JSON));
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey").value(
                        CONTROLLED_TRANSFER_ROUTE))
                .andRespond(withSuccess(
                        allowedControlledTransferOrigin(),
                        MediaType.APPLICATION_JSON));
        peopleServer.expect(requestTo(PEOPLE_URL
                        + "/internal/people/v1/product-surface-eligibility/evaluate"))
                .andRespond(withSuccess(
                        allowedManagementPeople(), MediaType.APPLICATION_JSON));

        Optional<HrisPlatformReceiptAuthoritySource.CurrentReceiptAuthority> resolved =
                query.resolve(
                        platformReceiptRequest(),
                        UUID.fromString("00000000-0000-4000-8000-000000000943"),
                        controlledTransferStored());

        assertThat(resolved).isPresent().get().satisfies(value -> {
            assertThat(value.originatingAction())
                    .isEqualTo("CONTROLLED_TRANSFER_CREATE");
            assertThat(value.purposeCode()).isEqualTo("HRIS_INTEGRATION_ADMIN");
            assertThat(value.originRouteContractKey())
                    .isEqualTo(CONTROLLED_TRANSFER_ROUTE);
            assertThat(value.freshUntil()).isEqualTo(NOW.plusMinutes(5));
        });
        authServer.verify();
        peopleServer.verify();
    }

    @Test
    void controlledTransferReceiptFailsClosedWhenOriginIsRevoked() {
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(AUTH_URL);
        RestClient.Builder peopleBuilder = RestClient.builder().baseUrl(PEOPLE_URL);
        MockRestServiceServer authServer =
                MockRestServiceServer.bindTo(authBuilder).build();
        MockRestServiceServer peopleServer =
                MockRestServiceServer.bindTo(peopleBuilder).build();
        AuthHrisExplorerAuthorityEvidenceQuery query = query(
                authBuilder.build(), peopleBuilder.build());
        expectIdentity(authServer);
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey").value(
                        AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE))
                .andRespond(withSuccess(
                        allowedPlatformReceipt(), MediaType.APPLICATION_JSON));
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey").value(
                        CONTROLLED_TRANSFER_ROUTE))
                .andRespond(withSuccess(
                        denied("auth-origin-v1", "policy-origin-v1"),
                        MediaType.APPLICATION_JSON));

        Optional<HrisPlatformReceiptAuthoritySource.CurrentReceiptAuthority> resolved =
                query.resolve(
                        platformReceiptRequest(),
                        UUID.fromString("00000000-0000-4000-8000-000000000944"),
                        controlledTransferStored());

        assertThat(resolved).isEmpty();
        authServer.verify();
        peopleServer.verify();
    }

    @Test
    void payrollAliasIntersectionExactlyRebindsGrantsAndNormalizesReadOnly() {
        AuthHrisExplorerAuthorityEvidenceQuery query = query(
                RestClient.create(), RestClient.create());
        AuthHrisExplorerAuthorityEvidenceQuery.AuthorityResult active =
                payrollAuthority(true);

        AuthHrisExplorerAuthorityEvidenceQuery.Intersection intersection =
                query.intersect(active, payrollEligibility(), PAY_DERIVED_SCOPE);

        assertThat(intersection.grants()).containsExactly(
                active.effectiveGrants().get(0).withScopeKeys(
                        List.of(PAY_DERIVED_SCOPE)),
                active.effectiveGrants().get(1).withScopeKeys(
                        List.of(PAY_DERIVED_SCOPE)));
        assertThat(intersection.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.key()).isEqualTo(PAY_DERIVED_SCOPE);
            assertThat(scope.kind()).isEqualTo("TARGET_POPULATION");
            assertThat(scope.displayName()).isEqualTo("Payroll population");
            assertThat(scope.isDefault()).isTrue();
            assertThat(scope.readOnly()).isFalse();
            assertThat(scope.validUntil()).isEqualTo(
                    java.time.OffsetDateTime.parse("2026-10-15T06:00:00Z"));
        });
        assertThat(intersection.selectedScope().key()).isEqualTo(PAY_DERIVED_SCOPE);

        AuthHrisExplorerAuthorityEvidenceQuery.Intersection inactive =
                query.intersect(payrollAuthority(false), payrollEligibility(),
                        PAY_DERIVED_SCOPE);
        assertThat(inactive.scopes()).singleElement().satisfies(scope ->
                assertThat(scope.readOnly()).isTrue());
    }

    @Test
    void identitySuccessorRejectsUnknownDuplicateAndTrailingJson() {
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(AUTH_URL);
        RestClient.Builder peopleBuilder = RestClient.builder().baseUrl(PEOPLE_URL);
        MockRestServiceServer authServer =
                MockRestServiceServer.bindTo(authBuilder).build();
        MockRestServiceServer.bindTo(peopleBuilder).build();
        AuthHrisExplorerAuthorityEvidenceQuery query = query(
                authBuilder.build(), peopleBuilder.build());
        List<String> invalidResponses = List.of(
                identity().replace(
                        "\"identityPlane\": \"TENANT\"",
                        "\"identityPlane\": \"TENANT\", \"unexpected\": true"),
                identity().replace(
                        "\"tenantId\": 42,",
                        "\"tenantId\": 42, \"tenantId\": 42,"),
                identity() + " {}");
        for (String invalid : invalidResponses) {
            authServer.expect(requestTo(AUTH_URL
                            + "/internal/identity/v1/tenants/42/users/84"))
                    .andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));
        }
        for (String ignored : invalidResponses) {
            assertThatThrownBy(() -> query.resolve(request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "Identity subject response is not a closed contract");
        }
        authServer.verify();
    }

    @Test
    void authSuccessorRejectsUnknownDuplicateAndTrailingJson() {
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(AUTH_URL);
        RestClient.Builder peopleBuilder = RestClient.builder().baseUrl(PEOPLE_URL);
        MockRestServiceServer authServer =
                MockRestServiceServer.bindTo(authBuilder).build();
        MockRestServiceServer.bindTo(peopleBuilder).build();
        AuthHrisExplorerAuthorityEvidenceQuery query = query(
                authBuilder.build(), peopleBuilder.build());
        String denied = denied("auth-v1", "policy-v1");
        List<String> invalidResponses = List.of(
                denied.replace(
                        "\"policyRevision\": \"policy-v1\"",
                        "\"policyRevision\": \"policy-v1\", \"unexpected\": true"),
                denied.replace(
                        "\"decision\": \"ROUTE_DENIED\",",
                        "\"decision\": \"ROUTE_DENIED\", "
                                + "\"decision\": \"ROUTE_DENIED\","),
                denied + " {}");
        for (String invalid : invalidResponses) {
            expectIdentity(authServer);
            authServer.expect(requestTo(AUTH_URL
                            + "/internal/auth/v1/product-surface-authority/evaluate"))
                    .andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));
        }
        for (String ignored : invalidResponses) {
            assertThatThrownBy(() -> query.resolve(request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "Auth authority response is not a closed contract");
        }
        authServer.verify();
    }

    @Test
    void peopleSuccessorRejectsUnknownDuplicateAndTrailingJson() {
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(AUTH_URL);
        RestClient.Builder peopleBuilder = RestClient.builder().baseUrl(PEOPLE_URL);
        MockRestServiceServer authServer =
                MockRestServiceServer.bindTo(authBuilder).build();
        MockRestServiceServer peopleServer =
                MockRestServiceServer.bindTo(peopleBuilder).build();
        AuthHrisExplorerAuthorityEvidenceQuery query = query(
                authBuilder.build(), peopleBuilder.build());
        String allowed = allowedPeople();
        List<String> invalidResponses = List.of(
                allowed.replace(
                        "\"decision\": \"ALLOWED\"",
                        "\"decision\": \"ALLOWED\", \"unexpected\": true"),
                allowed.replace(
                        "\"decision\": \"ALLOWED\",",
                        "\"decision\": \"ALLOWED\", "
                                + "\"decision\": \"ALLOWED\","),
                allowed + " {}");
        for (String invalid : invalidResponses) {
            expectIdentity(authServer);
            authServer.expect(requestTo(AUTH_URL
                            + "/internal/auth/v1/product-surface-authority/evaluate"))
                    .andRespond(withSuccess(allowedPage("auth-v1", "policy-v1"),
                            MediaType.APPLICATION_JSON));
            peopleServer.expect(requestTo(PEOPLE_URL
                            + "/internal/people/v1/product-surface-eligibility/evaluate"))
                    .andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));
        }
        for (String ignored : invalidResponses) {
            assertThatThrownBy(() -> query.resolve(request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(
                            "People eligibility response is not a closed contract");
        }
        authServer.verify();
        peopleServer.verify();
    }

    @Test
    void explorerReceiptOriginReproofMatchesGatewayAliasIntersection() {
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(AUTH_URL);
        RestClient.Builder peopleBuilder = RestClient.builder().baseUrl(PEOPLE_URL);
        MockRestServiceServer authServer =
                MockRestServiceServer.bindTo(authBuilder).build();
        MockRestServiceServer peopleServer =
                MockRestServiceServer.bindTo(peopleBuilder).build();
        AuthHrisExplorerAuthorityEvidenceQuery query = query(
                authBuilder.build(), peopleBuilder.build());
        expectIdentity(authServer);
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey").value(
                        "route.hcm.utility.explorer-recent-create.action"))
                .andRespond(withSuccess(
                        allowedExplorerOriginAliases(), MediaType.APPLICATION_JSON));
        peopleServer.expect(requestTo(PEOPLE_URL
                        + "/internal/people/v1/product-surface-eligibility/evaluate"))
                .andRespond(withSuccess(
                        allowedPeopleAliases(), MediaType.APPLICATION_JSON));
        String originDecision = AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                42, 84, "NORMAL", "auth-v1", "policy-v1",
                RELATIONSHIP_REVISION, POPULATION_REVISION, "hcm.utility",
                "route.hcm.utility.explorer-recent-create.action",
                "route-grant:explorer-recent");
        HrisExplorerRepository.ReceiptAuthority stored =
                new HrisExplorerRepository.ReceiptAuthority(
                        SUBJECT, JdbcHrisExplorerRepository.RECENT_ACTION,
                        "b".repeat(64),
                        HrisExplorerAuthoritySeals.populationScopeDigest(
                                DERIVED_SCOPE),
                        HrisExplorerAuthoritySeals.publicRevision(
                                AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                                        "field-policy", "policy-v1")),
                        AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                                "field-policy", "policy-v1"),
                        AuthHrisExplorerAuthorityEvidenceQuery.EXPLORER_PURPOSE,
                        HrisExplorerAuthoritySeals.publicRevision(
                                AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                                        "authorization", "auth-v1")),
                        AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                                "authorization", "auth-v1"),
                        "route.hcm.utility.explorer-recent-create.action",
                        CONTEXT, DERIVED_SCOPE, originDecision);

        Optional<HrisExplorerReceiptAuthoritySource.CurrentExplorerReceiptAuthority>
                current = query.resolveExplorerOrigin(
                receiptRequest(), UUID.fromString(
                        "00000000-0000-4000-8000-000000000941"), stored);

        assertThat(current).isPresent().get().satisfies(value -> {
            assertThat(value.authorityPopulationScopeDigest())
                    .isEqualTo(stored.authorityPopulationScopeDigest());
            assertThat(value.originDecisionRevision()).isEqualTo(originDecision);
            assertThat(value.freshUntil())
                    .isEqualTo(java.time.OffsetDateTime.parse(
                            "2026-10-15T04:45:00Z"));
        });
        authServer.verify();
        peopleServer.verify();
    }

    @Test
    void rejectsAuthorityRevisionChangeCarriedByDeniedDescendantMidScan() {
        RestClient.Builder authBuilder = RestClient.builder().baseUrl(AUTH_URL);
        RestClient.Builder peopleBuilder = RestClient.builder().baseUrl(PEOPLE_URL);
        MockRestServiceServer authServer =
                MockRestServiceServer.bindTo(authBuilder).build();
        MockRestServiceServer peopleServer =
                MockRestServiceServer.bindTo(peopleBuilder).build();
        AuthHrisExplorerAuthorityEvidenceQuery query = query(
                authBuilder.build(), peopleBuilder.build());

        expectIdentity(authServer);
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey")
                        .value(AuthHrisExplorerAuthorityEvidenceQuery.EXPLORER_PAGE_ROUTE))
                .andRespond(withSuccess(allowedPage("auth-v1", "policy-v1"),
                        MediaType.APPLICATION_JSON));
        peopleServer.expect(requestTo(PEOPLE_URL
                        + "/internal/people/v1/product-surface-eligibility/evaluate"))
                .andRespond(withSuccess(allowedPeople(), MediaType.APPLICATION_JSON));

        List<String> descendants = HrisExplorerV43RouteAdapter.deployedRouteKeys()
                .stream().sorted().toList();
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey").value(
                        HrisExplorerV43RouteAdapter.routeSpec(descendants.get(0))
                                .authorization().routeContractKey()))
                .andRespond(withSuccess(denied("auth-v1", "policy-v1"),
                        MediaType.APPLICATION_JSON));
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/auth/v1/product-surface-authority/evaluate"))
                .andExpect(jsonPath("$.routeContractKey").value(
                        HrisExplorerV43RouteAdapter.routeSpec(descendants.get(1))
                                .authorization().routeContractKey()))
                .andRespond(withSuccess(denied("auth-v2", "policy-v2"),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> query.resolve(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("descendant authority is unavailable or changed");
        authServer.verify();
        peopleServer.verify();
    }

    private static void expectIdentity(MockRestServiceServer authServer) {
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/identity/v1/tenants/42/users/84"))
                .andRespond(withSuccess(identity(), MediaType.APPLICATION_JSON));
    }

    private static String identity() {
        return """
                {
                  "tenantId": 42,
                  "userId": 84,
                  "personPublicId": "%s",
                  "status": "ACTIVE",
                  "identityPlane": "TENANT"
                }
                """.formatted(SUBJECT);
    }

    private static AuthHrisExplorerAuthorityEvidenceQuery.AuthorityResult
            payrollAuthority(boolean activeMutation) {
        java.time.OffsetDateTime readExpiry = java.time.OffsetDateTime.parse(
                "2026-10-15T06:00:01Z");
        java.time.OffsetDateTime mutationExpiry = java.time.OffsetDateTime.parse(
                "2026-10-15T06:00:00Z");
        AuthHrisExplorerAuthorityEvidenceQuery.EffectiveGrant read =
                new AuthHrisExplorerAuthorityEvidenceQuery.EffectiveGrant(
                        "CAPABILITY", "hcm.payroll-foundation.view",
                        "HCM_PAYROLL_FOUNDATION_VIEW", "payroll-read-policy",
                        "decision:payroll-read", "PERMISSION_ONLY", List.of(),
                        null, null, List.of(PAY_SCOPE), true, true, "ACTIVE",
                        readExpiry);
        AuthHrisExplorerAuthorityEvidenceQuery.EffectiveGrant mutation =
                new AuthHrisExplorerAuthorityEvidenceQuery.EffectiveGrant(
                        "CAPABILITY", "hcm.payroll-foundation.manage",
                        "HCM_PAYROLL_FOUNDATION_MANAGE", "payroll-manage-policy",
                        "decision:payroll-manage", "PERMISSION_AND_RELATIONSHIP",
                        List.of("predicate:payroll"), "PAYROLL_ADMIN", null,
                        List.of(PAYROLL_LEGAL_ENTITY_SCOPE), true, false,
                        activeMutation ? "ACTIVE" : "INACTIVE", mutationExpiry);
        return new AuthHrisExplorerAuthorityEvidenceQuery.AuthorityResult(
                "ALLOWED", null, "auth-payroll-v1", "policy-payroll-v1",
                CONTEXT, "hcm", "hcm.operations", "operations", "NORMAL",
                "ASSIGNMENT", "hcm.payroll-foundation", List.of(read, mutation),
                List.of(
                        new AuthHrisExplorerAuthorityEvidenceQuery.EffectiveScope(
                                PAY_SCOPE, "TARGET_POPULATION",
                                "Payroll population", true, true, readExpiry),
                        new AuthHrisExplorerAuthorityEvidenceQuery.EffectiveScope(
                                PAYROLL_LEGAL_ENTITY_SCOPE, "TARGET_POPULATION",
                                "Payroll population", true, false,
                                mutationExpiry)),
                "route-grant:payroll-foundation", false, true, mutationExpiry,
                null, null, null, NOW.plusMinutes(5), "auth-evidence-payroll");
    }

    private static AuthHrisExplorerAuthorityEvidenceQuery.EligibilityResult
            payrollEligibility() {
        return new AuthHrisExplorerAuthorityEvidenceQuery.EligibilityResult(
                "ALLOWED", null, "relationship-payroll-v1",
                "population-payroll-v1", List.of(
                new AuthHrisExplorerAuthorityEvidenceQuery.EligibleScope(
                        PAY_SCOPE, PAY_DERIVED_SCOPE, "TARGET_POPULATION",
                        "Payroll population", true, false,
                        java.time.OffsetDateTime.parse("2026-10-15T06:00:01Z")),
                new AuthHrisExplorerAuthorityEvidenceQuery.EligibleScope(
                        PAYROLL_LEGAL_ENTITY_SCOPE, PAY_DERIVED_SCOPE,
                        "TARGET_POPULATION", "Payroll population", true, false,
                        java.time.OffsetDateTime.parse("2026-10-15T06:00:00Z"))),
                NOW.plusMinutes(5), "people-evidence-payroll");
    }

    private static AuthorityRequest request() {
        String decision = AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                42, 84, "NORMAL", "auth-v1", "policy-v1",
                RELATIONSHIP_REVISION, POPULATION_REVISION, "hcm.utility",
                AuthHrisExplorerAuthorityEvidenceQuery.EXPLORER_PAGE_ROUTE,
                "route-grant:explorer-page");
        return new AuthorityRequest(
                42, 84, SUBJECT, "NORMAL", decision,
                AuthHrisExplorerAuthorityEvidenceQuery.EXPLORER_PAGE_ROUTE,
                CONTEXT, DERIVED_SCOPE,
                AuthHrisExplorerAuthorityEvidenceQuery.EXPLORER_PURPOSE,
                NOW.plusMinutes(5));
    }

    private static AuthorityRequest receiptRequest() {
        return new AuthorityRequest(
                42, 84, SUBJECT, "NORMAL", "psr-" + "7".repeat(64),
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE,
                CONTEXT, DERIVED_SCOPE,
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_PURPOSE,
                NOW.plusMinutes(5));
    }

    private static AuthorityRequest platformReceiptRequest() {
        return new AuthorityRequest(
                42, 84, SUBJECT, "NORMAL", PLATFORM_RECEIPT_DECISION,
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE,
                PLATFORM_RECEIPT_CONTEXT, PLATFORM_RECEIPT_SCOPE,
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_PURPOSE,
                NOW.plusMinutes(5));
    }

    private static AuthorityRequest dualReceiptRequest() {
        return new AuthorityRequest(
                42, 84, SUBJECT, "NORMAL", DECISION,
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE,
                CONTEXT, SCOPE,
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_PURPOSE,
                NOW.plusMinutes(5));
    }

    private static String allowedPlatformReceipt() {
        return """
                {
                  "decision": "ALLOWED",
                  "authRevision": "auth-platform-receipt-v1",
                  "policyRevision": "policy-platform-receipt-v1",
                  "contextKey": "%s",
                  "productKey": "hcm",
                  "surfaceKey": "hcm.utility",
                  "accessMode": "NORMAL",
                  "effectiveGrants": [{
                    "grantKind": "CAPABILITY",
                    "capabilityContractKey": "hcm.command-receipt.view",
                    "resolvedCapabilityCode": "DATA.HRIS_COMMAND_RECEIPT:VIEW",
                    "authorityMode": "PERMISSION_AND_RELATIONSHIP",
                    "scopeKeys": ["%s"],
                    "requiresProductEntitlement": false,
                    "readOnly": true,
                    "activationState": "ACTIVE"
                  }],
                  "scopes": [{
                    "key": "%s",
                    "kind": "TARGET_POPULATION",
                    "displayName": "Originating command population",
                    "isDefault": true,
                    "readOnly": true,
                    "validUntil": "2026-10-15T05:00:00Z"
                  }],
                  "routeGrantRef": "route-grant:platform-command-receipt",
                  "effectiveReadOnly": true,
                  "requiresProductEligibility": false,
                  "revalidateAt": "2026-10-15T04:10:00Z"
                }
                """.formatted(
                PLATFORM_RECEIPT_CONTEXT, PLATFORM_RECEIPT_SCOPE,
                PLATFORM_RECEIPT_SCOPE);
    }

    private static String allowedManagementOrigin() {
        return """
                {
                  "decision": "ALLOWED",
                  "authRevision": "auth-origin-v1",
                  "policyRevision": "policy-origin-v1",
                  "contextKey": "%s",
                  "productKey": "hcm",
                  "surfaceKey": "hcm.management",
                  "accessMode": "NORMAL",
                  "effectiveGrants": [{
                    "grantKind": "CAPABILITY",
                    "capabilityContractKey": "hcm.reference-configuration.manage",
                    "resolvedCapabilityCode": "CONFIG.HRIS_POLICY:UPDATE",
                    "authorityMode": "PERMISSION_AND_RELATIONSHIP",
                    "scopeKeys": ["%s"],
                    "requiresProductEntitlement": true,
                    "readOnly": false,
                    "activationState": "ACTIVE"
                  }],
                  "scopes": [{
                    "key": "%s",
                    "kind": "RESOURCE_SET",
                    "displayName": "HCM configuration",
                    "isDefault": true,
                    "readOnly": false,
                    "validUntil": "2026-10-15T05:00:00Z"
                  }],
                  "routeGrantRef": "route-grant:configuration-publish",
                  "effectiveReadOnly": false,
                  "requiresProductEligibility": true,
                  "revalidateAt": "2026-10-15T04:20:00Z"
                }
                """.formatted(ORIGIN_CONTEXT, ORIGIN_RAW_SCOPE, ORIGIN_RAW_SCOPE);
    }

    private static String allowedControlledTransferOrigin() {
        return allowedManagementOrigin()
                .replace("hcm.reference-configuration.manage",
                        "hcm.integration.transfer.create")
                .replace("CONFIG.HRIS_POLICY:UPDATE",
                        "ACTION.HRIS_CONTROLLED_TRANSFER:CREATE")
                .replace("route-grant:configuration-publish",
                        "route-grant:controlled-transfer-create");
    }

    private static HrisExplorerRepository.ReceiptAuthority
            controlledTransferStored() {
        long exactField = AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                "field-policy", "policy-origin-v1");
        long exactAuthorization =
                AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                        "authorization", "auth-origin-v1");
        String originDecision =
                AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                        42, 84, "NORMAL", "auth-origin-v1", "policy-origin-v1",
                        RELATIONSHIP_REVISION, POPULATION_REVISION, "hcm.management",
                        CONTROLLED_TRANSFER_ROUTE,
                        "route-grant:controlled-transfer-create");
        return new HrisExplorerRepository.ReceiptAuthority(
                SUBJECT, "CONTROLLED_TRANSFER_CREATE", "b".repeat(64),
                HrisExplorerAuthoritySeals.populationScopeDigest(
                        ORIGIN_DERIVED_SCOPE),
                HrisExplorerAuthoritySeals.publicRevision(exactField), exactField,
                "HRIS_INTEGRATION_ADMIN",
                HrisExplorerAuthoritySeals.publicRevision(exactAuthorization),
                exactAuthorization, CONTROLLED_TRANSFER_ROUTE, ORIGIN_CONTEXT,
                ORIGIN_DERIVED_SCOPE, originDecision);
    }

    private static String allowedManagementPeople() {
        return """
                {
                  "decision": "ALLOWED",
                  "productRelationshipRevision": "%s",
                  "targetPopulationRevision": "%s",
                  "scopes": [{
                    "sourceScopeKey": "%s",
                    "key": "%s",
                    "kind": "RESOURCE_SET",
                    "displayName": "HCM configuration",
                    "isDefault": true,
                    "readOnly": false,
                    "validUntil": "2026-10-15T04:50:00Z"
                  }],
                  "revalidateAt": "2026-10-15T04:15:00Z"
                }
                """.formatted(
                RELATIONSHIP_REVISION, POPULATION_REVISION,
                ORIGIN_RAW_SCOPE, ORIGIN_DERIVED_SCOPE);
    }

    private static String allowedPage(String authRevision, String policyRevision) {
        return """
                {
                  "decision": "ALLOWED",
                  "authRevision": "%s",
                  "policyRevision": "%s",
                  "contextKey": "%s",
                  "productKey": "hcm",
                  "surfaceKey": "hcm.utility",
                  "accessMode": "NORMAL",
                  "effectiveGrants": [{
                    "grantKind": "CAPABILITY",
                    "capabilityContractKey": "hcm.app.shell.view",
                    "resolvedCapabilityCode": "HCM_APP_SHELL_VIEW",
                    "scopeKeys": ["%s"],
                    "readOnly": true
                  }],
                  "scopes": [{
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": true,
                    "readOnly": true,
                    "validUntil": "2026-10-15T05:00:00Z"
                  }],
                  "routeGrantRef": "route-grant:explorer-page",
                  "effectiveReadOnly": true,
                  "requiresProductEligibility": true,
                  "revalidateAt": "2026-10-15T04:10:00Z"
                }
                """.formatted(
                authRevision, policyRevision, CONTEXT, RAW_SCOPE, RAW_SCOPE);
    }

    private static String allowedPeople() {
        return """
                {
                  "decision": "ALLOWED",
                  "productRelationshipRevision": "%s",
                  "targetPopulationRevision": "%s",
                  "scopes": [{
                    "sourceScopeKey": "%s",
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": true,
                    "readOnly": true,
                    "validUntil": "2026-10-15T05:00:00Z"
                  }],
                  "revalidateAt": "2026-10-15T04:10:00Z"
                }
                """.formatted(
                RELATIONSHIP_REVISION, POPULATION_REVISION,
                RAW_SCOPE, DERIVED_SCOPE);
    }

    private static String allowedExplorerOriginAliases() {
        return """
                {
                  "decision": "ALLOWED",
                  "authRevision": "auth-v1",
                  "policyRevision": "policy-v1",
                  "contextKey": "%s",
                  "productKey": "hcm",
                  "surfaceKey": "hcm.utility",
                  "accessMode": "NORMAL",
                  "effectiveGrants": [{
                    "grantKind": "CAPABILITY",
                    "capabilityContractKey": "hcm.app.shell.view",
                    "resolvedCapabilityCode": "HCM_APP_SHELL_VIEW",
                    "authorityMode": "PERMISSION_AND_RELATIONSHIP",
                    "scopeKeys": ["%s", "%s"],
                    "requiresProductEntitlement": true,
                    "readOnly": false,
                    "activationState": "ACTIVE"
                  }],
                  "scopes": [{
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": true,
                    "readOnly": false,
                    "validUntil": "2026-10-15T05:00:00Z"
                  }, {
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": false,
                    "readOnly": true,
                    "validUntil": "2026-10-15T04:50:00Z"
                  }],
                  "routeGrantRef": "route-grant:explorer-recent",
                  "effectiveReadOnly": false,
                  "requiresProductEligibility": true,
                  "revalidateAt": "2026-10-15T04:55:00Z"
                }
                """.formatted(
                CONTEXT, RAW_SCOPE, RAW_SCOPE_ALIAS,
                RAW_SCOPE, RAW_SCOPE_ALIAS);
    }

    private static String allowedPeopleAliases() {
        return """
                {
                  "decision": "ALLOWED",
                  "productRelationshipRevision": "%s",
                  "targetPopulationRevision": "%s",
                  "scopes": [{
                    "sourceScopeKey": "%s",
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": true,
                    "readOnly": false,
                    "validUntil": "2026-10-15T04:45:00Z"
                  }, {
                    "sourceScopeKey": "%s",
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": true,
                    "readOnly": false,
                    "validUntil": "2026-10-15T04:50:00Z"
                  }],
                  "revalidateAt": "2026-10-15T04:55:00Z"
                }
                """.formatted(
                RELATIONSHIP_REVISION, POPULATION_REVISION,
                RAW_SCOPE, DERIVED_SCOPE, RAW_SCOPE_ALIAS, DERIVED_SCOPE);
    }

    private static AuthHrisExplorerAuthorityEvidenceQuery query(
            RestClient auth, RestClient people) {
        return new AuthHrisExplorerAuthorityEvidenceQuery(
                auth, people, "product-token", "identity-token",
                "people-token",
                Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"),
                        ZoneOffset.UTC),
                new ObjectMapper().findAndRegisterModules()
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    private static String denied(String authRevision, String policyRevision) {
        return """
                {
                  "decision": "ROUTE_DENIED",
                  "authRevision": "%s",
                  "policyRevision": "%s"
                }
                """.formatted(authRevision, policyRevision);
    }
}
