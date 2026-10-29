package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService;
import com.dwp.services.auth.hrisservicegrant.Hrm013IdentityGrantRotationReadiness;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;

/** The complete identity runtime is absent unless the dedicated flag is true. */
@Configuration(proxyBeanMethods = false)
class Hrm013IdentityTaskDispatchRuntimeConfiguration {
    private static final String ENABLED =
            "dwp.auth.hrm013-identity-task-dispatch.enabled";
    private static final String MTLS = "dwp.auth.hrm013-mtls.";
    private static final String RELAY_ENABLED =
            "dwp.auth.hrm013-identity-task-dispatch.receipt-relay-enabled";

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    Clock hrm013IdentityClock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    Hrm013IdentityTaskDispatchIngressFilter.ClientCertificatePolicy
            hrm013IdentityCertificatePolicy(Environment environment,
                    ResourceLoader resources, Clock hrm013IdentityClock) {
        if (!"NEED".equals(required(environment, "client-auth"))) {
            throw new IllegalStateException("HRM-013 TLS client-auth must be NEED");
        }
        char[] password = required(environment, "trust-store-password").toCharArray();
        try {
            String type = required(environment, "trust-store-type")
                    .toUpperCase(Locale.ROOT);
            if (!Set.of("PKCS12", "JKS").contains(type)) {
                throw new IllegalStateException("unsupported HRM-013 trust-store type");
            }
            KeyStore store = KeyStore.getInstance(type);
            var resource = resources.getResource(
                    required(environment, "trust-store-location"));
            if (!resource.exists() || !resource.isReadable()) {
                throw new IllegalStateException("HRM-013 trust-store is unreadable");
            }
            try (InputStream input = resource.getInputStream()) {
                store.load(input, password);
            }
            Set<TrustAnchor> anchors = new HashSet<>();
            var aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                var value = store.getCertificate(aliases.nextElement());
                if (value instanceof X509Certificate certificate
                        && certificate.getBasicConstraints() >= 0) {
                    anchors.add(new TrustAnchor(certificate, null));
                }
            }
            String mode = required(environment, "revocation-mode")
                    .toUpperCase(Locale.ROOT);
            List<X509CRL> crls = new ArrayList<>();
            URI ocsp = null;
            if ("CRL".equals(mode)) {
                var crlResource = resources.getResource(
                        required(environment, "crl-location"));
                if (!crlResource.exists() || !crlResource.isReadable()) {
                    throw new IllegalStateException("HRM-013 CRL is unreadable");
                }
                try (InputStream input = crlResource.getInputStream()) {
                    for (var value : CertificateFactory.getInstance("X.509")
                            .generateCRLs(input)) {
                        if (value instanceof X509CRL crl) crls.add(crl);
                    }
                }
                Date now = Date.from(hrm013IdentityClock.instant());
                for (X509CRL crl : crls) {
                    if (crl.getThisUpdate() == null || crl.getThisUpdate().after(now)
                            || crl.getNextUpdate() == null
                            || !now.before(crl.getNextUpdate())) {
                        throw new IllegalStateException("HRM-013 CRL validity rejected");
                    }
                }
            } else if ("OCSP".equals(mode)) {
                ocsp = URI.create(required(environment, "ocsp-responder"));
                if (!"https".equalsIgnoreCase(ocsp.getScheme())
                        || ocsp.getHost() == null || ocsp.getUserInfo() != null
                        || ocsp.getFragment() != null) {
                    throw new IllegalStateException("HRM-013 OCSP responder must be HTTPS");
                }
            } else {
                throw new IllegalStateException("HRM-013 revocation mode is invalid");
            }
            return new Hrm013IdentityTaskDispatchIngressFilter
                    .PkixClientCertificatePolicy(anchors, crls, ocsp);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("HRM-013 mTLS initialization failed", failure);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    Hrm013IdentityTaskDispatchStore hrm013IdentityTaskDispatchStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactions,
            ObjectMapper mapper, Clock hrm013IdentityClock,
            Hrm013IdentityWorkloadCredentialProvider credentials,
            HrisServiceGrantQuiesceService authorities) {
        return new Hrm013IdentityTaskDispatchStore(
                jdbc, transactions, mapper, hrm013IdentityClock, credentials,
                authorities);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    Hrm013IdentityTaskDispatchService.TenantWorkSource hrm013IdentityTenantWorkSource(
            JdbcTemplate jdbc) {
        return (after, limit) -> jdbc.queryForList("""
                SELECT tenant_id FROM com_tenants
                 WHERE status='ACTIVE' AND tenant_id > ?
                 ORDER BY tenant_id LIMIT ?
                """, Long.class, after, Math.min(100, Math.max(1, limit)));
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    Hrm013IdentityTaskDispatchService hrm013IdentityTaskDispatchService(
            Hrm013IdentityTaskDispatchStore store,
            Hrm013IdentityTaskDispatchService.TenantWorkSource tenants) {
        return new Hrm013IdentityTaskDispatchService(store, tenants);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    Hrm013IdentityWorkloadCredentialProvider hrm013IdentityWorkloadCredentials(
            ObjectProvider<Hrm013IdentityWorkloadCredentialProvider.LeaseSource> sources,
            ObjectMapper mapper, Clock hrm013IdentityClock) {
        var candidates = sources.orderedStream().toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "exactly one external HRM-013 identity credential source is required");
        }
        return new Hrm013IdentityWorkloadCredentialProvider(
                candidates.getFirst(), mapper, hrm013IdentityClock);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    Hrm013IdentityGrantRotationReadiness hrm013IdentityGrantRotationReadiness(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            HrisServiceGrantQuiesceService authorities,
            ObjectMapper mapper, Clock hrm013IdentityClock) {
        return new Hrm013IdentityGrantRotationReadiness(
                jdbc, transactions, (query, correlationId) -> {
                    UUID proofId = UUID.randomUUID();
                    UUID nonce;
                    do {
                        nonce = UUID.randomUUID();
                    } while (nonce.equals(proofId));
                    var replay = new Hrm013WorkloadProofV1.ReplayReservation(
                            query.tenantId(), query.targetBindingId(),
                            query.requestingWorkloadId(),
                            Hrm013WorkloadProofV1.sha256(proofId.toString()
                                    .getBytes(StandardCharsets.UTF_8)),
                            Hrm013WorkloadProofV1.sha256(nonce.toString()
                                    .getBytes(StandardCharsets.UTF_8)),
                            hrm013IdentityClock.instant().plusSeconds(30));
                    String proofSha256 = Hrm013WorkloadProofV1.sha256(
                            (query.targetBindingId() + "\n" + correlationId + "\n"
                                    + proofId).getBytes(StandardCharsets.UTF_8));
                    return authorities.observeLocalReadiness(
                            query, replay, proofSha256);
                }, hrm013IdentityClock, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = {ENABLED, RELAY_ENABLED}, havingValue = "true")
    Hrm013IdentityTaskReceiptRelay hrm013IdentityTaskReceiptRelay(
            Hrm013IdentityTaskDispatchService service,
            Hrm013IdentityTaskDispatchService.TenantWorkSource tenants,
            Environment environment, Clock hrm013IdentityClock) {
        URI peopleBase = URI.create(requiredProperty(environment,
                "dwp.auth.hrm013-identity-task-dispatch.people-base-url"));
        return new Hrm013IdentityTaskReceiptRelay(
                service, ignored -> peopleBase,
                new Hrm013IdentityTaskReceiptRelay.JavaHttpTransport(),
                (callbackEventId, attempt) -> java.time.Duration.ofSeconds(
                        Hrm013IdentityTaskDispatchStore.boundedFullJitterSeconds(
                                callbackEventId, attempt)),
                tenants,
                hrm013IdentityClock);
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    Hrm013IdentityTaskDispatchIngressFilter hrm013IdentityTaskDispatchIngressFilter(
            Clock hrm013IdentityClock,
            Hrm013IdentityTaskDispatchIngressFilter.ClientCertificatePolicy policy) {
        return new Hrm013IdentityTaskDispatchIngressFilter(
                hrm013IdentityClock, policy);
    }

    @Bean
    @Order(-10)
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    SecurityFilterChain hrm013IdentityTaskDispatchChain(HttpSecurity http,
            Hrm013IdentityTaskDispatchIngressFilter filter) throws Exception {
        return http.securityMatcher(Hrm013TaskDispatchV1.IDENTITY.path())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(-10)
    @ConditionalOnProperty(name = ENABLED, havingValue = "false",
            matchIfMissing = true)
    SecurityFilterChain hrm013IdentityTaskDispatchDisabledChain(HttpSecurity http)
            throws Exception {
        return http.securityMatcher(Hrm013TaskDispatchV1.IDENTITY.path())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .build();
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getProperty(MTLS + suffix);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalStateException("missing HRM-013 mTLS setting: " + suffix);
        }
        return value;
    }

    private static String requiredProperty(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalStateException("missing HRM-013 setting: " + key);
        }
        return value;
    }
}
