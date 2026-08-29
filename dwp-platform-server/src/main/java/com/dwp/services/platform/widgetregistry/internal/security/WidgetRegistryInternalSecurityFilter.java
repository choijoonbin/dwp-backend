package com.dwp.services.platform.widgetregistry.internal.security;

import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryCommandTrustPolicy.Requirement;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryInternalRoutes.Match;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryInternalRoutes.Resolution;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryInternalRoutes.ResolutionStatus;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryRequestBinding.BindingException;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryRequestBinding.PreparedRequest;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.AssertionReplayStore;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ProviderAssertionClaims;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ProviderAssertionVerifier;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ReplayDecision;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ReplayKey;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ServiceTokenClaims;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.ServiceTokenVerifier;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.VerificationException;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.VerificationFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Receiver-first dual-proof trust boundary for exact internal Widget Registry routes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WidgetRegistryInternalSecurityFilter extends OncePerRequestFilter {

    static final String SERVICE_TOKEN_HEADER = "Authorization";
    static final String WIDGET_ASSERTION_HEADER = "X-DWP-Widget-Assertion";
    static final String RECONCILE_ASSERTION_HEADER = "X-DWP-Widget-Reconcile-Assertion";
    static final String PROVISIONING_TOKEN_HEADER = "X-DWP-Provisioning-Token";
    private static final Pattern COMPACT_JWS = Pattern.compile(
            "^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final int MAX_SERVICE_TOKEN_LENGTH = 8192;
    private static final int MAX_PROVIDER_ASSERTION_LENGTH = 16_384;
    private final ObjectMapper objectMapper;
    private final WidgetRegistryRequestBinding requestBinding;
    private final ServiceTokenVerifier serviceTokenVerifier;
    private final ProviderAssertionVerifier assertionVerifier;
    private final AssertionReplayStore replayStore;
    private final BooleanSupplier requestPermit;
    private final Clock clock;

    @Autowired
    public WidgetRegistryInternalSecurityFilter(
            ObjectMapper objectMapper,
            WidgetRegistryActivationInterlock activationInterlock) {
        this(
                objectMapper,
                null,
                null,
                null,
                activationInterlock::permitsRequest,
                Clock.systemUTC());
    }

    WidgetRegistryInternalSecurityFilter(
            ObjectMapper objectMapper,
            ServiceTokenVerifier serviceTokenVerifier,
            ProviderAssertionVerifier assertionVerifier,
            AssertionReplayStore replayStore,
            BooleanSupplier requestPermit,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.requestBinding = new WidgetRegistryRequestBinding(objectMapper);
        this.serviceTokenVerifier = serviceTokenVerifier;
        this.assertionVerifier = assertionVerifier;
        this.replayStore = replayStore;
        this.requestPermit = Objects.requireNonNull(requestPermit, "requestPermit");
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !WidgetRegistryInternalRoutes.isPlanePath(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Resolution resolution = WidgetRegistryInternalRoutes.resolve(request.getRequestURI(), request.getMethod());
        if (resolution.status() == ResolutionStatus.NOT_FOUND) {
            writeError(response, request, WidgetRegistryIngressFailure.ROUTE_NOT_FOUND);
            return;
        }
        if (resolution.status() == ResolutionStatus.METHOD_NOT_ALLOWED) {
            response.setHeader("Allow", String.join(", ", resolution.allowedMethods()));
            writeError(response, request, WidgetRegistryIngressFailure.METHOD_NOT_ALLOWED);
            return;
        }
        Match match = resolution.match();
        if (!request.isSecure()) {
            writeError(response, request, WidgetRegistryIngressFailure.TLS_REQUIRED);
            return;
        }
        if (!headerValues(request, PROVISIONING_TOKEN_HEADER).isEmpty()) {
            writeError(response, request, WidgetRegistryIngressFailure.PROVISIONING_TOKEN_FORBIDDEN);
            return;
        }
        if (hasUnexpectedDwpHeader(request, match)) {
            writeError(response, request, WidgetRegistryIngressFailure.AUTHORITY_HEADERS_FORBIDDEN);
            return;
        }
        if (!permitsRequest()) {
            writeError(response, request, WidgetRegistryIngressFailure.TRUST_UNAVAILABLE);
            return;
        }
        ProofHeaders proofHeaders = proofHeaders(request, match);
        if (proofHeaders == null) {
            writeError(response, request, WidgetRegistryIngressFailure.DUAL_PROOF_REQUIRED);
            return;
        }
        if (serviceTokenVerifier == null || assertionVerifier == null || replayStore == null) {
            writeError(response, request, WidgetRegistryIngressFailure.TRUST_UNAVAILABLE);
            return;
        }

        ServiceTokenClaims serviceToken;
        try {
            serviceToken = serviceTokenVerifier.verify(proofHeaders.serviceToken());
        } catch (VerificationException exception) {
            writeVerificationError(response, request, exception, WidgetRegistryIngressFailure.SERVICE_TOKEN_INVALID);
            return;
        } catch (RuntimeException exception) {
            writeError(response, request, WidgetRegistryIngressFailure.TRUST_UNAVAILABLE);
            return;
        }
        ProviderAssertionClaims assertion;
        try {
            assertion = assertionVerifier.verify(proofHeaders.assertion(), match.route().assertionKind());
        } catch (VerificationException exception) {
            writeVerificationError(response, request, exception, WidgetRegistryIngressFailure.ASSERTION_INVALID);
            return;
        } catch (RuntimeException exception) {
            writeError(response, request, WidgetRegistryIngressFailure.TRUST_UNAVAILABLE);
            return;
        }

        PreparedRequest prepared;
        try {
            prepared = requestBinding.prepare(request, match);
        } catch (BindingException exception) {
            writeError(response, request, exception.failure());
            return;
        }

        Requirement requirement = requirement(match, prepared, assertion);
        if (requirement == null) {
            writeError(response, request, WidgetRegistryIngressFailure.ASSERTION_INVALID);
            return;
        }
        Instant now = clock.instant();
        if (!WidgetRegistryClaimValidator.validServiceToken(serviceToken, requirement.serviceScope(), now)) {
            writeError(response, request, WidgetRegistryIngressFailure.SERVICE_TOKEN_INVALID);
            return;
        }
        if (!WidgetRegistryClaimValidator.validAssertion(
                assertion,
                serviceToken,
                prepared.binding(),
                requirement.providerPermission(),
                match.route().assertionPurpose(),
                now)) {
            writeError(response, request, WidgetRegistryIngressFailure.ASSERTION_INVALID);
            return;
        }
        if (!validSealArtifactBinding(prepared, assertion)) {
            writeError(response, request, WidgetRegistryIngressFailure.ASSERTION_INVALID);
            return;
        }

        ReplayDecision replayDecision;
        try {
            replayDecision = replayStore.claim(
                    new ReplayKey(
                            assertion.identity().issuer(),
                            assertion.identity().subject(),
                            assertion.identity().jwtId()),
                    now.plus(5, ChronoUnit.MINUTES));
        } catch (RuntimeException exception) {
            replayDecision = ReplayDecision.UNAVAILABLE;
        }
        if (replayDecision == ReplayDecision.UNAVAILABLE || replayDecision == null) {
            writeError(response, request, WidgetRegistryIngressFailure.TRUST_UNAVAILABLE);
            return;
        }
        if (replayDecision == ReplayDecision.REPLAYED) {
            writeError(response, request, WidgetRegistryIngressFailure.ASSERTION_REPLAYED);
            return;
        }

        prepared.request().setAttribute(
                WidgetRegistryTrustedRequestContext.REQUEST_ATTRIBUTE,
                trustedContext(match, requirement, serviceToken, assertion, prepared));
        filterChain.doFilter(prepared.request(), response);
    }

    private Requirement requirement(
            Match match,
            PreparedRequest prepared,
            ProviderAssertionClaims assertion) {
        if (assertion == null) return null;
        if (match.route() == WidgetRegistryInternalRoutes.Route.EXECUTE_COMMAND) {
            if (prepared.command() == null
                    || !Objects.equals(assertion.operationId(), prepared.command().operationId())
                    || !Objects.equals(assertion.commandType(), prepared.command().commandType())
                    || !requestBinding.matchesSignedCommand(prepared.command(), assertion)) {
                return null;
            }
            return WidgetRegistryCommandTrustPolicy.resolve(
                    prepared.command().operationId(),
                    prepared.command().commandType(),
                    prepared.command().target(),
                    prepared.command().semanticFields());
        }
        if (match.route().assertionKind() == WidgetRegistryTrustPorts.AssertionKind.WIDGET
                && (assertion.operationId() != null
                || assertion.commandType() != null
                || assertion.command() != null)) {
            return null;
        }
        return new Requirement(match.route().serviceScope(), match.route().providerPermission());
    }

    private static boolean validSealArtifactBinding(
            PreparedRequest prepared,
            ProviderAssertionClaims assertion) {
        if (prepared.seal() == null) return true;
        return Objects.equals(prepared.seal().reconcile(), assertion.reconcile());
    }

    private static WidgetRegistryTrustedRequestContext trustedContext(
            Match match,
            Requirement requirement,
            ServiceTokenClaims serviceToken,
            ProviderAssertionClaims assertion,
            PreparedRequest prepared) {
        return new WidgetRegistryTrustedRequestContext(
                match.route().operationId(),
                match.route().pathTemplate(),
                match.actualPath(),
                requirement.serviceScope(),
                requirement.providerPermission(),
                serviceToken.identity().jwtId(),
                assertion.identity().jwtId(),
                serviceToken.proof().keyId(),
                assertion.proof().keyId(),
                prepared.binding().requestTargetSha256(),
                prepared.binding().bodySha256(),
                prepared.binding().idempotencyKey(),
                prepared.binding().correlationId(),
                assertion.actorRef(),
                assertion.sessionRef(),
                assertion.permissionCodes(),
                assertion.ownerProductKeys(),
                assertion.providerAuthorityRevision(),
                assertion.authenticatedAt(),
                assertion.command(),
                assertion.reconcile());
    }

    private ProofHeaders proofHeaders(HttpServletRequest request, Match match) {
        List<String> authorizations = headerValues(request, SERVICE_TOKEN_HEADER);
        String expectedAssertionHeader = match.route().assertionKind()
                == WidgetRegistryTrustPorts.AssertionKind.RECONCILE
                ? RECONCILE_ASSERTION_HEADER
                : WIDGET_ASSERTION_HEADER;
        String forbiddenAssertionHeader = expectedAssertionHeader.equals(WIDGET_ASSERTION_HEADER)
                ? RECONCILE_ASSERTION_HEADER
                : WIDGET_ASSERTION_HEADER;
        List<String> assertions = headerValues(request, expectedAssertionHeader);
        if (authorizations.size() != 1
                || assertions.size() != 1
                || !headerValues(request, forbiddenAssertionHeader).isEmpty()) {
            return null;
        }
        String authorization = authorizations.get(0);
        if (authorization.length() <= 7 || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String serviceToken = authorization.substring(7);
        String assertion = assertions.get(0);
        if (!validCompactProof(serviceToken, MAX_SERVICE_TOKEN_LENGTH)
                || !validCompactProof(assertion, MAX_PROVIDER_ASSERTION_LENGTH)) {
            return null;
        }
        return new ProofHeaders(serviceToken, assertion);
    }

    private static boolean validCompactProof(String value, int maximumLength) {
        return value != null
                && value.length() <= maximumLength
                && COMPACT_JWS.matcher(value).matches();
    }

    private static List<String> headerValues(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }

    private static boolean hasUnexpectedDwpHeader(HttpServletRequest request, Match match) {
        String allowed = match.route().assertionKind() == WidgetRegistryTrustPorts.AssertionKind.RECONCILE
                ? RECONCILE_ASSERTION_HEADER
                : WIDGET_ASSERTION_HEADER;
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return true;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.regionMatches(true, 0, "X-DWP-", 0, 6)
                    && !name.equalsIgnoreCase(allowed)) {
                return true;
            }
        }
        return false;
    }

    private boolean permitsRequest() {
        try {
            return requestPermit.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void writeVerificationError(
            HttpServletResponse response,
            HttpServletRequest request,
            VerificationException exception,
            WidgetRegistryIngressFailure invalidFailure) throws IOException {
        if (exception.failure() == VerificationFailure.TRUST_UNAVAILABLE) {
            writeError(response, request, WidgetRegistryIngressFailure.TRUST_UNAVAILABLE);
            return;
        }
        writeError(response, request, invalidFailure);
    }

    private void writeError(
            HttpServletResponse response,
            HttpServletRequest request,
            WidgetRegistryIngressFailure failure) throws IOException {
        response.setStatus(failure.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        if (failure.status().value() == 401) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"dwp-platform-widget-registry\"");
        }
        objectMapper.writeValue(
                response.getOutputStream(),
                new ErrorEnvelope(
                        "ERROR",
                        failure.message(),
                        failure.code(),
                        false,
                        clock.instant(),
                        safeCorrelationId(request)));
    }

    private static String safeCorrelationId(HttpServletRequest request) {
        List<String> values = headerValues(request, WidgetRegistryRequestBinding.CORRELATION_HEADER);
        if (values.size() != 1 || !UUID.matcher(values.get(0)).matches()) return null;
        return values.get(0);
    }

    private record ProofHeaders(String serviceToken, String assertion) {
    }

    private record ErrorEnvelope(
            String status,
            String message,
            String errorCode,
            boolean success,
            Instant timestamp,
            String correlationId) {
    }
}
