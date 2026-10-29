package com.dwp.services.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingFollowupInternalSecurityChainTest {

    @Test
    void dedicatedChainMatchesOnlyTheExactRouteAndInstallsItsOwnFilter() {
        try (var context = context()) {
            SecurityFilterChain meeting = context.getBean(
                    "meetingFollowupInternalSecurityFilterChain",
                    SecurityFilterChain.class);
            SecurityFilterChain productSurface = context.getBean(
                    "productSurfaceInternalSecurityFilterChain",
                    SecurityFilterChain.class);

            assertThat(meeting.matches(request(
                    "POST", ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH)))
                    .isTrue();
            assertThat(meeting.getFilters()).anyMatch(
                    ProductSurfaceInternalSecurityConfig.MeetingFollowupTokenFilter.class
                            ::isInstance);
            assertThat(productSurface.matches(request(
                    "POST", ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH)))
                    .isFalse();

            for (String sibling : new String[] {
                    ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH + "/",
                    ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH + "/extra",
                    "/internal/auth/v1/meeting-followup-authority/Evaluate"}) {
                assertThat(meeting.matches(request("POST", sibling)))
                        .as(sibling).isFalse();
            }
        }
    }

    @Test
    void realFilterChainRejectsEveryUnverifiedOrAmbiguousMeetingCaller()
            throws Exception {
        try (var context = context()) {
            FilterChainProxy proxy = context.getBean(FilterChainProxy.class);
            MockHttpServletRequest accepted = meetingRequest(
                    "POST",
                    ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY,
                    "trusted-meeting-followup-token");
            MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
            AtomicBoolean acceptedDownstream = new AtomicBoolean();

            proxy.doFilter(accepted, acceptedResponse,
                    (request, response) -> acceptedDownstream.set(true));

            assertThat(acceptedResponse.getStatus()).isEqualTo(200);
            assertThat(acceptedDownstream).isTrue();
            assertThat(accepted.getAttribute(ProductSurfaceInternalSecurityConfig
                    .AUTHENTICATED_SERVICE_IDENTITY_ATTRIBUTE))
                    .isEqualTo(ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY);

            assertRejected(proxy, meetingRequest("POST", null,
                    "trusted-meeting-followup-token"), 401);
            assertRejected(proxy, meetingRequest("POST",
                    ProductSurfaceInternalSecurityConfig.GATEWAY_SERVICE_IDENTITY,
                    "trusted-meeting-followup-token"), 401);
            assertRejected(proxy, meetingRequest("POST",
                    ProductSurfaceInternalSecurityConfig.PLATFORM_SERVICE_IDENTITY,
                    "trusted-meeting-followup-token"), 401);

            MockHttpServletRequest genericToken = meetingRequest(
                    "POST", ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY, null);
            genericToken.addHeader(ProductSurfaceInternalSecurityConfig.TOKEN_HEADER,
                    "trusted-product-surface-token");
            assertRejected(proxy, genericToken, 401);

            MockHttpServletRequest mixedProduct = meetingRequest(
                    "POST", ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY,
                    "trusted-meeting-followup-token");
            mixedProduct.addHeader(ProductSurfaceInternalSecurityConfig.TOKEN_HEADER,
                    "trusted-product-surface-token");
            assertRejected(proxy, mixedProduct, 401);

            MockHttpServletRequest mixedHris = meetingRequest(
                    "POST", ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY,
                    "trusted-meeting-followup-token");
            mixedHris.addHeader(
                    ProductSurfaceInternalSecurityConfig.HRIS_ORIGIN_AUTHORITY_TOKEN_HEADER,
                    "trusted-hris-origin-token");
            assertRejected(proxy, mixedHris, 401);

            MockHttpServletRequest duplicateToken = meetingRequest(
                    "POST", ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY,
                    "trusted-meeting-followup-token");
            duplicateToken.addHeader(
                    ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER,
                    "trusted-meeting-followup-token");
            assertRejected(proxy, duplicateToken, 401);

            MockHttpServletRequest duplicateIdentity = meetingRequest(
                    "POST", ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY,
                    "trusted-meeting-followup-token");
            duplicateIdentity.addHeader(
                    ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                    ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY);
            assertRejected(proxy, duplicateIdentity, 401);

            assertRejected(proxy, meetingRequest(
                    "GET", ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY,
                    "trusted-meeting-followup-token"), 401);

            MockHttpServletRequest sibling = new MockHttpServletRequest(
                    "POST", ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH + "/extra");
            sibling.addHeader(ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER,
                    ProductSurfaceInternalSecurityConfig.MEETING_SERVICE_IDENTITY);
            sibling.addHeader(
                    ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER,
                    "trusted-meeting-followup-token");
            assertRejected(proxy, sibling, 403);
        }
    }

    private AnnotationConfigWebApplicationContext context() {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "meeting-followup-security-test",
                Map.of(
                        "dwp.auth.product-surface-token",
                        "trusted-product-surface-token",
                        "dwp.auth.meeting-followup-authority-token",
                        "trusted-meeting-followup-token",
                        "dwp.auth.hris-origin-authority-token",
                        "trusted-hris-origin-token")));
        context.register(Fixture.class);
        context.refresh();
        return context;
    }

    private MockHttpServletRequest meetingRequest(
            String method, String identity, String token) {
        MockHttpServletRequest request = request(
                method, ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_PATH);
        if (identity != null) {
            request.addHeader(
                    ProductSurfaceInternalSecurityConfig.SERVICE_IDENTITY_HEADER, identity);
        }
        if (token != null) {
            request.addHeader(
                    ProductSurfaceInternalSecurityConfig.MEETING_FOLLOWUP_TOKEN_HEADER, token);
        }
        return request;
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private void assertRejected(
            FilterChainProxy proxy,
            MockHttpServletRequest request,
            int status) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        proxy.doFilter(request, response,
                (servletRequest, servletResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(invoked).isFalse();
        assertThat(request.getAttribute(ProductSurfaceInternalSecurityConfig
                .AUTHENTICATED_SERVICE_IDENTITY_ATTRIBUTE)).isNull();
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    @Import(ProductSurfaceInternalSecurityConfig.class)
    static class Fixture {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        @Order(99)
        SecurityFilterChain fallbackSecurityFilterChain(HttpSecurity http) throws Exception {
            http
                    .securityMatcher("/**")
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
            return http.build();
        }
    }
}
