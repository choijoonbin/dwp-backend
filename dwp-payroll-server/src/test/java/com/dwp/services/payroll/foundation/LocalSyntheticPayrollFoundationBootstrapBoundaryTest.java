package com.dwp.services.payroll.foundation;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LocalSyntheticPayrollFoundationBootstrapBoundaryTest {
    private static final String RUN_ID = "w1-20261002t010203z-0123abcd";
    private static final String TOKEN = "local-payroll-token-0123456789abcdef";

    @Test
    void boundaryBeansAreAbsentByDefault() {
        new ApplicationContextRunner()
                .withBean(PayrollFoundationService.class,
                        () -> mock(PayrollFoundationService.class))
                .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
                .withUserConfiguration(
                        LocalSyntheticPayrollFoundationBootstrapFilter.class,
                        LocalSyntheticPayrollFoundationBootstrapService.class,
                        LocalSyntheticPayrollFoundationBootstrapController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticPayrollFoundationBootstrapFilter.class);
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticPayrollFoundationBootstrapService.class);
                    assertThat(context).doesNotHaveBean(
                            LocalSyntheticPayrollFoundationBootstrapController.class);
                });
    }

    @Test
    void enabledBoundaryRejectsShortTokenDuringContextStartup() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class,
                        () -> new ObjectMapper().findAndRegisterModules())
                .withUserConfiguration(LocalSyntheticPayrollFoundationBootstrapFilter.class)
                .withPropertyValues(
                        "dwp.hris.payroll-foundation.synthetic-bootstrap.enabled=true",
                        "DWP_ENVIRONMENT=local",
                        "dwp.hris.payroll-foundation.synthetic-bootstrap.token=short")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledConfigurationRequiresLocalEnvironmentAndLongUnpaddedToken() {
        assertThatThrownBy(() ->
                LocalSyntheticPayrollFoundationBootstrapFilter.requireLocalConfiguration(
                        "production", TOKEN))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                LocalSyntheticPayrollFoundationBootstrapFilter.requireLocalConfiguration(
                        "local", "short"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                LocalSyntheticPayrollFoundationBootstrapFilter.requireLocalConfiguration(
                        "local", TOKEN + " "))
                .isInstanceOf(IllegalStateException.class);
        LocalSyntheticPayrollFoundationBootstrapFilter.requireLocalConfiguration("local", TOKEN);
    }

    @Test
    void transportAcceptsOnlyLoopbackAndExactPurposeToken() throws Exception {
        var filter = new LocalSyntheticPayrollFoundationBootstrapFilter(
                "local", TOKEN, new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest accepted = request("127.0.0.1", TOKEN);
        MockFilterChain acceptedChain = new MockFilterChain();
        filter.doFilter(accepted, new MockHttpServletResponse(), acceptedChain);
        assertThat(acceptedChain.getRequest()).isSameAs(accepted);

        for (MockHttpServletRequest rejected : List.of(
                request("203.0.113.10", TOKEN),
                request("127.0.0.1", "x".repeat(40)),
                request("GET", "127.0.0.1", TOKEN),
                duplicateTokenRequest())) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(rejected, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
        }
        assertThat(PayrollFoundationRoute.resolve(
                "POST", LocalSyntheticPayrollFoundationBootstrapFilter.PATH)).isEmpty();
    }

    @Test
    void ownerServiceCreatesSimulatesAndExposesPublishableSeparationOfDutiesState() {
        var payroll = new PayrollFoundationService(
                new PayrollFoundationTestSupport.InMemoryStore(),
                new PayrollFoundationTestSupport.MutableDependencies());
        var bootstrap = new LocalSyntheticPayrollFoundationBootstrapService(payroll, RUN_ID);
        UUID legalEntityId = UUID.fromString("10000000-0000-0000-0000-0000000000aa");
        var request = new LocalSyntheticPayrollFoundationBootstrapDtos.BootstrapRequest(
                RUN_ID, 41L, 9001L, legalEntityId);

        var response = bootstrap.bootstrap(request);

        assertThat(response.lifecycleState()).isEqualTo("SIMULATED");
        assertThat(response.dependencyFreshness()).isEqualTo("LIVE");
        assertThat(response.version()).isEqualTo(2L);
        assertThat(response.authorActorId()).isEqualTo(9001L);
        assertThat(response.receiptSha256()).matches("[0-9a-f]{64}");
        var administrator = PayrollFoundationAccess.actor(
                41L,
                1001L,
                "SYNTHETIC_TENANT_ADMIN",
                "APP.HRIS:VIEW,PAYROLL_FOUNDATION:VIEW,PAYROLL_FOUNDATION:PUBLISH",
                "PAYROLL_CONFIGURATION",
                legalEntityId.toString(),
                PayrollFoundationAccess.compatibilityPolicy());
        var view = payroll.get(administrator, response.configurationId());
        assertThat(view.authorId()).isNotEqualTo(administrator.actorId());
        assertThat(view.access().canPublish()).isTrue();
        assertThat(view.freshness().state())
                .isEqualTo(PayrollFoundationModels.FreshnessState.LIVE);
        assertThatThrownBy(() -> bootstrap.bootstrap(request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("fresh legal entity fixture");
    }

    @Test
    void rejectsRequestsBoundToAnotherSyntheticRun() {
        var payroll = new PayrollFoundationService(
                new PayrollFoundationTestSupport.InMemoryStore(),
                new PayrollFoundationTestSupport.MutableDependencies());
        var bootstrap = new LocalSyntheticPayrollFoundationBootstrapService(payroll, RUN_ID);
        var request = new LocalSyntheticPayrollFoundationBootstrapDtos.BootstrapRequest(
                "w1-20261002t010203z-feedface", 41L, 9001L, UUID.randomUUID());

        assertThatThrownBy(() -> bootstrap.bootstrap(request))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("not bound to this runtime");
    }

    private static MockHttpServletRequest request(String remoteAddress, String token) {
        return request("POST", remoteAddress, token);
    }

    private static MockHttpServletRequest request(
            String method, String remoteAddress, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method, LocalSyntheticPayrollFoundationBootstrapFilter.PATH);
        request.setRemoteAddr(remoteAddress);
        request.addHeader(LocalSyntheticPayrollFoundationBootstrapFilter.TOKEN_HEADER, token);
        return request;
    }

    private static MockHttpServletRequest duplicateTokenRequest() {
        MockHttpServletRequest request = request("127.0.0.1", TOKEN);
        request.addHeader(LocalSyntheticPayrollFoundationBootstrapFilter.TOKEN_HEADER, TOKEN);
        return request;
    }
}
