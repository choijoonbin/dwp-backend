package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.security.RolePlaneBoundary;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
@ConditionalOnExpression(
        "'${dwp.hris.explorer.enabled:false}' == 'true' || "
                + "'${dwp.hris.controlled-transfer.enabled:false}' == 'true'")
final class HrisExplorerOwnerEvidenceFilter extends OncePerRequestFilter {

    private static final String SERVICE_TOKEN = "X-DWP-Service-Token";
    private static final String TENANT = "X-DWP-Tenant-ID";
    private static final String ACTOR = "X-DWP-User-ID";
    private static final String SUBJECT = "X-DWP-Person-Public-ID";
    private static final String ACTIVE_MODE = "X-DWP-Active-Access-Mode";
    private static final String DECISION = "X-DWP-Current-Decision-Revision";
    private static final String ROUTE = "X-DWP-Route-Contract-Key";
    private static final String CONTEXT = "X-DWP-Context-Key";
    private static final String CONTEXT_SCOPE = "X-DWP-Context-Scope-Key";
    private static final String REVALIDATE_AT = "X-DWP-Current-Revalidate-At";
    private static final Pattern RECEIPT_PATH = Pattern.compile(
            "^/v1/admin/hris/command-receipts/([0-9a-fA-F-]{36})$");

    private final String configuredServiceToken;
    private final HrisExplorerOwnerEvidenceAdapter adapter;
    private final HrisPlatformReceiptAuthorityAdapter platformReceiptAuthority;
    private final HcmPlatformPepRegistry hcmPlatformPepRegistry;
    private final boolean explorerEnabled;

    @Autowired
    HrisExplorerOwnerEvidenceFilter(
            @Value("${dwp.platform.service-token:}") String configuredServiceToken,
            HrisExplorerOwnerEvidenceAdapter adapter,
            HrisPlatformReceiptAuthorityAdapter platformReceiptAuthority,
            HcmPlatformPepRegistry hcmPlatformPepRegistry,
            @Value("${dwp.hris.explorer.enabled:false}") boolean explorerEnabled) {
        this.configuredServiceToken = configuredServiceToken == null
                ? "" : configuredServiceToken.strip();
        this.adapter = adapter;
        this.platformReceiptAuthority = platformReceiptAuthority;
        this.hcmPlatformPepRegistry = hcmPlatformPepRegistry;
        this.explorerEnabled = explorerEnabled;
    }

    HrisExplorerOwnerEvidenceFilter(
            String configuredServiceToken,
            HrisExplorerOwnerEvidenceAdapter adapter,
            HrisPlatformReceiptAuthorityAdapter platformReceiptAuthority,
            HcmPlatformPepRegistry hcmPlatformPepRegistry) {
        this(configuredServiceToken, adapter, platformReceiptAuthority,
                hcmPlatformPepRegistry, true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (RECEIPT_PATH.matcher(path).matches()) return false;
        return !explorerEnabled
                || !(path.equals("/v1/hris/explorer")
                || path.equals("/v1/hris/explorer/preferences")
                || path.equals("/v1/hris/explorer/recents"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "private, no-store, max-age=0");
        String provided = single(request, SERVICE_TOKEN);
        if (configuredServiceToken.isBlank()
                || provided == null
                || !MessageDigest.isEqual(
                configuredServiceToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        Long tenantId = positiveLong(single(request, TENANT));
        Long actorId = positiveLong(single(request, ACTOR));
        UUID subject = uuid(single(request, SUBJECT));
        String activeMode = single(request, ACTIVE_MODE);
        String decision = single(request, DECISION);
        String trustedRoute = single(request, ROUTE);
        String context = single(request, CONTEXT);
        String contextScope = single(request, CONTEXT_SCOPE);
        java.time.OffsetDateTime revalidateAt = instant(single(request, REVALIDATE_AT));
        if (tenantId == null || actorId == null || subject == null
                || !java.util.Set.of("NORMAL", "ELEVATED").contains(activeMode)
                || decision == null || !decision.matches("^psr-[a-f0-9]{64}$")
                || !expectedRoute(request).equals(trustedRoute)
                || context == null || !context.matches("^psc-[a-f0-9]{64}$")
                || contextScope == null || contextScope.length() > 500
                || revalidateAt == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        UUID receiptId = receiptId(request.getRequestURI());
        String requestPurpose = receiptId == null
                ? "HRIS_APP_DISCOVERY" : "COMMAND_STATUS";
        HrisExplorerAuthoritySource.AuthorityRequest authorityRequest =
                new HrisExplorerAuthoritySource.AuthorityRequest(
                        tenantId, actorId, subject, activeMode, decision,
                        trustedRoute, context, contextScope, requestPurpose, revalidateAt);
        Optional<HrisExplorerOwnerEvidenceAdapter.ResolvedEvidence> requestEvidence =
                Optional.empty();
        if (explorerEnabled) {
            try {
                requestEvidence = adapter.resolveRequest(authorityRequest);
            } catch (RuntimeException unavailable) {
                if (receiptId == null) {
                    response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    return;
                }
                // Receipt profiles are independent. An unavailable Explorer-origin
                // proof must not suppress the typed Platform-origin proof; the latter
                // still rejects Explorer actions and every origin-seal mismatch.
                requestEvidence = Optional.empty();
            }
        }
        if (receiptId == null) {
            if (requestEvidence.isEmpty()) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
            attachExplorer(request, requestEvidence.get());
            filterChain.doFilter(request, response);
            return;
        }

        if (requestEvidence.isPresent()) {
            Optional<HrisExplorerOwnerEvidenceAdapter.ResolvedEvidence> explorerReceipt;
            try {
                explorerReceipt = adapter.resolveReceipt(
                        authorityRequest, receiptId, requestEvidence.get());
            } catch (RuntimeException unavailable) {
                explorerReceipt = Optional.empty();
            }
            if (explorerReceipt.isPresent()) {
                attachExplorer(request, explorerReceipt.get());
                filterChain.doFilter(request, response);
                return;
            }
        }

        Optional<HrisPlatformReceiptAuthorityAdapter.VerifiedReceiptAuthority> platform;
        try {
            platform = platformReceiptAuthority.resolve(
                    authorityRequest, receiptId,
                    requestEvidence.map(
                            HrisExplorerOwnerEvidenceAdapter.ResolvedEvidence::snapshot)
                            .orElse(null));
        } catch (RuntimeException unavailable) {
            platform = Optional.empty();
        }
        if (platform.isPresent()) {
            if (!platformReceiptPepAllowed(request, authorityRequest)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            request.setAttribute(
                    HrisPlatformReceiptAuthorityAdapter.VERIFIED_ATTRIBUTE,
                    platform.get());
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private boolean platformReceiptPepAllowed(
            HttpServletRequest request,
            HrisExplorerAuthoritySource.AuthorityRequest authorityRequest) {
        Set<String> roles = commaValues(request.getHeader("X-DWP-Roles"));
        String supportSession = request.getHeader("X-DWP-Support-Session-ID");
        if (!"TENANT".equals(request.getHeader("X-DWP-Identity-Plane"))
                || (supportSession != null && !supportSession.isBlank())
                || RolePlaneBoundary.hasConflict(roles)
                || RolePlaneBoundary.isProviderIdentity(roles)) {
            return false;
        }
        HcmPlatformPepRegistry.Decision decision = hcmPlatformPepRegistry.authorize(
                new HcmPlatformPepRegistry.RequestEvidence(
                        request.getMethod(), request.getRequestURI(),
                        commaValues(request.getHeader("X-DWP-Permissions")),
                        roles,
                        commaValues(request.getHeader("X-DWP-Resource-Roles")),
                        authorityRequest.currentDecisionRevision(),
                        authorityRequest.contextKey(),
                        authorityRequest.contextScopeKey(),
                        null,
                        authorityRequest.trustedRouteContractKey()));
        return decision.allowed()
                && decision.authority() != null
                && "platform-originating-command-receipt-reader".equals(
                decision.authority().profileKey());
    }

    private Set<String> commaValues(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return java.util.Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private void attachExplorer(
            HttpServletRequest request,
            HrisExplorerOwnerEvidenceAdapter.ResolvedEvidence resolved) {
        request.setAttribute(HrisExplorerOwnerEvidenceAdapter.SNAPSHOT_ATTRIBUTE,
                resolved.snapshot());
        request.setAttribute(HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE,
                resolved.ownerEvidence());
    }

    private String single(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= 500 && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0 ? value : null;
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private java.time.OffsetDateTime instant(String value) {
        try {
            return value == null ? null : java.time.OffsetDateTime.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private UUID receiptId(String path) {
        Matcher matcher = RECEIPT_PATH.matcher(path);
        if (!matcher.matches()) return null;
        return uuid(matcher.group(1));
    }

    private String expectedRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("GET".equals(request.getMethod()) && path.equals("/v1/hris/explorer")) {
            return "route.hcm.utility.explorer.page";
        }
        if ("PUT".equals(request.getMethod())
                && path.equals("/v1/hris/explorer/preferences")) {
            return "route.hcm.utility.explorer-preferences-update.action";
        }
        if ("POST".equals(request.getMethod())
                && path.equals("/v1/hris/explorer/recents")) {
            return "route.hcm.utility.explorer-recent-create.action";
        }
        return receiptId(path) != null && "GET".equals(request.getMethod())
                ? "route.hcm.utility.explorer-command-receipt.data" : "";
    }

}
