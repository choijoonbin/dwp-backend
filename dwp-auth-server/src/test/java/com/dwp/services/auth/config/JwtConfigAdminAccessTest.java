package com.dwp.services.auth.config;

import com.dwp.services.auth.security.AuthSessionActivityFilter;
import com.dwp.services.auth.security.AuthSessionJwtValidator;
import com.dwp.services.auth.scim.ScimCredentialService;
import com.dwp.services.auth.service.AuthSessionService;
import com.dwp.services.auth.service.SessionCookieService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = JwtConfigAdminSecurityProbe.class,
        properties = "jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
@Import({JwtConfig.class, SecurityExceptionHandler.class, AuthSessionActivityFilter.class})
class JwtConfigAdminAccessTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthSessionJwtValidator sessionValidator;

    @MockitoBean
    private AuthSessionService sessionService;

    @MockitoBean
    private SessionCookieService sessionCookieService;

    @MockitoBean
    private ScimCredentialService scimCredentialService;

    @BeforeEach
    void allowSyntheticSession() {
        when(sessionValidator.validate(any(Jwt.class)))
                .thenReturn(OAuth2TokenValidatorResult.success());
    }

    @Test
    void namedReviewerIsDeniedAtTheSecurityChainForTheBaseAndNestedAdminPaths()
            throws Exception {
        Cookie session = session(List.of("APP_ACCESS_REVIEWER"));

        mockMvc.perform(get("/auth/admin/access/reviews").cookie(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/auth/admin/access/reviews/security-probe").cookie(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantAdminPassesTheSecurityChainForTheExactAdminPathFamily() throws Exception {
        Cookie session = session(List.of("TENANT_ADMIN"));

        mockMvc.perform(get("/auth/admin/access/reviews").cookie(session))
                .andExpect(status().isOk());
        mockMvc.perform(get("/auth/admin/access/reviews/security-probe").cookie(session))
                .andExpect(status().isOk());
    }

    private Cookie session(List<String> roles) {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("7")
                .issuedAt(Date.from(now.minusSeconds(30)))
                .expiration(Date.from(now.plusSeconds(300)))
                .claim("tenant_id", 1L)
                .claim("roles", roles)
                .signWith(
                        Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();
        return new Cookie("DWP_SESSION", token);
    }

}

@RestController
class JwtConfigAdminSecurityProbe {

    @GetMapping({
            "/auth/admin/access/reviews",
            "/auth/admin/access/reviews/security-probe"
    })
    Map<String, Boolean> available() {
        return Map.of("available", true);
    }
}
