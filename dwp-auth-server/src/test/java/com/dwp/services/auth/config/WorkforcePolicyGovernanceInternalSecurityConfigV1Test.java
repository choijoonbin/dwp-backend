package com.dwp.services.auth.config;

import static com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.*;
import static com.dwp.services.auth.config.WorkforcePolicyGovernanceInternalSecurityConfigV1.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dwp.services.auth.controller.WorkforcePolicyGovernanceAuthorityControllerV1;
import com.dwp.services.auth.service.WorkforcePolicyGovernanceAuthorityAdapterV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Exact real security chain + native HS256/default validators. Adapter is MOCK;
 * actual native session/actor/factory/evaluator are tested in the separate PG suite. */
class WorkforcePolicyGovernanceInternalSecurityConfigV1Test {
    static final String SECRET="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static AnnotationConfigWebApplicationContext context;static MockMvc mvc;
    static WorkforcePolicyGovernanceAuthorityAdapterV1 adapter;
    @Configuration @EnableWebMvc @EnableWebSecurity @Import(WorkforcePolicyGovernanceInternalSecurityConfigV1.class)
    static class Fixture {
        @Bean ObjectMapper objectMapper(){return new ObjectMapper();}
        @Bean JwtDecoder decoder(){
            var value=NimbusJwtDecoder.withSecretKey(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256).build();value.setJwtValidator(JwtValidators.createDefault());return value;
        }
        @Bean WorkforcePolicyGovernanceAuthorityAdapterV1 adapter(){return mock(WorkforcePolicyGovernanceAuthorityAdapterV1.class);}
        @Bean WorkforcePolicyGovernanceAuthorityControllerV1 controller(WorkforcePolicyGovernanceAuthorityAdapterV1 value){return new WorkforcePolicyGovernanceAuthorityControllerV1(value);}
    }
    @BeforeAll static void buildOnlyDedicatedChain(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getSystemProperties().put("dwp.auth.workforce-policy-governance-token","owned-test-service-token");
        context.register(Fixture.class);context.refresh();adapter=context.getBean(WorkforcePolicyGovernanceAuthorityAdapterV1.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(org.springframework.security.web.FilterChainProxy.class)).build();
    }
    @AfterAll static void close(){if(context!=null)context.close();System.clearProperty("dwp.auth.workforce-policy-governance-token");}
    @BeforeEach void resetMock(){reset(adapter);when(adapter.evaluate(any(),any())).thenReturn(com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.denied(Code.OPERATION_UNREGISTERED));}
    static String jwt(boolean expired){
        Instant now=Instant.now();return Jwts.builder().id("owned-jwt").subject("900009").claim("tenant_id","41")
                .issuedAt(Date.from(now.minusSeconds(expired?300:1))).expiration(Date.from(now.plusSeconds(expired?-180:60)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),Jwts.SIG.HS256).compact();
    }
    static MockHttpServletRequestBuilder valid(String path){return post(path).header(TOKEN_HEADER,"owned-test-service-token")
            .header(IDENTITY_HEADER,"dwp-people-server").header("Authorization","Bearer "+jwt(false))
            .contentType("application/json").content("{}");}
    @Test void exactDualAuthorityHeadersAndSignedActorJwtReachOnlyReadAdapter() throws Exception {
        mvc.perform(valid(PATH)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OPERATION_UNREGISTERED"))
                .andExpect(jsonPath("$.boundary").value("READ_ONLY_NOT_COMMAND_PERMIT"));
        verify(adapter).evaluate(any(byte[].class),anyString());
    }
    static Stream<String> absentHeaders(){return Stream.of(TOKEN_HEADER,IDENTITY_HEADER,"Authorization");}
    @ParameterizedTest(name="{displayName} [{index}] {0}") @MethodSource("absentHeaders")
    void eachMissingHeaderRejectsBeforeAdapter(String header)throws Exception {
        var request=valid(PATH);request.headers(new org.springframework.http.HttpHeaders());
        request=post(PATH).content("{}");if(!header.equals(TOKEN_HEADER))request.header(TOKEN_HEADER,"owned-test-service-token");
        if(!header.equals(IDENTITY_HEADER))request.header(IDENTITY_HEADER,"dwp-people-server");
        if(!header.equals("Authorization"))request.header("Authorization","Bearer "+jwt(false));
        mvc.perform(request).andExpect(status().isUnauthorized());verifyNoInteractions(adapter);
    }
    @ParameterizedTest(name="{displayName} [{index}] {0}") @MethodSource("absentHeaders")
    void duplicateOrCommaCombinedAuthorityHeadersReject(String header)throws Exception {
        var request=valid(PATH).header(header,"second-authority");
        mvc.perform(request).andExpect(status().isUnauthorized());verifyNoInteractions(adapter);
    }
    static Stream<String> spoofedHeaders(){return Stream.of("X-DWP-Product-Surface-Token","X-DWP-Meeting-Followup-Authority-Token",
            "X-User-Id","X-Tenant-Id","X-DWP-User-Id","X-DWP-Tenant-Id","X-DWP-Person-Public-Id",
            "X-DWP-Roles","X-Roles","X-DWP-Permissions","X-Permissions","X-DWP-Resource-Roles");}
    @ParameterizedTest @MethodSource("spoofedHeaders")
    void forwardedIdentityAndOtherInternalTokensNeverBecomeS1Authority(String header)throws Exception {
        mvc.perform(valid(PATH).header(header,"spoofed-owner-claim")).andExpect(status().isUnauthorized());verifyNoInteractions(adapter);
    }
    @Test void wrongServiceIdentityOrTokenRejectsBeforeJwtAndAdapter()throws Exception{
        mvc.perform(post(PATH).header(TOKEN_HEADER,"wrong").header(IDENTITY_HEADER,"dwp-people-server")
                .header("Authorization","Bearer "+jwt(false))).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header(TOKEN_HEADER,"owned-test-service-token").header(IDENTITY_HEADER,"dwp-auth-server")
                .header("Authorization","Bearer "+jwt(false))).andExpect(status().isUnauthorized());verifyNoInteractions(adapter);
    }
    @Test void expiredOrTamperedJwtIsNotAServiceOnlyRead()throws Exception{
        mvc.perform(post(PATH).header(TOKEN_HEADER,"owned-test-service-token").header(IDENTITY_HEADER,"dwp-people-server")
                .header("Authorization","Bearer "+jwt(true))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("ACTOR_JWT_INVALID"));
        mvc.perform(post(PATH).header(TOKEN_HEADER,"owned-test-service-token").header(IDENTITY_HEADER,"dwp-people-server")
                .header("Authorization","Bearer tampered-secret-payload")).andExpect(status().isUnauthorized());verifyNoInteractions(adapter);
    }
    @Test void cookieDoesNotReplaceSingleBearerHeader()throws Exception{
        mvc.perform(post(PATH).header(TOKEN_HEADER,"owned-test-service-token").header(IDENTITY_HEADER,"dwp-people-server")
                .cookie(new Cookie("DWP_SESSION",jwt(false)))).andExpect(status().isUnauthorized());verifyNoInteractions(adapter);
    }
    @Test void endpointOnlyPathAndMethodCannotFallThroughAnotherSecurityChain()throws Exception{
        for(String path:List.of(PATH+"/",PATH+"/unknown",PATH.replace("/evaluate","/mutate")))mvc.perform(valid(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header(TOKEN_HEADER,"owned-test-service-token").header(IDENTITY_HEADER,"dwp-people-server")
                .header("Authorization","Bearer "+jwt(false))).andExpect(status().isMethodNotAllowed());verifyNoInteractions(adapter);
    }
    @Test void bodyLimitAppliesBeforeAdapterEvenAfterValidAuthentication()throws Exception{
        mvc.perform(valid(PATH).content(" ".repeat(32769))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));verifyNoInteractions(adapter);
    }
}
