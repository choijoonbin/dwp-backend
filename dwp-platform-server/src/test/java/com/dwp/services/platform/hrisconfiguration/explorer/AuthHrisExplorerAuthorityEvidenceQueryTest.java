package com.dwp.services.platform.hrisconfiguration.explorer;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

class AuthHrisExplorerAuthorityEvidenceQueryTest extends AuthHrisExplorerAuthorityFixture {

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
}
