package com.dwp.services.auth.scim;

import com.dwp.observability.api.ApiHistoryAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
public class ScimAuthenticationFilter extends OncePerRequestFilter {

    private static final String SCIM_ERROR_SCHEMA =
            "urn:ietf:params:scim:api:messages:2.0:Error";

    private final ScimCredentialService credentialService;
    private final ObjectMapper objectMapper;

    public ScimAuthenticationFilter(
            ScimCredentialService credentialService,
            ObjectMapper objectMapper) {
        this.credentialService = credentialService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/scim/v2/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim()
                : null;
        try {
            ScimConnectorContext.ConnectorIdentity identity = credentialService.authenticate(token);
            ScimConnectorContext.set(identity);
            request.setAttribute(ApiHistoryAttributes.ACTOR_TYPE, "SERVICE");
            request.setAttribute(ApiHistoryAttributes.ACTOR_ID, "scim:" + identity.connectorKey());
            request.setAttribute(ApiHistoryAttributes.TENANT_ID, identity.tenantId());
            request.setAttribute(ApiHistoryAttributes.AUTH_TYPE, "SCIM");
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            identity,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_SCIM_PROVISIONER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (ScimCredentialService.ScimAuthenticationException exception) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/scim+json");
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"DWP SCIM\"");
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "schemas", List.of(SCIM_ERROR_SCHEMA),
                    "status", "401",
                    "detail", "A valid SCIM provisioning credential is required."));
        } finally {
            ScimConnectorContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
