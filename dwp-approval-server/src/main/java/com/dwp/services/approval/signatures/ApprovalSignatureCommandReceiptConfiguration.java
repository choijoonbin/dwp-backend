package com.dwp.services.approval.signatures;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
public class ApprovalSignatureCommandReceiptConfiguration {
    @Bean ApprovalSignatureCommandReceiptRepository approvalSignatureCommandReceiptRepository(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,Environment env){
        var json=new ApprovalSignatureCanonical(mapper);return new ApprovalSignatureCommandReceiptRepository(jdbc,json,new ApprovalSignatureReceiptCurrentRepository(jdbc,json,()->keySha(env)));
    }
    @Bean ApprovalSignatureCommandReceiptAuthority.Source approvalSignatureCommandReceiptSource(Environment env,ObjectProvider<HttpServletRequest> requests,
            ObjectProvider<ApprovalSignatureSourceKeys> keys,ObjectProvider<ApprovalSignatureAuthorityClient> client,ApprovalSignatureCommandReceiptRepository repository,ObjectMapper mapper){
        var json=new ApprovalSignatureCanonical(mapper);Clock clock=Clock.systemUTC();return new ApprovalSignatureCommandReceiptSource(env.getProperty("dwp.approval.internal-signatures.source.enabled",Boolean.class,false),
                requests::getObject,new ApprovalSignatureReceiptInstalledSource(json,clock),keys::getObject,client::getObject,repository,json,clock);
    }
    @Bean @Lazy ApprovalSignatureCommandReceiptAuthority approvalSignatureCommandReceiptAuthority(ApprovalSignatureCommandReceiptAuthority.Source source,Environment env,ObjectMapper mapper){
        try{return new ApprovalSignatureCommandReceiptAuthority(source,JWKSet.parse(env.getRequiredProperty("dwp.approval.internal-signatures.authority-public-jwks")),Clock.systemUTC(),new ApprovalSignatureCanonical(mapper));}
        catch(Exception missing){throw ApprovalSignatureCanonical.unavailable();}
    }
    @Bean ApprovalSignatureCommandReceiptService approvalSignatureCommandReceiptService(Environment env,ObjectProvider<ApprovalSignatureCommandReceiptAuthority> authority,
            ObjectProvider<HttpServletRequest> requests,ApprovalSignatureCommandReceiptRepository repository,ObjectMapper mapper,PlatformTransactionManager tx){
        var json=new ApprovalSignatureCanonical(mapper);var reads=new TransactionTemplate(tx);reads.setReadOnly(true);return new ApprovalSignatureCommandReceiptService(
                env.getProperty("dwp.approval.internal-signatures.source.enabled",Boolean.class,false),authority::getObject,new ApprovalSignatureReceiptInstalledSource(json,Clock.systemUTC()),requests::getObject,repository,json,reads);
    }
    private static String keySha(Environment env){
        try{var key=RSAKey.parse(env.getRequiredProperty("dwp.approval.internal-signatures.signing-private-jwk"));
            if(!key.isPrivate() || key.size()<2048 || key.getKeyID()==null || !key.getKeyID().startsWith("approval-self-attestation:") || !KeyUse.SIGNATURE.equals(key.getKeyUse()) || !JWSAlgorithm.RS256.equals(key.getAlgorithm()))throw ApprovalSignatureCanonical.unavailable();
            // Hash-only inspection: no key creation, signing operation or artifact retrieval on this GET.
            return java.util.HexFormat.of().formatHex(key.toPublicJWK().computeThumbprint().decode());
        }catch(Exception missing){throw ApprovalSignatureCanonical.unavailable();}
    }
}
