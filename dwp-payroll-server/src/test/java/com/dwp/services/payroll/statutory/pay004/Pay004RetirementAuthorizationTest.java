package com.dwp.services.payroll.statutory.pay004;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static com.dwp.services.payroll.statutory.pay004.Pay004TestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class Pay004RetirementAuthorizationTest {

    @Test
    void subjectRequiresExactSevenLayerAuthorityEvidence() {
        Pay004Authority.Actor actor = Pay004TestFixtures.actor();
        assertThat(actor.subject().route()).isEqualTo(Pay004Route.RETIREMENT);
        assertThat(actor.subject().purposeCode())
                .isEqualTo("PAYROLL_OPERATIONS");
        assertThat(actor.subject().populationKind())
                .isEqualTo("TARGET_POPULATION");
        assertThat(actor.subject().fieldPolicyKey())
                .isEqualTo("pay004.iaq-049.fields.v1");
        assertThat(actor.subject().permissions())
                .contains("APP.HCM:VIEW", "DATA.HRIS_PAYROLL_STATUS:VIEW");
        assertThat(actor.subject().duties())
                .containsExactly("HRIS_DUTY_PAYROLL_SUPPORT_VIEWER");

        assertThatThrownBy(() -> new Pay004Authority.Subject(
                1, 91, Pay004TestFixtures.SUBJECT, Pay004Route.RETIREMENT,
                Pay004Models.PURPOSE, "ctx", "scope", 1, 1, "revision",
                NOW.plusSeconds(10), "NORMAL", Pay004Models.PROFILE,
                "TARGET_POPULATION", Pay004Models.FIELD_POLICY,
                Set.of("APP.HCM:VIEW"), Set.of(Pay004Models.DUTY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Pay004Authority.Subject(
                1, 91, Pay004TestFixtures.SUBJECT, Pay004Route.RETIREMENT,
                "WRONG_PURPOSE", "ctx", "scope", 1, 1, "revision",
                NOW.plusSeconds(10), "NORMAL", Pay004Models.PROFILE,
                "TARGET_POPULATION", Pay004Models.FIELD_POLICY,
                Set.of("APP.HCM:VIEW", Pay004Models.CAPABILITY),
                Set.of(Pay004Models.DUTY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staleRevisionAndIncompleteFieldPolicyFailClosedBeforeOwnerRead() {
        Pay004Store store = mock(Pay004Store.class);
        Pay004Ports.ProjectionDecisionPort decisions = mock(
                Pay004Ports.ProjectionDecisionPort.class);
        Pay004Service service = new Pay004Service(
                store, decisions, Pay004TestFixtures.canonical(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.retirement(
                Pay004TestFixtures.actor(NOW), Pay004TestFixtures.query()))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(store, decisions);

        org.mockito.Mockito.when(decisions.decisions(
                Pay004TestFixtures.actor(), Pay004Models.VARIANT))
                .thenReturn(Map.of("CASE_STATUS", Pay004Models.Decision.VIEW));
        assertThatThrownBy(() -> service.retirement(
                Pay004TestFixtures.actor(), Pay004TestFixtures.query()))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(store);
    }

    @Test
    void allRuntimeBeansAreStrictlyDefaultOff() {
        assertDefaultOff(Pay004Canonical.class);
        assertDefaultOff(Pay004Controller.class);
        assertDefaultOff(Pay004SecurityFilter.class);
        assertDefaultOff(Pay004ProblemAdvice.class);
        assertDefaultOff(Pay004Service.class);
        assertDefaultOff(JdbcPay004Store.class);
        assertDefaultOff(JdbcPay004ScopeResolver.class);
        assertDefaultOff(Pay004UnavailableProviders.class);
    }

    @Test
    void zeroTenantEnrollmentConcealsTheSurfaceBeforeControllerDispatch()
            throws Exception {
        Pay004SecurityFilter filter = new Pay004SecurityFilter(
                "service-secret", "", Pay004TestFixtures.MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/workbench/pay-retirement");
        request.addParameter("filter", "{}");
        request.addParameter("limit", "25");
        request.addParameter("locale", "en-GB");
        request.addParameter("sort", "itemPublicId:ASC");
        request.addParameter("timeZone", "Europe/London");
        request.addParameter("correlationId",
                Pay004TestFixtures.CORRELATION.toString());
        request.addHeader("Authorization", "Bearer gateway-user");
        request.addHeader("X-DWP-Service-Token", "service-secret");
        request.addHeader("X-DWP-Tenant-ID", "1");
        request.addHeader("X-DWP-User-ID", "91");
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                Pay004TestFixtures.SUBJECT.toString());
        request.addHeader("X-DWP-Permissions",
                "APP.HCM:VIEW," + Pay004Models.CAPABILITY);
        request.addHeader("X-DWP-Responsibilities", Pay004Models.DUTY);
        request.addHeader("X-DWP-Access-Profile", Pay004Models.PROFILE);
        request.addHeader("X-DWP-Route-Contract-Key",
                "route.hcm.operations.pay-retirement.data");
        request.addHeader("X-DWP-Current-Decision-Revision", "decision-41");
        request.addHeader("X-DWP-Current-Revalidate-At",
                NOW.plusSeconds(600).toString());
        request.addHeader("X-DWP-Context-Key", "ctx-pay004");
        request.addHeader("X-DWP-Context-Scope-Key",
                HcmEligibilityScopeKey.derived(
                        1, 91, Pay004Route.RETIREMENT.name(),
                        "source-scope", "rel-1", "pop-1"));
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        request.addHeader("X-DWP-Field-Policy-Revision", "17");
        request.addHeader("X-DWP-Authorization-Revision", "23");
        request.addHeader("X-Purpose-Code", Pay004Models.PURPOSE);
        request.addHeader("X-DWP-Population-Kind", "TARGET_POPULATION");
        request.addHeader("X-DWP-Field-Policy-Key", Pay004Models.FIELD_POLICY);
        request.addHeader("X-Correlation-ID",
                Pay004TestFixtures.CORRELATION.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains(
                "TARGET_NOT_DISCLOSED",
                Pay004TestFixtures.CORRELATION.toString());
        verifyNoInteractions(chain);
    }

    private void assertDefaultOff(Class<?> type) {
        ConditionalOnProperty condition = type.getAnnotation(
                ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.name())
                .containsExactly("dwp.features.HRIS_PAY_RETIREMENT_WORKBENCH.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }
}
