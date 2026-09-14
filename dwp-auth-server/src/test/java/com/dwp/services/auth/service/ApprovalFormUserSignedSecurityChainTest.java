package com.dwp.services.auth.service;

import static com.dwp.services.auth.service.ApprovalFormUserProofTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.auth.config.ApprovalFormUserInternalSecurityConfig;
import com.dwp.services.auth.controller.ApprovalFormUserDirectoryController;
import com.dwp.services.auth.dto.ApprovalFormUserDirectoryDtos.ResolvedPerson;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos.*;
import com.dwp.services.auth.entity.Tenant;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.ApprovalFormUserDirectoryRepository;
import com.dwp.services.auth.repository.TenantRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class ApprovalFormUserSignedSecurityChainTest {
    private static final String ACTION = "route.approvals.work.request-draft-update.action";

    @Test
    void actualSignedResolveChainEnforcesCurrentSourceReplayDigestAndPrePostPolicyWithoutLeakingPeople() throws Exception {
        try (var redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379)) {
            redis.start();
            try (var context = new AnnotationConfigWebApplicationContext()) {
                context.setServletContext(new MockServletContext());
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("signed-purpose-test", Map.of(
                        "dwp.auth.approval-form-user-token", "dedicated-token", "test.redis.host", redis.getHost(),
                        "test.redis.port", redis.getMappedPort(6379))));
                context.register(TestConfig.class); context.refresh();
                var users = context.getBean(UserRepository.class); var tenants = context.getBean(TenantRepository.class);
                when(users.findTenantIdentityByUserIdAndTenantId(20L, 10L)).thenReturn(Optional.of(
                        User.builder().userId(20L).tenantId(10L).identityPlane("TENANT").status("ACTIVE").build()));
                when(tenants.findById(10L)).thenReturn(Optional.of(Tenant.builder().tenantId(10L).status("ACTIVE").build()));
                var identities = context.getBean(ProductAuthorizationIdentityEvidenceService.class);
                evidence(identities, true);
                var routes = context.getBean(ProductSurfaceAuthorityService.class);
                when(routes.evaluate(any())).thenReturn(result("policy-current"));
                var directory = context.getBean(ApprovalFormUserDirectoryRepository.class);
                when(directory.resolve(10L, List.of(PERSON))).thenReturn(List.of(new ResolvedPerson(10L, 101L, PERSON, "Kim", "TENANT", "ACTIVE")));
                MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
                String body = body();
                String response = mvc.perform(request("/resolve", body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
                var resolved = MAPPER.readTree(response);
                assertThat(resolved.isObject()).as("Exact internal response: %s", response).isTrue();
                assertThat(resolved.path("people").path(0).path("personPublicId").textValue()).isEqualTo(PERSON.toString());
                verify(routes, times(2)).evaluate(argThat(value -> ACTION.equals(value.routeContractKey())));
                verify(directory).resolve(10L, List.of(PERSON)); clearInvocations(directory);

                mvc.perform(request("/resolve", body()).accept(MediaType.APPLICATION_XML)).andExpect(status().isForbidden());
                mvc.perform(request("/resolve", body)).andExpect(status().isForbidden());
                mvc.perform(request("/resolve", body.replace(PERSON.toString(), "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")))
                        .andExpect(status().isForbidden());
                mvc.perform(request("/search", body)).andExpect(status().is4xxClientError());
                verifyNoInteractions(directory);

                String digest = verifier().requestDigest("RESOLVE", Map.of("personPublicIds", List.of(PERSON)));
                var aliasClaims = referenceClaims("route.approvals.work.request-draft-recover.action", 0, digest);
                aliasClaims.put("routeContractKey", "route.approvals.work.drafts.recover.action");
                aliasClaims.put("jti", UUID.randomUUID().toString());
                String aliasBody = MAPPER.writeValueAsString(Map.of("sourceProof", sign(aliasClaims), "personPublicIds", List.of(PERSON)));
                var redisKeys = new StringRedisTemplate(context.getBean(LettuceConnectionFactory.class));
                var beforeAliasKeys = redisKeys.keys("*");
                clearInvocations(users, tenants, identities, routes, directory);
                mvc.perform(request("/resolve", aliasBody)).andExpect(status().isForbidden()).andExpect(jsonPath("$.people").doesNotExist());
                verifyNoInteractions(users, tenants, identities, routes, directory);
                assertThat(redisKeys.keys("*")).isEqualTo(beforeAliasKeys);

                evidence(identities, false);
                mvc.perform(request("/resolve", body())).andExpect(status().isForbidden()).andExpect(jsonPath("$.people").doesNotExist());
                verifyNoInteractions(directory);
                evidence(identities, true);
                when(routes.evaluate(any())).thenReturn(result("policy-current")).thenReturn(result("policy-changed"));
                mvc.perform(request("/resolve", body())).andExpect(status().isConflict()).andExpect(jsonPath("$.people").doesNotExist());
                verify(directory).resolve(10L, List.of(PERSON));
            }
        }
    }

    private static MockHttpServletRequestBuilder request(String suffix, String body) {
        return post(ApprovalFormUserInternalSecurityConfig.PREFIX + suffix)
                .header(ApprovalFormUserInternalSecurityConfig.TOKEN_HEADER, "dedicated-token")
                .header("X-DWP-Service-Identity", "dwp-approval-server").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body() throws Exception {
        String digest = verifier().requestDigest("RESOLVE", Map.of("personPublicIds", List.of(PERSON)));
        var claims = referenceClaims(ACTION, 0, digest); claims.put("jti", UUID.randomUUID().toString());
        return MAPPER.writeValueAsString(Map.of("sourceProof", sign(claims), "personPublicIds", List.of(PERSON)));
    }

    private static void evidence(ProductAuthorizationIdentityEvidenceService identities, boolean source) {
        var permissions = new java.util.HashSet<>(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:UPDATE"));
        if (source) permissions.add(ApprovalFormUserCurrentAuthorityAdapter.SOURCE_PERMISSION);
        when(identities.load(10L, 20L)).thenReturn(new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                permissions, Set.of(), List.of(), List.of(), "auth-current"));
    }

    private static AuthorityResult result(String policy) {
        return new AuthorityResult(Decision.ALLOWED, null, "auth-current", policy, "approval-context", "approvals", "approvals.work", "work",
                AccessMode.NORMAL, AccessSource.ENTITLEMENT, "APP.APPROVALS", List.of(),
                List.of(new EffectiveScope("approval-scope", "SELF", "Self", true, true, null)), "exact-action", true, false,
                null, null, null, null, OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC), "evidence");
    }

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    @Import({ApprovalFormUserInternalSecurityConfig.class, ApprovalFormUserDirectoryController.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean ObjectMapper objectMapper() { return MAPPER; }
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean TenantRepository tenants() { return mock(TenantRepository.class); }
        @Bean ProductAuthorizationIdentityEvidenceService identities() { return mock(ProductAuthorizationIdentityEvidenceService.class); }
        @Bean ProductSurfaceAuthorityService routes() { return mock(ProductSurfaceAuthorityService.class); }
        @Bean ApprovalFormUserDirectoryRepository directory() { return mock(ApprovalFormUserDirectoryRepository.class); }
        @Bean ApprovalFormUserSourceProofVerifier proofs() { return verifier(); }
        @Bean ApprovalFormReferenceProofVerifier references(ApprovalFormUserSourceProofVerifier proofs) { return new ApprovalFormReferenceProofVerifier(proofs); }
        @Bean(destroyMethod = "destroy") LettuceConnectionFactory redisConnection(org.springframework.core.env.Environment env) {
            return new LettuceConnectionFactory(env.getRequiredProperty("test.redis.host"), Integer.parseInt(env.getRequiredProperty("test.redis.port")));
        }
        @Bean ApprovalFormUserProofReplayStore replay(LettuceConnectionFactory connection) {
            return new ApprovalFormUserProofReplayStore(new StringRedisTemplate(connection), CLOCK);
        }
        @Bean ApprovalFormUserAuthorityPort authority(ProductAuthorizationIdentityEvidenceService identities, ProductSurfaceAuthorityService routes,
                UserRepository users, TenantRepository tenants) {
            return new ApprovalFormUserCurrentAuthorityAdapter(identities, routes, users, tenants, CLOCK);
        }
        @Bean ApprovalFormUserDirectoryService service(ObjectMapper mapper, ApprovalFormUserSourceProofVerifier proofs,
                ApprovalFormReferenceProofVerifier references, ApprovalFormUserProofReplayStore replay,
                ObjectProvider<ApprovalFormUserAuthorityPort> authorities, ApprovalFormUserDirectoryRepository directory) {
            return new ApprovalFormUserDirectoryService(mapper, proofs, references, replay, authorities, directory);
        }
    }
}
