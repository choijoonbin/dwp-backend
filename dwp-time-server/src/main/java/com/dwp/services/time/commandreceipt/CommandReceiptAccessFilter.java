package com.dwp.services.time.commandreceipt;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.api.HrisTimeApiException;
import com.dwp.services.time.api.HrisTimeProblemCode;
import com.dwp.services.time.api.HrisTimeProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared fail-closed receipt boundary that never selects a storage provider. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 24)
@ConditionalOnExpression(
        "'${dwp.hris.time.timecard.enabled:false}' == 'true'"
                + " || '${dwp.hris.time.close.enabled:false}' == 'true'")
public final class CommandReceiptAccessFilter extends OncePerRequestFilter {

    public static final String VERIFIED_REQUEST_ATTRIBUTE =
            "com.dwp.services.time.commandreceipt.verified-request";
    private static final Pattern EXACT_ROUTE = Pattern.compile(
            "^/v1/command-receipts/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z][a-z0-9.-]{0,63}");

    private final List<CommandReceiptAuthorityProvider> authorityProviders;
    private final HrisTimeProblemWriter problemWriter;
    private final Clock clock;

    @Autowired
    public CommandReceiptAccessFilter(
            List<CommandReceiptAuthorityProvider> authorityProviders,
            HrisTimeProblemWriter problemWriter) {
        this(authorityProviders, problemWriter, Clock.systemUTC());
    }

    CommandReceiptAccessFilter(
            List<CommandReceiptAuthorityProvider> authorityProviders,
            HrisTimeProblemWriter problemWriter,
            Clock clock) {
        this.authorityProviders = List.copyOf(Objects.requireNonNull(
                authorityProviders, "authorityProviders"));
        this.problemWriter = Objects.requireNonNull(problemWriter, "problemWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
        long distinct = this.authorityProviders.stream()
                .map(CommandReceiptAuthorityProvider::providerId).distinct().count();
        if (distinct != this.authorityProviders.size()
                || this.authorityProviders.stream().anyMatch(provider ->
                    provider.providerId() == null
                            || !PROVIDER_ID.matcher(provider.providerId()).matches())) {
            throw new IllegalArgumentException(
                    "command-receipt authority provider ids must be unique");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/command-receipts/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!"GET".equals(request.getMethod())
                || !EXACT_ROUTE.matcher(request.getRequestURI()).matches()) {
            deny(request, response, HrisTimeProblemCode.SCHEMA_INVALID);
            return;
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        CommandReceiptAuthority common = null;
        try {
            for (CommandReceiptAuthorityProvider provider : authorityProviders) {
                CommandReceiptAuthority authority = provider.verify(request).orElse(null);
                if (authority == null) continue;
                if (common != null && !sameEnvelope(common, authority)) {
                    throw new BaseException(
                            ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                            "Command-receipt authority projections disagree");
                }
                if (common == null || authority.revalidateAt().isBefore(common.revalidateAt())) {
                    common = authority;
                }
                evidence.put(provider.providerId(), authority.providerEvidence());
            }
        } catch (BaseException failure) {
            deny(request, response, HrisTimeProblemCode.AUTHORITY_UNAVAILABLE);
            return;
        } catch (RuntimeException unavailable) {
            deny(request, response, HrisTimeProblemCode.AUTHORITY_UNAVAILABLE);
            return;
        }
        if (common == null || evidence.isEmpty()
                || !common.revalidateAt().isAfter(clock.instant())) {
            deny(request, response, HrisTimeProblemCode.AUTHORIZATION_DENIED);
            return;
        }

        VerifiedCommandReceiptRequest verified = new VerifiedCommandReceiptRequest(
                common.tenantId(), common.correlationId(), common.decisionRevision(),
                common.revalidateAt(), evidence);
        request.setAttribute(VERIFIED_REQUEST_ATTRIBUTE, verified);
        response.setHeader("X-DWP-Decision-Revision", verified.decisionRevision());
        filterChain.doFilter(request, response);
    }

    private static boolean sameEnvelope(
            CommandReceiptAuthority left, CommandReceiptAuthority right) {
        return left.tenantId() == right.tenantId()
                && left.correlationId().equals(right.correlationId())
                && left.decisionRevision().equals(right.decisionRevision());
    }

    private void deny(
            HttpServletRequest request,
            HttpServletResponse response,
            HrisTimeProblemCode code) throws IOException {
        problemWriter.write(request, response, new HrisTimeApiException(code));
    }
}
