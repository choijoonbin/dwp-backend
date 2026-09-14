package com.dwp.services.auth.retentionexecutionauthority;

import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import com.dwp.core.common.*;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class RetentionExecutionSecurityConfiguration {
    @Bean @Order(-12) SecurityFilterChain retentionExecutionSecurityFilterChain(HttpSecurity http,RetentionExecutionAuthorityService service,
            ObjectMapper mapper,@Value("${dwp.auth.approval-retention-execution.enabled:false}") boolean enabled) throws Exception {
        http.securityMatcher(request->request.getRequestURI().contains("retention-execution-authority")).csrf(csrf->csrf.disable())
                .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth->auth.anyRequest().permitAll())
                .addFilterBefore(new PurposeFilter(service,mapper,enabled),AnonymousAuthenticationFilter.class);
        return http.build();
    }
    public static final class PurposeFilter extends OncePerRequestFilter {
        private final RetentionExecutionAuthorityService service;private final ObjectMapper mapper;private final boolean enabled;
        public PurposeFilter(RetentionExecutionAuthorityService service,ObjectMapper mapper,boolean enabled) {this.service=service;this.mapper=mapper;this.enabled=enabled;}
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            if(!enabled) {error(response,ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);return;}
            if(!"POST".equals(request.getMethod()) || !PATH.equals(request.getRequestURI()) || request.getQueryString()!=null
                    || request.getHeader("Cookie")!=null || request.getHeader("Authorization")!=null
                    || !"dwp-approval-server".equals(single(request,"X-DWP-Service-Identity")) || borrowed(request) || !json(request)) {
                error(response,ErrorCode.FORBIDDEN);return;
            }
            String transport=single(request,HEADER);
            if(transport==null || transport.length()>TRANSPORT_LIMIT || request.getContentLengthLong()>BODY_LIMIT) {error(response,ErrorCode.FORBIDDEN);return;}
            try {byte[] body=request.getInputStream().readNBytes(BODY_LIMIT+1);request.setAttribute(RetentionExecutionController.PROOF_ATTRIBUTE,service.preverify(body,transport));}
            catch(BaseException invalid) {error(response,invalid.getErrorCode());return;}
            catch(RuntimeException unavailable) {error(response,ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);return;}
            chain.doFilter(request,response);
        }
        private boolean borrowed(HttpServletRequest request) {
            return Collections.list(request.getHeaderNames()).stream().map(name->name.toLowerCase(Locale.ROOT))
                    .anyMatch(name->name.startsWith("x-dwp-") && !Set.of("x-dwp-service-identity",HEADER.toLowerCase(Locale.ROOT)).contains(name));
        }
        private String single(HttpServletRequest request,String name) {var values=Collections.list(request.getHeaders(name));return values.size()==1?values.getFirst():null;}
        private boolean json(HttpServletRequest request) {
            try {var values=Collections.list(request.getHeaders("Content-Type"));if(values.size()!=1) return false;var type=MediaType.parseMediaType(values.getFirst());
                return "application".equals(type.getType()) && "json".equals(type.getSubtype()) && type.getParameters().keySet().stream().allMatch("charset"::equals)
                        && (type.getCharset()==null || java.nio.charset.StandardCharsets.UTF_8.equals(type.getCharset()));}
            catch(IllegalArgumentException invalid) {return false;}
        }
        private void error(HttpServletResponse response,ErrorCode code) throws IOException {
            response.setStatus(code.getHttpStatus().value());response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),ApiResponse.error(code,"Current managed retention execution authority is required."));
        }
    }
}
