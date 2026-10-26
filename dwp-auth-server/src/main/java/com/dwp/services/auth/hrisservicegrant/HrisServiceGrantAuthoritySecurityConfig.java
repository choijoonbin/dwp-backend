package com.dwp.services.auth.hrisservicegrant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;

/** Default-off bean and HTTP boundary for HRM-013 Auth control-plane runtime. */
@Configuration(proxyBeanMethods = false)
public class HrisServiceGrantAuthoritySecurityConfig {
    private static final String GRANT_ENABLED =
            "dwp.auth.hris-service-grant-authority.enabled";
    private static final String REQUESTER_ENABLED =
            "dwp.auth.hrm013-requester-authority.enabled";
    private static final String MTLS = "dwp.auth.hrm013-mtls.";

    @Bean
    @ConditionalOnExpression(
            "'${dwp.auth.hris-service-grant-authority.enabled:false}' == 'true' or "
                    + "'${dwp.auth.hrm013-requester-authority.enabled:false}' == 'true'")
    HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
            hrm013ClientCertificateValidator(
                    Environment environment, ResourceLoader resources) {
        return loadClientCertificateValidator(environment, resources, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = GRANT_ENABLED, havingValue = "true")
    HrisServiceGrantAuthorityRepository hrisServiceGrantAuthorityRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper mapper) {
        return new HrisServiceGrantAuthorityRepository(jdbc, transactionManager, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = GRANT_ENABLED, havingValue = "true")
    HrisServiceGrantAuthorityService hrisServiceGrantAuthorityService(
            HrisServiceGrantAuthorityRepository repository) {
        return new HrisServiceGrantAuthorityService(repository);
    }

    @Bean
    @ConditionalOnProperty(name = GRANT_ENABLED, havingValue = "true")
    HrisServiceGrantQuiesceService hrisServiceGrantQuiesceService(
            HrisServiceGrantAuthorityRepository repository,
            HrisServiceGrantRotationReadinessVerifier readinessVerifier) {
        return new HrisServiceGrantQuiesceService(repository, readinessVerifier);
    }

    @Bean
    @ConditionalOnProperty(name = GRANT_ENABLED, havingValue = "true")
    HrisServiceGrantRotationReadinessVerifier hrisServiceGrantRotationReadinessVerifier() {
        return new HrisServiceGrantRotationReadinessVerifier();
    }

    @Bean
    @ConditionalOnProperty(name = GRANT_ENABLED, havingValue = "true")
    HrisServiceGrantAuthorityOwnerCredentialProvider hrisOwnerCredentialProvider(
            ObjectProvider<HrisServiceGrantAuthorityOwnerCredentialProvider.LeaseSource> sources) {
        var candidates = sources.orderedStream().toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "exactly one externally provisioned HRM-013 owner credential source is required");
        }
        return new HrisServiceGrantAuthorityOwnerCredentialProvider(candidates.getFirst());
    }

    @Bean
    @ConditionalOnProperty(name = GRANT_ENABLED, havingValue = "true")
    HrisServiceGrantAuthorityProofVerifier hrisServiceGrantAuthorityProofVerifier(
            ObjectMapper mapper) {
        return new HrisServiceGrantAuthorityProofVerifier(mapper, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = GRANT_ENABLED, havingValue = "true")
    HrisServiceGrantAuthorityIngressFilter hrisServiceGrantAuthorityIngressFilter(
            ObjectMapper mapper, HrisServiceGrantAuthorityProofVerifier verifier,
            HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
                    certificateValidator) {
        return new HrisServiceGrantAuthorityIngressFilter(
                mapper, Clock.systemUTC(), verifier, certificateValidator);
    }

    @Bean
    @Order(-12)
    @ConditionalOnProperty(name = GRANT_ENABLED, havingValue = "true")
    SecurityFilterChain hrisServiceGrantAuthoritySecurityChain(
            HttpSecurity http, HrisServiceGrantAuthorityIngressFilter filter)
            throws Exception {
        return http.securityMatcher(
                        com.dwp.platform.contracts.hris.separation.v1
                                .HrisServiceGrantAuthorityV1.PATH)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(-12)
    @ConditionalOnProperty(
            name = GRANT_ENABLED, havingValue = "false", matchIfMissing = true)
    SecurityFilterChain hrisServiceGrantAuthorityDisabledChain(HttpSecurity http)
            throws Exception {
        return disabled(http,
                com.dwp.platform.contracts.hris.separation.v1
                        .HrisServiceGrantAuthorityV1.PATH);
    }

    @Bean
    @ConditionalOnProperty(name = REQUESTER_ENABLED, havingValue = "true")
    Hrm013RequesterIdentityAuthorityRepository hrm013RequesterIdentityAuthorityRepository(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ObjectMapper mapper) {
        return new Hrm013RequesterIdentityAuthorityRepository(
                jdbc, transactionManager, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = REQUESTER_ENABLED, havingValue = "true")
    Hrm013RequesterIdentityProofVerifier hrm013RequesterIdentityProofVerifier(
            ObjectMapper mapper) {
        return new Hrm013RequesterIdentityProofVerifier(mapper, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = REQUESTER_ENABLED, havingValue = "true")
    Hrm013RequesterIdentityAuthorityService hrm013RequesterIdentityAuthorityService(
            Hrm013RequesterIdentityAuthorityRepository repository,
            Hrm013RequesterIdentityProofVerifier proofVerifier) {
        return new Hrm013RequesterIdentityAuthorityService(repository, proofVerifier);
    }

    @Bean
    @ConditionalOnProperty(name = REQUESTER_ENABLED, havingValue = "true")
    Hrm013RequesterIdentityIngressFilter hrm013RequesterIdentityIngressFilter(
            ObjectMapper mapper,
            HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
                    certificateValidator) {
        return new Hrm013RequesterIdentityIngressFilter(
                mapper, Clock.systemUTC(), certificateValidator);
    }

    @Bean
    @Order(-11)
    @ConditionalOnProperty(name = REQUESTER_ENABLED, havingValue = "true")
    SecurityFilterChain hrm013RequesterIdentitySecurityChain(
            HttpSecurity http, Hrm013RequesterIdentityIngressFilter filter)
            throws Exception {
        return http.securityMatcher(
                        com.dwp.platform.contracts.hris.separation.v1
                                .Hrm013RequesterIdentityAuthorityV1.PATH)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(-11)
    @ConditionalOnProperty(
            name = REQUESTER_ENABLED, havingValue = "false", matchIfMissing = true)
    SecurityFilterChain hrm013RequesterIdentityDisabledChain(HttpSecurity http)
            throws Exception {
        return disabled(http,
                com.dwp.platform.contracts.hris.separation.v1
                        .Hrm013RequesterIdentityAuthorityV1.PATH);
    }

    private static SecurityFilterChain disabled(HttpSecurity http, String path)
            throws Exception {
        return http.securityMatcher(path)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .build();
    }

    static HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
            loadClientCertificateValidator(
                    Environment environment, ResourceLoader resources, Clock clock) {
        if (!"NEED".equals(required(environment, "client-auth"))) {
            throw new IllegalStateException("HRM-013 TLS client-auth must be NEED");
        }
        String location = required(environment, "trust-store-location");
        String passwordText = required(environment, "trust-store-password");
        String type = required(environment, "trust-store-type").toUpperCase(Locale.ROOT);
        if (!Set.of("PKCS12", "JKS").contains(type)) {
            throw new IllegalStateException("HRM-013 trust-store type must be PKCS12 or JKS");
        }
        char[] password = passwordText.toCharArray();
        try {
            KeyStore keyStore = KeyStore.getInstance(type);
            var resource = resources.getResource(location);
            if (!resource.exists() || !resource.isReadable()) {
                throw new IllegalStateException("HRM-013 trust-store is unreadable");
            }
            try (InputStream input = resource.getInputStream()) {
                keyStore.load(input, password);
            }
            Set<TrustAnchor> anchors = new HashSet<>();
            var aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                var certificate = keyStore.getCertificate(aliases.nextElement());
                if (certificate instanceof X509Certificate x509
                        && x509.getBasicConstraints() >= 0) {
                    anchors.add(new TrustAnchor(x509, null));
                }
            }
            if (anchors.isEmpty()) {
                throw new IllegalStateException("HRM-013 trust-store has no CA certificate");
            }
            var mode = HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
                    .RevocationMode.valueOf(required(environment, "revocation-mode")
                            .toUpperCase(Locale.ROOT));
            ArrayList<X509CRL> crls = new ArrayList<>();
            URI ocsp = null;
            if (mode == HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator
                    .RevocationMode.CRL) {
                var crlResource = resources.getResource(required(environment, "crl-location"));
                if (!crlResource.exists() || !crlResource.isReadable()) {
                    throw new IllegalStateException("HRM-013 CRL is unreadable");
                }
                try (InputStream input = crlResource.getInputStream()) {
                    for (var value : CertificateFactory.getInstance("X.509")
                            .generateCRLs(input)) {
                        if (value instanceof X509CRL crl) crls.add(crl);
                    }
                }
                if (crls.isEmpty()) {
                    throw new IllegalStateException("HRM-013 CRL set is empty");
                }
                var now = java.util.Date.from(clock.instant());
                for (X509CRL crl : crls) {
                    if (crl.getThisUpdate() == null || crl.getThisUpdate().after(now)
                            || crl.getNextUpdate() == null || !now.before(crl.getNextUpdate())) {
                        throw new IllegalStateException("HRM-013 CRL validity window rejected");
                    }
                    boolean verified = false;
                    for (TrustAnchor anchor : anchors) {
                        try {
                            crl.verify(anchor.getTrustedCert().getPublicKey());
                            verified = true;
                            break;
                        } catch (Exception ignored) {
                            // Try the next explicitly trusted CA.
                        }
                    }
                    if (!verified) {
                        throw new IllegalStateException("HRM-013 CRL is not trust-anchored");
                    }
                }
            } else {
                ocsp = URI.create(required(environment, "ocsp-responder"));
                if (!"https".equalsIgnoreCase(ocsp.getScheme())
                        || ocsp.getHost() == null || ocsp.getUserInfo() != null
                        || ocsp.getFragment() != null) {
                    throw new IllegalStateException("HRM-013 OCSP responder must be HTTPS");
                }
            }
            return new HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator(
                    anchors, mode, crls, ocsp);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("HRM-013 mTLS trust initialization failed", failure);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getProperty(MTLS + suffix);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalStateException("missing HRM-013 mTLS setting: " + suffix);
        }
        return value;
    }
}
