package com.dwp.services.auth.service;

import com.dwp.services.auth.approvalsignatures.*;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class ApprovalSignatureAuthorityConfiguration {
    @Bean SignatureAuthorityJson signatureAuthorityJson(ObjectMapper mapper) { return new SignatureAuthorityJson(mapper); }
    @Bean @Lazy SignatureAuthorityKeys signatureAuthorityKeys(SignatureAuthorityJson json, Environment env) {
        String prefix = "dwp.auth.approval-signature-source.";
        var forbidden = new java.util.ArrayList<String>();
        forbidden.add(env.getProperty(prefix + "prohibited-jwks", ""));
        for (String name : List.of("dwp.auth.step-up.private-key-pem", "dwp.auth.approval-form-user-proof-jwks",
                "dwp.auth.approval-policy-impact.owner-public-jwks", "dwp.auth.approval-policy-impact.transport-public-jwks",
                "dwp.auth.approval-policy-impact.attestation-private-jwk", "dwp.auth.approval-policy-impact.attestation-public-jwks",
                "dwp.auth.approval-workflow-runtime.owner-trusted-keys", "dwp.auth.approval-workflow-runtime.transport-trusted-keys",
                "dwp.auth.approval-workflow-runtime.attestation-private-key", "dwp.auth.approval-workflow-runtime.attestation-trusted-keys",
                "dwp.auth.approval-workflow-role-proof-jwks", "dwp.auth.approval-workflow-role-transport-jwks",
                "dwp.auth.approval-workflow-role-mapping-private-jwk", "dwp.auth.approval-information-replay.owner-trusted-keys",
                "dwp.auth.approval-information-replay.transport-trusted-keys", "dwp.auth.approval-information-replay.attestation-private-key",
                "dwp.auth.approval-information-replay.attestation-trusted-keys", "dwp.auth.approval-recovery-proof-jwks"))
            forbidden.add(env.getProperty(name, ""));
        return new SignatureAuthorityKeys(json, env.getProperty(prefix + "owner-public-jwks"), env.getProperty(prefix + "transport-public-jwks"),
                env.getProperty(prefix + "attestation-private-jwk"), env.getProperty(prefix + "attestation-public-jwks"), forbidden,
                env.getProperty(prefix + "key-isolation-inventory-complete", Boolean.class, false)
                    && !env.getProperty(prefix + "prohibited-jwks", "").isBlank());
    }
    @Bean @Lazy SignatureAuthorityProofVerifier signatureAuthorityProofVerifier(SignatureAuthorityJson json, SignatureAuthorityKeys keys) {
        return new SignatureAuthorityProofVerifier(json, keys, Clock.systemUTC());
    }
    @Bean @Lazy SignatureAuthorityIssuer signatureAuthorityIssuer(SignatureAuthorityKeys keys) { return new SignatureAuthorityIssuer(keys, Clock.systemUTC()); }
    @Bean @Lazy SignatureHighRiskVerifier signatureHighRiskVerifier(SignatureAuthorityJson json, Environment env) {
        try {
            var key = SignatureAuthorityRsaKeyReader.read(env.getProperty("dwp.auth.step-up.private-key-pem", ""));
            return new SignatureHighRiskVerifier(json, key.toPublicJWK(), env.getProperty("dwp.auth.step-up.issuer"),
                    env.getProperty("dwp.auth.step-up.key-id"), env.getProperty("dwp.auth.step-up.required-acr"), Clock.systemUTC(),
                    env.getProperty("dwp.auth.step-up.maximum-authentication-age-seconds", Long.class, 600L),
                    env.getProperty("dwp.auth.step-up.challenge-ttl-seconds", Long.class, 900L));
        } catch (Exception unavailable) { throw SignatureAuthorityJson.unavailable(); }
    }
    @Bean SignatureCurrentAuthority signatureCurrentAuthority(ProductAuthorizationIdentityEvidenceService identities, ProductSurfaceAuthorityService surfaces,
            ProductAuthorizationContractRepository contracts, JdbcTemplate jdbc, ProductAuthorizationContractValidator validator, ObjectMapper mapper,
            SignatureAuthorityJson json, ObjectProvider<SignatureHighRiskVerifier> highRisk) {
        return new ApprovalSignatureCurrentAuthorityBridge(identities, surfaces, contracts, jdbc, validator, mapper, json, highRisk::getObject, Clock.systemUTC());
    }
    @Bean SignatureAuthorityService signatureAuthorityService(Environment env, ObjectProvider<SignatureAuthorityProofVerifier> verifier,
            SignatureCurrentAuthority authority, ObjectProvider<SignatureAuthorityIssuer> issuer) {
        return new SignatureAuthorityService(env.getProperty("dwp.auth.approval-signature-source.enabled", Boolean.class, false),
                verifier::getObject, authority, issuer::getObject);
    }
}
