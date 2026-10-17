package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
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
                .withUserConfiguration(TimeCloseService.class)
                .withPropertyValues("dwp.hris.time.close.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TimeCloseService.class);
                    assertThat(context).hasSingleBean(TimeCloseReceiptOwnership.class);
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
                    verifyNoInteractions(jdbc, transactions);
                });
    }
}
