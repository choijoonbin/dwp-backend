package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class TimeCloseFeatureIsolationTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void populationProjectionResolverBindsCurrentUtcEffectiveWindow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(status);
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(Long.toString(TimeCloseTestFixtures.TENANT));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        var resolver = new JdbcTimeClosePopulationProjectionResolver(jdbc, transactions);

        assertThat(resolver.resolve(
                TimeCloseTestFixtures.TENANT, 72L, "scope-key",
                TimeCloseTestFixtures.NOW)).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), arguments.capture());
        assertThat(sql.getValue())
                .contains("p.effective_from <= ?")
                .contains("p.effective_to IS NULL OR p.effective_to > ?");
        assertThat(arguments.getValue())
                .endsWith(LocalDate.parse("2026-10-17"), LocalDate.parse("2026-10-17"));
    }

    @Test
    void closeRuntimeIsAbsentWhenFeaturePropertyIsMissingOrFalse() {
        assertRuntimeAbsent(new String[0]);
        assertRuntimeAbsent("dwp.hris.time.close.enabled=false");
    }

    @Test
    void enabledCloseReceiptProviderBootsWithoutAnyTimecardBean() {
        TimeCloseRepository repository = mock(TimeCloseRepository.class);
        TimeCloseEvidenceSource evidence = mock(TimeCloseEvidenceSource.class);
        TimeCloseApprovalVerifier approval = mock(TimeCloseApprovalVerifier.class);
        TimeCloseStepUpVerifier stepUp = mock(TimeCloseStepUpVerifier.class);
        TimeCloseActivationGate activation = new ConfiguredTimeCloseActivationGate("");

        new ApplicationContextRunner()
                .withBean(TimeCloseRepository.class, () -> repository)
                .withBean(TimeCloseEvidenceSource.class, () -> evidence)
                .withBean(TimeCloseApprovalVerifier.class, () -> approval)
                .withBean(TimeCloseStepUpVerifier.class, () -> stepUp)
                .withBean(TimeCloseActivationGate.class, () -> activation)
                .withUserConfiguration(
                        TimeCloseService.class,
                        TimeCloseCommandReceiptProvider.class)
                .withPropertyValues("dwp.hris.time.close.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TimeCloseService.class);
                    assertThat(context).hasSingleBean(TimeCloseReceiptOwnership.class);
                    assertThat(context).hasSingleBean(TimeCloseCommandReceiptProvider.class);
                    assertThat(Arrays.stream(context.getBeanDefinitionNames())
                            .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT)
                                    .contains("timecard")))
                            .isTrue();
                    TimeCloseReceiptOwnership ownership =
                            context.getBean(TimeCloseReceiptOwnership.class);
                    assertThat(ownership.owns(
                            TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RECEIPT))
                            .isFalse();
                    assertThat(activation.allows(TimeCloseTestFixtures.TENANT)).isFalse();
                    verifyNoInteractions(repository, evidence, approval, stepUp);
                });
    }

    @Test
    void unenrolledTenantStopsAtOwnerPepBeforePopulationOrControllerCalls()
            throws Exception {
        TimeClosePopulationProjectionResolver projections =
                mock(TimeClosePopulationProjectionResolver.class);
        GatewayVerifiedTimeCloseOwnerAuthoritySource authority =
                new GatewayVerifiedTimeCloseOwnerAuthoritySource(
                        "trusted-gateway", true, Set.of(),
                        Clock.fixed(TimeCloseTestFixtures.NOW, ZoneOffset.UTC), projections);
        TimeCloseOwnerAccessFilter filter = new TimeCloseOwnerAccessFilter(
                authority, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(TimeCloseTestFixtures.NOW, ZoneOffset.UTC));
        FilterChain downstream = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/close-periods/" + TimeCloseTestFixtures.PERIOD
                        + "/validations");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.SERVICE_TOKEN,
                "trusted-gateway");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.TENANT,
                Long.toString(TimeCloseTestFixtures.TENANT));
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.ACTOR, "72");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, downstream);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("AUTHORIZATION_DENIED");
        verifyNoInteractions(projections, downstream);
    }

    @Test
    void untrustedResponsibilityAndScopeHeadersAloneNeverCreateAuthority() {
        TimeClosePopulationProjectionResolver projections =
                mock(TimeClosePopulationProjectionResolver.class);
        GatewayVerifiedTimeCloseOwnerAuthoritySource authority =
                new GatewayVerifiedTimeCloseOwnerAuthoritySource(
                        "trusted-gateway", true, Set.of(TimeCloseTestFixtures.TENANT),
                        Clock.fixed(TimeCloseTestFixtures.NOW, ZoneOffset.UTC), projections);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/close-periods/" + TimeCloseTestFixtures.PERIOD + "/close");
        request.addHeader(
                GatewayVerifiedTimeCloseOwnerAuthoritySource.RESPONSIBILITIES,
                "HRIS_DUTY_TIME_CLOSE_OPERATOR");
        request.addHeader(
                GatewayVerifiedTimeCloseOwnerAuthoritySource.LEGAL_ENTITY,
                TimeCloseTestFixtures.LEGAL_ENTITY.toString());
        request.addHeader(
                GatewayVerifiedTimeCloseOwnerAuthoritySource.TIME_GROUP,
                TimeCloseTestFixtures.TIME_GROUP.toString());

        assertThatThrownBy(() -> authority.verify(request, TimeCloseOwnerRoute.CLOSE))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        verifyNoInteractions(projections);
    }

    @Test
    void trustedSharedReceiptDecisionProjectsIntoTheCloseDigestDomain() {
        String gatewayScope = "hcm-scope-" + "7".repeat(40);
        TimeClosePopulationProjectionResolver projections =
                mock(TimeClosePopulationProjectionResolver.class);
        when(projections.resolve(
                TimeCloseTestFixtures.TENANT, 72L, gatewayScope,
                TimeCloseTestFixtures.NOW)).thenReturn(Optional.of(
                        new TimeClosePopulationProjectionResolver.Projection(
                                TimeCloseTestFixtures.TENANT, 72L, gatewayScope,
                                TimeCloseTestFixtures.POPULATION, 9L, 11L,
                                TimeCloseTestFixtures.POPULATION_SOURCE_DIGEST,
                                TimeCloseTestFixtures.GRANT_SOURCE_DIGEST,
                                TimeCloseTestFixtures.NOW.plusSeconds(600))));
        GatewayVerifiedTimeCloseOwnerAuthoritySource authority =
                new GatewayVerifiedTimeCloseOwnerAuthoritySource(
                        "trusted-gateway", true, Set.of(TimeCloseTestFixtures.TENANT),
                        Clock.fixed(TimeCloseTestFixtures.NOW, ZoneOffset.UTC), projections);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/command-receipts/" + TimeCloseTestFixtures.RECEIPT);
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.SERVICE_TOKEN,
                "trusted-gateway");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.TENANT,
                Long.toString(TimeCloseTestFixtures.TENANT));
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.ACTOR, "72");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.SUBJECT,
                TimeCloseTestFixtures.SUBJECT.toString());
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.PERMISSIONS,
                "APP.HCM:VIEW,DATA.HRIS_COMMAND_RECEIPT:VIEW,"
                        + TimeCloseOwnerRoute.HANDOFF_CREATE.permission());
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.RESPONSIBILITIES,
                TimeCloseOwnerRoute.HANDOFF_CREATE.responsibility());
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.ROUTE,
                "route.hcm.time.command-receipt.data");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.CONTEXT,
                "psc-" + "2".repeat(64));
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.SCOPE, gatewayScope);
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.ACCESS_MODE, "NORMAL");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.CURRENT_REVISION,
                TimeCloseTestFixtures.DECISION_REVISION);
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.REVALIDATE_AT,
                TimeCloseTestFixtures.NOW.plusSeconds(300).toString());
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.CORRELATION,
                TimeCloseTestFixtures.CORRELATION.toString());
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.FIELD_POLICY_REVISION,
                "13");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.AUTHORIZATION_REVISION,
                "17");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.POPULATION_KIND,
                "TARGET_POPULATION");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.PURPOSE,
                "COMMAND_STATUS");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.LEGAL_ENTITY,
                TimeCloseTestFixtures.LEGAL_ENTITY.toString());
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.TIME_GROUP,
                TimeCloseTestFixtures.TIME_GROUP.toString());
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.ROLLOUT_STATE, "110");
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.ROLLOUT_REVISION,
                "rollout-" + "8".repeat(64));
        request.addHeader(GatewayVerifiedTimeCloseOwnerAuthoritySource.ROLLOUT_COHORT,
                "full");

        assertThat(authority.verifyReceipt(request)).hasValueSatisfying(verified -> {
            assertThat(verified.populationScopeDigest())
                    .isEqualTo(TimeCloseTestFixtures.SCOPE_DIGEST);
            assertThat(verified.responsibilities())
                    .containsExactly(TimeCloseOwnerRoute.HANDOFF_CREATE.responsibility());
            assertThat(verified.revalidateAt())
                    .isEqualTo(TimeCloseTestFixtures.NOW.plusSeconds(300));
        });
    }

    private static void assertRuntimeAbsent(String... properties) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class,
                        () -> new ObjectMapper().findAndRegisterModules())
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class, () -> transactions)
                .withUserConfiguration(
                        JdbcTimeCloseRepository.class,
                        JdbcTimeCloseEvidenceSource.class,
                        JdbcTimeClosePopulationProjectionResolver.class,
                        GatewayVerifiedTimeCloseOwnerAuthoritySource.class,
                        ConfiguredTimeCloseActivationGate.class,
                        UnavailableTimeCloseApprovalVerifier.class,
                        UnavailableTimeCloseStepUpVerifier.class,
                        TimeCloseService.class,
                        ClosedTimeResultService.class,
                        TimeCloseIaProjectionService.class,
                        TimeCloseCommandReceiptAuthorityProvider.class,
                        TimeCloseCommandReceiptProvider.class,
                        TimeCloseOwnerAccessFilter.class,
                        TimeCloseOwnerController.class,
                        TimeCloseOwnerProblemAdvice.class)
                .withPropertyValues(properties)
                .run(context -> {
                    clearInvocations(jdbc, transactions);
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(TimeCloseService.class);
                    assertThat(context).doesNotHaveBean(ClosedTimeResultService.class);
                    assertThat(context).doesNotHaveBean(TimeCloseRepository.class);
                    assertThat(context).doesNotHaveBean(TimeCloseEvidenceSource.class);
                    assertThat(context).doesNotHaveBean(TimeCloseOwnerController.class);
                    assertThat(context).doesNotHaveBean(TimeCloseOwnerAccessFilter.class);
                    assertThat(context).doesNotHaveBean(TimeCloseActivationGate.class);
                    assertThat(context).doesNotHaveBean(
                            TimeCloseCommandReceiptProvider.class);
                    assertThat(context).doesNotHaveBean(
                            TimeCloseCommandReceiptAuthorityProvider.class);
                    verifyNoInteractions(jdbc, transactions);
                });
    }
}
