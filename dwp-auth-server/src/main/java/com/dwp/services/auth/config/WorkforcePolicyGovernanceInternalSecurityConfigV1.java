package com.dwp.services.auth.config;

import static com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1.*;
import com.dwp.platform.contracts.hris.workforce.v1.WorkforcePolicyGovernanceV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

/** Exact S1 internal endpoint; no legacy chain/JWT modification or command permission. */
@Configuration
public class WorkforcePolicyGovernanceInternalSecurityConfigV1 {
    public static final String TOKEN_HEADER="X-DWP-Workforce-Policy-Governance-Token";
    public static final String IDENTITY_HEADER="X-DWP-Service-Identity";
    private static final String OWNED_PREFIX="/internal/auth/v2/workforce-policy-governance";
    @Bean @Order(1)
    SecurityFilterChain workforcePolicyGovernanceV1(HttpSecurity http,JwtDecoder decoder,ObjectMapper mapper,
            @Value("${dwp.auth.workforce-policy-governance-token:}") String token) throws Exception {
        http.securityMatcher(request->request.getRequestURI().startsWith(OWNED_PREFIX))
                .csrf(csrf->csrf.disable())
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth.anyRequest().authenticated())
                .oauth2ResourceServer(resource->resource.bearerTokenResolver(request->{
                    String header=exact(request,"Authorization");
                    return header!=null&&header.startsWith("Bearer ")?header.substring(7):null;
                }).jwt(jwt->jwt.decoder(decoder))
                .authenticationEntryPoint((request,response,failure)->write(mapper,response,401,Code.ACTOR_JWT_INVALID)))
                .exceptionHandling(errors->errors.accessDeniedHandler((request,response,failure)->write(mapper,response,403,Code.PERMISSION_DENIED)))
                .addFilterBefore(new ServiceGate(token,mapper),BearerTokenAuthenticationFilter.class);
        return http.build();
    }
    static final class ServiceGate extends OncePerRequestFilter {
        private final String expected;
        private final ObjectMapper mapper;
        ServiceGate(String token,ObjectMapper mapper) { expected=token==null?"":token.strip();this.mapper=mapper; }
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
                throws ServletException,IOException {
            if(!PATH.equals(request.getRequestURI())){write(mapper,response,401,Code.CALLER_UNAUTHORIZED);return;}
            if(!"POST".equals(request.getMethod())){write(mapper,response,405,Code.CALLER_UNAUTHORIZED);return;}
            String actual=exact(request,TOKEN_HEADER),identity=exact(request,IDENTITY_HEADER),jwt=exact(request,"Authorization");
            boolean absent=true;
            for(String header:List.of("X-DWP-Product-Surface-Token","X-DWP-Meeting-Followup-Authority-Token",
                    "X-User-Id","X-Tenant-Id","X-DWP-User-Id","X-DWP-Tenant-Id","X-DWP-Person-Public-Id",
                    "X-DWP-Roles","X-Roles","X-DWP-Permissions","X-Permissions","X-DWP-Resource-Roles"))
                if(request.getHeaders(header).hasMoreElements())absent=false;
            if(!absent||expected.isBlank()||actual==null||!"dwp-people-server".equals(identity)
                    ||jwt==null||!jwt.startsWith("Bearer ")||jwt.length()<=7
                    ||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8))) {
                write(mapper,response,401,Code.CALLER_UNAUTHORIZED);return;
            }
            chain.doFilter(request,response);
        }
    }
    private static String exact(HttpServletRequest request,String name) {
        var values=Collections.list(request.getHeaders(name));
        if(values.size()!=1||values.getFirst()==null||values.getFirst().isBlank()
                ||!values.getFirst().equals(values.getFirst().strip()))return null;
        return values.getFirst();
    }
    private static void write(ObjectMapper mapper,HttpServletResponse response,int status,Code code)throws IOException {
        response.setStatus(status);response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(),WorkforcePolicyGovernanceV1.denied(code));
    }
}
