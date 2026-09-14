package com.dwp.services.approval.signatures;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.document.ApprovalDocumentRenderer;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class ApprovalSignatureVerifiedSourceConfiguration {
    @Bean ApprovalSignatureHighRiskGuard approvalSignatureHighRiskGuard(ObjectProvider<HttpServletRequest> requests, ObjectMapper mapper,
            ApprovalStepUpVerifier verifier, ApprovalStepUpReplayRepository replay) {
        Clock clock=Clock.systemUTC();
        return new ApprovalSignatureHighRiskGuard(new ApprovalSignatureInstalledSource(new ApprovalSignatureCanonical(mapper),clock),
                requests::getObject,verifier,replay,clock);
    }
    @Bean @Lazy ApprovalSignatureSourceKeys approvalSignatureSourceKeys(Environment env) { return new ApprovalSignatureSourceKeys(env); }
    @Bean @Lazy ApprovalSignatureAuthorityClient approvalSignatureAuthorityClient(Environment env, ApprovalSignatureSourceKeys keys, ObjectMapper mapper) {
        try {
            String base = env.getRequiredProperty("dwp.approval.internal-signatures.source.auth-base-url");
            if (base.endsWith("/")) throw ApprovalSignatureCanonical.unavailable();
            return new ApprovalSignatureAuthorityClient(URI.create(base + ApprovalSignatureSourceExchange.PATH), keys, new ApprovalSignatureCanonical(mapper));
        } catch (RuntimeException invalid) { throw ApprovalSignatureCanonical.unavailable(); }
    }
    @Bean @Lazy ApprovalSignatureSourceRepository approvalSignatureAuthoritySourceRepository(Environment env, NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        try {
            var canonical = new ApprovalSignatureCanonical(mapper); String prefix = "dwp.approval.internal-signatures.";
            var signer = ApprovalSignatureSignerFactory.create(env, mapper, canonical, JWKSet.parse(env.getRequiredProperty(prefix + "authority-public-jwks")),
                    env.getProperty(prefix + "signing-private-jwk", ""), env.getProperty(prefix + "prohibited-public-jwks", ""),
                    env.getProperty(prefix + "key-isolation-inventory-complete", Boolean.class, false));
            return new ApprovalSignatureSourceRepository(jdbc, canonical, new ApprovalDocumentRenderer(new ApprovalDocumentCanonical(mapper)), signer);
        } catch (Exception unavailable) { throw ApprovalSignatureCanonical.unavailable(); }
    }
    @Bean ApprovalSignatureAuthority.Source approvalSignatureVerifiedSource(Environment env, ObjectProvider<HttpServletRequest> requests,
            ObjectProvider<ApprovalSignatureSourceKeys> keys, ObjectProvider<ApprovalSignatureAuthorityClient> transport,
            ObjectProvider<ApprovalSignatureSourceRepository> sources, NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, ApprovalStepUpVerifier highRisk) {
        var canonical = new ApprovalSignatureCanonical(mapper); Clock clock = Clock.systemUTC();
        return new ApprovalSignatureVerifiedSourceSupplier(env.getProperty("dwp.approval.internal-signatures.source.enabled", Boolean.class, false),
                requests::getObject, new ApprovalSignatureInstalledSource(canonical, clock), keys::getObject, transport::getObject,
                sources::getObject, jdbc, canonical, highRisk, clock);
    }
}
