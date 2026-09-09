package com.dwp.services.platform.security;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owner-service PEP for the Platform-owned DWAI-ON Agent registry routes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 32)
public final class DwaionAgentProductSurfacePepFilter extends OncePerRequestFilter {

    static final String ROUTE_HEADER = "X-DWP-Route-Contract-Key";
    static final String ROLLOUT_STATE_HEADER = "X-DWP-Rollout-State";
    static final String ROLLOUT_REVISION_HEADER = "X-DWP-Rollout-Revision";
    static final String ROLLOUT_COHORT_HEADER = "X-DWP-Rollout-Cohort";
    static final String ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    static final String CONTEXT_HEADER = "X-DWP-Context-Key";
    static final String SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    static final String CURRENT_REVISION_HEADER = "X-DWP-Current-Decision-Revision";
    static final String REVALIDATE_AT_HEADER = "X-DWP-Current-Revalidate-At";
    static final String EXPECTED_REVISION_HEADER = "X-DWP-Expected-Decision-Revision";
    static final String RESPONSE_REVISION_HEADER = "X-DWP-Decision-Revision";

    private static final String PREFIX = "/v1/admin/dwaion/agents";
    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> ROLLOUT_COHORTS = Set.of(
            "baseline", "holdout", "full", "eligible-10", "eligible-25",
            "eligible-50", "eligible-90");
    private static final Set<String> ACCESS_MODES = Set.of("NORMAL", "ELEVATED");
    private static final Pattern CONTEXT = Pattern.compile("psc-[a-f0-9]{64}");
    private static final Pattern REVISION = Pattern.compile("psr-[a-f0-9]{64}");
    private static final Pattern ROLLOUT_REVISION = Pattern.compile("rollout-[a-f0-9]{64}");
    private static final Pattern ENTRY_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");
    private static final Pattern POSITIVE_REVISION = Pattern.compile("[1-9][0-9]*");

    private static final List<BindingPattern> BINDINGS = List.of(
            binding("GET", PREFIX, "route.dwaion.management.agents.page", "VIEW", false),
            binding("GET", PREFIX + "/{entryKey}",
                    "route.dwaion.management.agent-detail.data", "VIEW", false),
            binding("POST", PREFIX, "route.dwaion.management.agent-create.action", "CREATE", true),
            binding("POST", PREFIX + "/{entryKey}/revisions",
                    "route.dwaion.management.agent-revision-create.action", "UPDATE", true),
            binding("PATCH", PREFIX + "/{entryKey}/revisions/{revisionNumber}",
                    "route.dwaion.management.agent-revision-update.action", "UPDATE", true),
            binding("POST", PREFIX + "/{entryKey}/revisions/{revisionNumber}/activate",
                    "route.dwaion.management.agent-revision-activate.action", "APPROVE", true),
            binding("POST", PREFIX + "/{entryKey}/revisions/{revisionNumber}/retire",
                    "route.dwaion.management.agent-revision-retire.action", "MANAGE", true));

    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public DwaionAgentProductSurfacePepFilter(
            @Value("${dwp.platform.product-authorization-dwaion-v6-enabled:false}")
            boolean enabled,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        boolean rolloutPresent = request.getHeader(ROLLOUT_STATE_HEADER) != null;
        String rolloutState = exactHeader(request, ROLLOUT_STATE_HEADER);
        if (!rolloutPresent && !enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!validRollout(
                rolloutState,
                exactHeader(request, ROLLOUT_REVISION_HEADER),
                exactHeader(request, ROLLOUT_COHORT_HEADER))) {
            reject(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted DWAI-ON rollout evidence is invalid.");
            return;
        }
        if (rolloutState.charAt(1) == '0') {
            filterChain.doFilter(request, response);
            return;
        }
        if (!enabled) {
            reject(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "DWAI-ON product authorization v6 is not ready for enforcement.");
            return;
        }

        Binding binding = resolve(request.getMethod(), request.getRequestURI());
        if (binding == null || !binding.routeKey().equals(exactHeader(request, ROUTE_HEADER))) {
            reject(response, ErrorCode.FORBIDDEN,
                    "The exact DWAI-ON Agent registry route authority is required.");
            return;
        }
        Long tenantId = positiveLong(exactHeader(request, "X-DWP-Tenant-ID"));
        Long userId = positiveLong(exactHeader(request, "X-DWP-User-ID"));
        Set<String> roles = exactValues(request, "X-DWP-Roles");
        Set<String> permissions = exactValues(request, "X-DWP-Permissions");
        String accessMode = exactHeader(request, ACCESS_MODE_HEADER);
        String context = exactHeader(request, CONTEXT_HEADER);
        String selectedScope = exactHeader(request, SCOPE_HEADER);
        if (tenantId == null || userId == null
                || !"TENANT".equals(exactHeader(request, "X-DWP-Identity-Plane"))
                || roles == null || roles.stream().anyMatch(role -> role.startsWith("PROVIDER_"))
                || permissions == null
                || !permissions.contains("ADMIN.DWAION_AGENTS:" + binding.permission())
                || !ACCESS_MODES.contains(accessMode)
                || request.getHeader("X-DWP-Support-Session-ID") != null
                || context == null || !CONTEXT.matcher(context).matches()
                || !validScope(selectedScope, tenantId, userId)) {
            reject(response, ErrorCode.FORBIDDEN,
                    "The selected DWAI-ON Agent registry authority is not valid.");
            return;
        }

        String currentRevision = exactHeader(request, CURRENT_REVISION_HEADER);
        OffsetDateTime revalidateAt = instant(exactHeader(request, REVALIDATE_AT_HEADER));
        if (currentRevision == null || !REVISION.matcher(currentRevision).matches()
                || revalidateAt == null || !revalidateAt.isAfter(OffsetDateTime.now())) {
            reject(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Trusted current DWAI-ON authority is missing or expired.");
            return;
        }
        if (binding.action()
                && !currentRevision.equals(exactHeader(request, EXPECTED_REVISION_HEADER))) {
            reject(response, ErrorCode.DECISION_REVISION_CONFLICT,
                    "DWAI-ON authority changed after the client decision.");
            return;
        }

        response.setHeader(RESPONSE_REVISION_HEADER, currentRevision);
        filterChain.doFilter(request, response);
    }

    private boolean validScope(String value, long tenantId, long userId) {
        if (value == null) return false;
        return value.equals(ProductSurfaceScopeKey.key(
                tenantId, userId, "dwaion", "dwaion.management",
                "APP_RESOURCE_SET:RS_DWAION", "RESOURCE_SET"))
                || value.equals(ProductSurfaceScopeKey.resourceSet(
                        tenantId, userId, "dwaion", "dwaion.management", "RS_DWAION"));
    }

    private boolean validRollout(String state, String revision, String cohort) {
        return state != null && ROLLOUT_STATES.contains(state)
                && revision != null && ROLLOUT_REVISION.matcher(revision).matches()
                && cohort != null && ROLLOUT_COHORTS.contains(cohort);
    }

    private Binding resolve(String method, String path) {
        for (BindingPattern binding : BINDINGS) {
            Binding value = binding.resolve(method, path);
            if (value != null) return value;
        }
        return null;
    }

    private Set<String> exactValues(HttpServletRequest request, String name) {
        Enumeration<String> headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements()) return Set.of();
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value == null || value.isBlank()
                || !value.equals(value.trim()) || value.length() > 4_000
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return null;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String token : Arrays.asList(value.split(",", -1))) {
            if (token.isBlank() || !token.equals(token.trim())
                    || !token.equals(token.toUpperCase(Locale.ROOT)) || !values.add(token)) {
                return null;
            }
        }
        return Set.copyOf(values);
    }

    private String exactHeader(HttpServletRequest request, String name) {
        Enumeration<String> headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements()) return null;
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value == null || value.isBlank()
                || value.length() > 200 || !value.equals(value.trim())
                || value.indexOf(',') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) return null;
        return value;
    }

    private Long positiveLong(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) return null;
        try {
            long result = Long.parseLong(value);
            return result > 0 ? result : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private OffsetDateTime instant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private void reject(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }

    private static BindingPattern binding(
            String method, String path, String routeKey, String permission, boolean action) {
        return new BindingPattern(method, path, routeKey, permission, action);
    }

    private record Binding(String routeKey, String permission, boolean action) {
    }

    private record BindingPattern(
            String method,
            Pattern pattern,
            String routeKey,
            String permission,
            boolean action,
            List<String> parameters) {

        private BindingPattern(
                String method, String path, String routeKey, String permission, boolean action) {
            this(method, compile(path), routeKey, permission, action, parameterNames(path));
        }

        private Binding resolve(String candidateMethod, String path) {
            if (!method.equals(candidateMethod)) return null;
            Matcher matcher = pattern.matcher(path);
            if (!matcher.matches()) return null;
            for (int index = 0; index < parameters.size(); index++) {
                String name = parameters.get(index);
                String value = matcher.group(index + 1);
                if (("entryKey".equals(name) && !ENTRY_KEY.matcher(value).matches())
                        || ("revisionNumber".equals(name)
                        && !POSITIVE_REVISION.matcher(value).matches())) return null;
            }
            return new Binding(routeKey, permission, action);
        }

        private static Pattern compile(String path) {
            StringBuilder expression = new StringBuilder("^");
            int cursor = 0;
            Matcher matcher = Pattern.compile("\\{([^{}]+)}").matcher(path);
            while (matcher.find()) {
                expression.append(Pattern.quote(path.substring(cursor, matcher.start())));
                expression.append("([^/]+)");
                cursor = matcher.end();
            }
            expression.append(Pattern.quote(path.substring(cursor))).append('$');
            return Pattern.compile(expression.toString());
        }

        private static List<String> parameterNames(String path) {
            Matcher matcher = Pattern.compile("\\{([^{}]+)}").matcher(path);
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            while (matcher.find()) values.add(matcher.group(1));
            return List.copyOf(values);
        }
    }
}
