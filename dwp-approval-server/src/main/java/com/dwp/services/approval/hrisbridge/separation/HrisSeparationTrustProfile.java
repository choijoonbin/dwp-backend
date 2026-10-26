package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Validates the live TLS peer and the closed five-header APR-002 envelope. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hrm013-bridge", name = "enabled",
        havingValue = "true")
final class HrisSeparationTrustProfile {
    static final String CERTIFICATES = "jakarta.servlet.request.X509Certificate";
    static final String TLS_PROTOCOL = "org.apache.tomcat.util.net.secure_protocol_version";
    static final String PEOPLE_SPIFFE = "spiffe://dwp/service/dwp-people-server";
    private static final Set<String> APPLICATION = Set.of(
            "content-type", "accept", "x-dwp-service-identity",
            "x-dwp-workload-proof", "x-correlation-id");
    private static final Set<String> TRANSPORT = Set.of(
            "host", "content-length", "user-agent", "connection",
            "accept-encoding", "http2-settings");

    Hrm013WorkloadProofV1.TransportIdentity authenticate(HttpServletRequest request) {
        if (request == null || !request.isSecure()
                || !"TLSv1.3".equals(request.getAttribute(TLS_PROTOCOL))) {
            throw HrisSeparationProtocolException.authenticationRequired();
        }
        Object attribute = request.getAttribute(CERTIFICATES);
        if (!(attribute instanceof X509Certificate[] chain) || chain.length == 0
                || chain[0] == null) {
            throw HrisSeparationProtocolException.authenticationRequired();
        }
        X509Certificate leaf = chain[0];
        try {
            leaf.checkValidity();
            List<String> uris = uriSans(leaf);
            if (!List.of(PEOPLE_SPIFFE).equals(uris)) {
                throw HrisSeparationProtocolException.authenticationRequired();
            }
            rejectUnregisteredHeaders(request);
            String thumbprint = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
            return new Hrm013WorkloadProofV1.TransportIdentity(
                    "dwp-people-server", thumbprint);
        } catch (HrisSeparationProtocolException failure) {
            throw failure;
        } catch (Exception invalidCertificate) {
            throw HrisSeparationProtocolException.authenticationRequired();
        }
    }

    List<Hrm013WorkloadProofV1.Header> applicationHeaders(HttpServletRequest request) {
        rejectUnregisteredHeaders(request);
        List<Hrm013WorkloadProofV1.Header> result = new ArrayList<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return List.of();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (!APPLICATION.contains(name.toLowerCase(Locale.ROOT))) continue;
            Enumeration<String> values = request.getHeaders(name);
            while (values != null && values.hasMoreElements()) {
                result.add(new Hrm013WorkloadProofV1.Header(name, values.nextElement()));
            }
        }
        return List.copyOf(result);
    }

    private static void rejectUnregisteredHeaders(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return;
        while (names.hasMoreElements()) {
            String name = names.nextElement().toLowerCase(Locale.ROOT);
            if (!APPLICATION.contains(name) && !TRANSPORT.contains(name)) {
                throw HrisSeparationProtocolException.malformed();
            }
        }
    }

    private static List<String> uriSans(X509Certificate certificate) throws Exception {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        if (names == null) return List.of();
        List<String> uris = new ArrayList<>();
        for (List<?> name : names) {
            if (name != null && name.size() >= 2
                    && Integer.valueOf(6).equals(name.get(0))
                    && name.get(1) instanceof String value) {
                uris.add(value);
            }
        }
        return List.copyOf(uris);
    }
}
