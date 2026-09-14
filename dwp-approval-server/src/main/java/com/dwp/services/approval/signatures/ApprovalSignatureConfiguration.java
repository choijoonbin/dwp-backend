package com.dwp.services.approval.signatures;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.document.ApprovalDocumentRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.*;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="dwp.approval.internal-signatures",name="enabled",havingValue="true")
public class ApprovalSignatureConfiguration {
    @Bean(name="approvalSignatureService") ApprovalSignatureService guardedApprovalSignatureService(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager tx,
            ObjectMapper mapper,ObjectProvider<ApprovalSignatureAuthority.Source> source,Environment environment,ApprovalSignatureHighRiskGuard highRisk) {
        String prefix="dwp.approval.internal-signatures.";
        return create(jdbc,tx,mapper,source,environment,environment.getProperty(prefix+"authority-public-jwks",""),
                environment.getProperty(prefix+"signing-private-jwk",""),environment.getProperty(prefix+"prohibited-public-jwks",""),
                environment.getProperty(prefix+"key-isolation-inventory-complete",Boolean.class,false),environment.getProperty(prefix+"terms-id",""),
                environment.getProperty(prefix+"terms-version",Long.class,0L),environment.getProperty(prefix+"terms-ko",""),environment.getProperty(prefix+"terms-en",""),
                environment.getProperty(prefix+"terms-expires-at",""),highRisk);
    }
    ApprovalSignatureService approvalSignatureService(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager tx,
            ObjectMapper mapper,ObjectProvider<ApprovalSignatureAuthority.Source> source,Environment environment,
            @Value("${dwp.approval.internal-signatures.authority-public-jwks:}") String trusted,
            @Value("${dwp.approval.internal-signatures.signing-private-jwk:}") String privateJwk,
            @Value("${dwp.approval.internal-signatures.prohibited-public-jwks:}") String prohibited,
            @Value("${dwp.approval.internal-signatures.key-isolation-inventory-complete:false}") boolean inventory,
            @Value("${dwp.approval.internal-signatures.terms-id:}") String termsId,
            @Value("${dwp.approval.internal-signatures.terms-version:0}") long termsVersion,
            @Value("${dwp.approval.internal-signatures.terms-ko:}") String ko,
            @Value("${dwp.approval.internal-signatures.terms-en:}") String en,
            @Value("${dwp.approval.internal-signatures.terms-expires-at:}") String expiration) {
        return create(jdbc,tx,mapper,source,environment,trusted,privateJwk,prohibited,inventory,termsId,termsVersion,ko,en,expiration,null);
    }
    private ApprovalSignatureService create(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager tx,ObjectMapper mapper,
            ObjectProvider<ApprovalSignatureAuthority.Source> source,Environment environment,String trusted,String privateJwk,String prohibited,
            boolean inventory,String termsId,long termsVersion,String ko,String en,String expiration,ApprovalSignatureHighRiskGuard highRisk) {
        try {
            var canonical=new ApprovalSignatureCanonical(mapper); Clock clock=Clock.systemUTC();
            JWKSet trust=trusted.isBlank()?new JWKSet():JWKSet.parse(trusted);
            var signer=ApprovalSignatureSignerFactory.create(environment,mapper,canonical,trust,privateJwk,prohibited,inventory);
            var authority=new ApprovalSignatureAuthority(source.getIfAvailable(),trust,clock,canonical);
            var terms=new ApprovalSignatureTerms(termsId,termsVersion,Map.of("ko",ko,"en",en),expiration.isBlank()?null:Instant.parse(expiration),clock);
            var sources=new ApprovalSignatureSourceRepository(jdbc,canonical,new ApprovalDocumentRenderer(new ApprovalDocumentCanonical(mapper)),signer);
            // SELECT row locks protect read projections; these handlers still contain no DML.
            var writes=new TransactionTemplate(tx); var reads=new TransactionTemplate(tx);
            return new ApprovalSignatureService(true,authority,sources,new ApprovalSignatureRepository(jdbc,canonical),terms,signer,canonical,writes,reads,clock,highRisk);
        } catch (Exception invalid) { throw ApprovalSignatureCanonical.unavailable(); }
    }
}
