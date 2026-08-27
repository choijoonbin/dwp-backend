package com.dwp.services.auth.config;

import com.dwp.services.auth.scim.ScimCredentialService;
import com.dwp.services.auth.security.DurableIdentityPlaneGuard;
import com.dwp.services.auth.service.AuthSessionService;
import com.dwp.services.auth.service.SessionCookieService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = ProductAuthorizationInternalSecurityProbe.class,
        properties = {
                "dwp.product-authorization.operations.provider-approval-token=provider-approval-secret",
                "dwp.product-authorization.operations.platform-activation-token=platform-activation-secret"
        })
@Import(ProductAuthorizationOperationsSecurityConfig.class)
class ProductAuthorizationInternalSecurityChainTest {

    private static final String USER_JWT_SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String READ_ROOT =
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_ROOT
                    + "/bundles/product-surfaces";
    private static final String OPERATIONS_ROOT =
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX
                    + "/bundles/product-surfaces/versions/3";
    private static final String OPERATIONS_READ_ROOT =
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX
                    + "/bundles/product-surfaces";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScimCredentialService scimCredentialService;

    @MockitoBean
    private AuthSessionService authSessionService;

    @MockitoBean
    private DurableIdentityPlaneGuard durableIdentityPlaneGuard;

    @MockitoBean
    private SessionCookieService sessionCookieService;

    @MockitoBean
    private SecurityExceptionHandler securityExceptionHandler;

    @Test
    void noTokenCannotReachImmutableReadsOrLifecycleOperations() throws Exception {
        mockMvc.perform(get(READ_ROOT + "/active"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(READ_ROOT + "/versions/3"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(OPERATIONS_ROOT + "/activation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantAndProviderJwtsCannotEscapeTheServiceTokenChain() throws Exception {
        for (String jwt : new String[] {
                userJwt(11, "TENANT_ADMIN"), userJwt(12, "PROVIDER_ADMIN")}) {
            mockMvc.perform(get(READ_ROOT + "/active")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                            .cookie(new Cookie("DWP_SESSION", jwt)))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post(OPERATIONS_ROOT + "/activation")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                            .cookie(new Cookie("DWP_SESSION", jwt)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void platformServiceTokenCanReadAndRunActivationOperations() throws Exception {
        mockMvc.perform(platformGet(READ_ROOT + "/active"))
                .andExpect(status().isOk());
        mockMvc.perform(platformGet(READ_ROOT + "/versions/3"))
                .andExpect(status().isOk());
        mockMvc.perform(platformGet(OPERATIONS_READ_ROOT + "/active"))
                .andExpect(status().isOk());
        mockMvc.perform(platformGet(OPERATIONS_READ_ROOT + "/versions/3"))
                .andExpect(status().isOk());
        mockMvc.perform(platformPost(OPERATIONS_ROOT + "/activation"))
                .andExpect(status().isOk());
        mockMvc.perform(platformPost(OPERATIONS_ROOT + "/rollback"))
                .andExpect(status().isOk());
    }

    @Test
    void providerServiceTokenCanApproveButCannotReadOrActivate() throws Exception {
        mockMvc.perform(post(OPERATIONS_ROOT + "/approval")
                        .header(
                                ProductAuthorizationOperationsSecurityConfig
                                        .SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig
                                        .PROVIDER_SERVICE_IDENTITY)
                        .header(
                                ProductAuthorizationOperationsSecurityConfig
                                        .APPROVAL_TOKEN_HEADER,
                                "provider-approval-secret"))
                .andExpect(status().isOk());

        mockMvc.perform(get(READ_ROOT + "/active")
                        .header(
                                ProductAuthorizationOperationsSecurityConfig
                                        .SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig
                                        .PROVIDER_SERVICE_IDENTITY)
                        .header(
                                ProductAuthorizationOperationsSecurityConfig
                                        .APPROVAL_TOKEN_HEADER,
                                "provider-approval-secret"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(OPERATIONS_ROOT + "/activation")
                        .header(
                                ProductAuthorizationOperationsSecurityConfig
                                        .SERVICE_IDENTITY_HEADER,
                                ProductAuthorizationOperationsSecurityConfig
                                        .PROVIDER_SERVICE_IDENTITY)
                        .header(
                                ProductAuthorizationOperationsSecurityConfig
                                        .APPROVAL_TOKEN_HEADER,
                                "provider-approval-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownChildrenStayClosedEvenWithAValidPlatformCredential() throws Exception {
        mockMvc.perform(platformGet(
                        ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_ROOT
                                + "/unclassified"))
                .andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            platformGet(String path) {
        return get(path)
                .header(
                        ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                        ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                .header(
                        ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                        "platform-activation-secret");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            platformPost(String path) {
        return post(path)
                .header(
                        ProductAuthorizationOperationsSecurityConfig.SERVICE_IDENTITY_HEADER,
                        ProductAuthorizationOperationsSecurityConfig.PLATFORM_SERVICE_IDENTITY)
                .header(
                        ProductAuthorizationOperationsSecurityConfig.ACTIVATION_TOKEN_HEADER,
                        "platform-activation-secret");
    }

    private static String userJwt(long userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim("tenant_id", "1")
                .claim("roles", List.of(role))
                .claim("sid", UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(30)))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(
                        Keys.hmacShaKeyFor(
                                USER_JWT_SECRET.getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();
    }
}

@RestController
class ProductAuthorizationInternalSecurityProbe {

    @GetMapping({
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_ROOT
                    + "/bundles/{bundleKey}/active",
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_ROOT
                    + "/bundles/{bundleKey}/versions/{version}",
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX
                    + "/bundles/{bundleKey}/active",
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX
                    + "/bundles/{bundleKey}/versions/{version}"
    })
    Map<String, Boolean> read() {
        return Map.of("available", true);
    }

    @PostMapping({
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX
                    + "/bundles/{bundleKey}/versions/{version}/approval",
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX
                    + "/bundles/{bundleKey}/versions/{version}/activation",
            ProductAuthorizationOperationsSecurityConfig.INTERNAL_PATH_PREFIX
                    + "/bundles/{bundleKey}/versions/{version}/rollback"
    })
    Map<String, Boolean> operate() {
        return Map.of("available", true);
    }
}
