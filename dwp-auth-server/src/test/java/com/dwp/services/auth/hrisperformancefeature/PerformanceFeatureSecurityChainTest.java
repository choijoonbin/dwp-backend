package com.dwp.services.auth.hrisperformancefeature;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.dwp.services.auth.hrisperformancefeature.PerformanceWorkloadFixture.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PerformanceFeatureSecurityChainTest {
    @Test void exactDedicatedChainRejectsBrowserProofAliasesAndDuplicatesBeforeAnyAuthorityRead() throws Exception {
        try (var context = context(WithTrust.class)) {
            var service = context.getBean(PerformanceFeatureService.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
            byte[] body = body(); String proof = currentProof(body);
            mvc.perform(post(PerformanceFeatureContract.PATH).contentType("application/json")
                    .header("X-DWP-Service-Identity", "dwp-platform-server")
                    .header("X-DWP-Workload-Proof", proof).content(body)).andExpect(status().isOk());
            verify(service).evaluate(REQUEST); clearInvocations(service);
            for (String header : List.of("Authorization", "Cookie", "Origin", "Referer", "X-DWP-Tenant-Id",
                    "X-DWP-Unregistered-Authority", "X-DWP-Workload-Proof", "X-DWP-Service-Identity")) {
                mvc.perform(post(PerformanceFeatureContract.PATH).contentType("application/json")
                        .header("X-DWP-Service-Identity", "dwp-platform-server")
                        .header("X-DWP-Workload-Proof", currentProof(body)).header(header, "spoof").content(body))
                        .andExpect(status().isForbidden());
            }
            mvc.perform(post(PerformanceFeatureContract.PATH + "?tenantId=8").contentType("application/json")
                    .header("X-DWP-Service-Identity", "dwp-platform-server")
                    .header("X-DWP-Workload-Proof", currentProof(body)).content(body)).andExpect(status().isForbidden());
            mvc.perform(get(PerformanceFeatureContract.PATH)).andExpect(status().isForbidden());
            for (String suffix : List.of("/", "/extra")) {
                mvc.perform(post(PerformanceFeatureContract.PATH + suffix).contentType("application/json").content(body))
                        .andExpect(status().isNotFound());
            }
            verifyNoInteractions(service);
        }
    }

    @Test void productionDefaultHasNoSigningOrDurableReplayProviderAndMakesZeroAuthorityCalls() throws Exception {
        try (var context = context(Unbound.class)) {
            var service = context.getBean(PerformanceFeatureService.class);
            var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
            byte[] body = body();
            mvc.perform(post(PerformanceFeatureContract.PATH).contentType("application/json")
                    .header("X-DWP-Service-Identity", "dwp-platform-server")
                    .header("X-DWP-Workload-Proof", currentProof(body)).content(body)).andExpect(status().isForbidden());
            verifyNoInteractions(service);
        }
    }

    private AnnotationConfigWebApplicationContext context(Class<?> configuration) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext()); context.register(configuration); context.refresh(); return context;
    }
    private String currentProof(byte[] body) throws Exception {
        var claims = claims(body); long now = Instant.now().getEpochSecond();
        claims.put("iat", now); claims.put("nbf", now); claims.put("exp", now + 30); return token(claims);
    }

    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({PerformanceFeatureSecurityConfig.class, PerformanceFeatureController.class})
    static class Unbound {
        @Bean ObjectMapper mapper() { return JSON; }
        @Bean PerformanceFeatureService service() { return mock(PerformanceFeatureService.class); }
    }
    @Configuration @Import(Unbound.class)
    static class WithTrust {
        @Bean PerformanceFeatureWorkloadVerifier.SigningKeys signingKeys() { return keys(); }
        @Bean PerformanceFeatureWorkloadVerifier.ReplayStore replayStore() { return (a, b, c, d) -> true; }
    }
}
