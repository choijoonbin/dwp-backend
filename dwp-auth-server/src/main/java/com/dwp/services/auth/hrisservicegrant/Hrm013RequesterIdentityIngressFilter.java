package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.Header;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.TransportIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.springframework.web.filter.OncePerRequestFilter;

/** Transport-only requester gate; service-grant keys are deliberately resolved later. */
public final class Hrm013RequesterIdentityIngressFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = RequestContext.class.getName();

    public record RequestContext(
            Hrm013RequesterIdentityAuthorityV1.ResolveRequest request,
            byte[] exactBody,
            List<Header> headers,
            TransportIdentity transportIdentity) {
        public RequestContext {
            Objects.requireNonNull(request, "request");
            exactBody = Objects.requireNonNull(exactBody, "exactBody").clone();
            headers = List.copyOf(headers);
            Objects.requireNonNull(transportIdentity, "transportIdentity");
        }

        @Override public byte[] exactBody() { return exactBody.clone(); }
    }

    private final ObjectMapper mapper;
    private final Clock clock;
    private final HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
            certificateValidator;

    public Hrm013RequesterIdentityIngressFilter(
            ObjectMapper mapper, Clock clock,
            HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
                    certificateValidator) {
        this.mapper = mapper;
        this.clock = clock;
        this.certificateValidator = certificateValidator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !Hrm013RequesterIdentityAuthorityV1.PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var correlation = HrisServiceGrantAuthorityIngressFilter.correlationOrNull(request);
        try {
            HrisServiceGrantAuthorityIngressFilter.requireExactRequestLine(
                    request, Hrm013RequesterIdentityAuthorityV1.PATH);
            List<Header> headers =
                    HrisServiceGrantAuthorityIngressFilter.exactApplicationHeaders(request);
            X509Certificate leaf =
                    HrisServiceGrantAuthorityIngressFilter.requireTlsLeaf(
                            request, clock, certificateValidator);
            HrisServiceGrantAuthorityIngressFilter.requireSingleSpiffeUri(
                    leaf, "spiffe://dwp/service/dwp-approval-server");
            byte[] body = HrisServiceGrantAuthorityIngressFilter.boundedBody(
                    request, Hrm013RequesterIdentityAuthorityV1.MAX_REQUEST_BYTES);
            var decoded = Hrm013RequesterIdentityAuthorityV1.decodeRequest(mapper, body);
            var identity = new TransportIdentity(
                    "dwp-approval-server",
                    HrisServiceGrantAuthorityIngressFilter.certificateThumbprint(leaf));
            var context = new RequestContext(decoded, body, headers, identity);
            var wrapped = new HrisServiceGrantAuthorityIngressFilter.BufferedRequest(request, body);
            wrapped.setAttribute(ATTRIBUTE, context);
            chain.doFilter(wrapped, response);
        } catch (HrisServiceGrantAuthorityIngressFilter.MissingCertificate failure) {
            HrisServiceGrantAuthorityProblem.write(
                    response, Hrm013SafeErrorV1.authenticationRequired(), correlation);
        } catch (RuntimeException failure) {
            HrisServiceGrantAuthorityProblem.write(
                    response, Hrm013SafeErrorV1.authorityDenied(), correlation);
        }
    }
}
