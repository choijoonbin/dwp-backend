package com.dwp.services.people.directory;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.people.security.PeopleRequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class PeopleDataAccessAuditFilter extends OncePerRequestFilter {

    private final AuditOutboxRecorder audit;

    public PeopleDataAccessAuditFilter(AuditOutboxRecorder audit) {
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod())
                || !request.getRequestURI().startsWith("/v1/people");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        filterChain.doFilter(request, response);
        if (response.getStatus() < 200 || response.getStatus() >= 400) return;
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        String suffix = request.getRequestURI().substring("/v1/people".length());
        boolean detail = suffix.length() > 1;
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("DATA_ACCESS")
                .action(detail ? "people.profile.viewed" : "people.directory.searched")
                .outcome("SUCCESS")
                .severity(detail ? "LOW" : "INFO")
                .riskScore(detail ? 25 : 15)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("people-directory")
                .targetType(detail ? "PERSON_PROFILE" : "PEOPLE_DIRECTORY")
                .targetId(detail ? suffix.substring(1) : "search")
                .correlationId(request.getHeader("X-Correlation-ID"))
                .metadata(Map.of(
                        "queryProvided", request.getParameter("query") != null,
                        "asOfProvided", request.getParameter("asOf") != null))
                .retentionClass(detail ? "EXTENDED" : "STANDARD")
                .build());
    }
}
