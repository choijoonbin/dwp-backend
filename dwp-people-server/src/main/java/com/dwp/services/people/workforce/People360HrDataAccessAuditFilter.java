package com.dwp.services.people.workforce;

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
import java.util.Set;

/** Audits the People 360 projection exposed through the personal and team HR surfaces. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 22)
public final class People360HrDataAccessAuditFilter extends OncePerRequestFilter {

    private static final String PROJECTION = "people360";
    private static final String HOME_PATH = "/v1/hr/home";
    private static final String TEAM_PATH = "/v1/hr/team";
    private static final Set<String> AUDITED_PATHS = Set.of(HOME_PATH, TEAM_PATH);

    private final AuditOutboxRecorder audit;

    public People360HrDataAccessAuditFilter(AuditOutboxRecorder audit) {
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod())
                || !AUDITED_PATHS.contains(request.getRequestURI())
                || !PROJECTION.equals(request.getParameter("projection"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);
        if (response.getStatus() < 200 || response.getStatus() >= 300) return;

        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        boolean self = HOME_PATH.equals(request.getRequestURI());
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("DATA_ACCESS")
                .action("people.profile.viewed")
                .outcome("SUCCESS")
                .severity("LOW")
                .riskScore(25)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("workforce-people")
                .targetType("PERSON_PROFILE")
                .targetId(targetId(request, actor, self))
                .correlationId(request.getHeader("X-Correlation-ID"))
                .metadata(Map.of(
                        "projection", PROJECTION,
                        "asOfProvided", request.getParameter("asOf") != null,
                        "accessSurface", self ? "HR_SELF" : "HR_TEAM"))
                .retentionClass("EXTENDED")
                .build());
    }

    private String targetId(
            HttpServletRequest request,
            PeopleRequestContext.Actor actor,
            boolean self) {
        if (self) {
            return actor.personPublicId() == null
                    ? "self:user:" + actor.userId()
                    : actor.personPublicId().toString();
        }
        String target = request.getParameter("personId");
        return target == null || target.isBlank() ? "team-profile" : target.trim();
    }
}
