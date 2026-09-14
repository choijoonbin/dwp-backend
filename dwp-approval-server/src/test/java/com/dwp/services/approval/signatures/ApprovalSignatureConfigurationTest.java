package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

class ApprovalSignatureConfigurationTest {
    RSAKey key(String kid) throws Exception {
        return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
    }
    @Test void independentlyRenamedRuntimeKeyMaterialIsRejectedForEveryRegisteredKeyFamilyWithoutSql() throws Exception {
        RSAKey signing=key("approval-self-attestation:configuration"),other=key("not-the-signing-key");
        var jdbc=mock(NamedParameterJdbcTemplate.class); var tx=mock(PlatformTransactionManager.class);
        var sources=emptySources();
        for (String property:List.of("dwp.approval.workflow-runtime-authority.owner-private-key",
                "dwp.approval.information-replay.transport-private-key","dwp.approval.form-user-source-private-jwk",
                "dwp.approval.policy-impact.source.owner-private-jwk")) {
            var env=new MockEnvironment().withProperty(property,new RSAKey.Builder(signing).keyID("renamed-runtime-key").build().toJSONString());
            assertThatThrownBy(() -> configure(jdbc,tx,sources,env,signing,other,true)).as(property)
                    .isInstanceOf(com.dwp.core.exception.BaseException.class);
        }
        var pem=java.util.Base64.getEncoder().encodeToString(signing.toRSAPrivateKey().getEncoded());
        assertThatThrownBy(() -> configure(jdbc,tx,sources,new MockEnvironment().withProperty("dwp.approval.workflow-runtime-authority.transport-private-key",pem),signing,other,true))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        var publicPem=java.util.Base64.getEncoder().encodeToString(signing.toRSAPublicKey().getEncoded());
        assertThatThrownBy(() -> configure(jdbc,tx,sources,new MockEnvironment().withProperty("dwp.approval.step-up.public-key-pem",publicPem),signing,other,true))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        String privateEnvelope="-----BEGIN PRIVATE KEY-----\n"+java.util.Base64.getEncoder().encodeToString(other.toRSAPrivateKey().getEncoded())+"\n-----END PRIVATE KEY-----";
        assertThatCode(() -> configure(jdbc,tx,sources,new MockEnvironment().withProperty("dwp.approval.workflow-runtime-authority.owner-private-key",privateEnvelope),signing,other,true))
                .doesNotThrowAnyException();
        verifyNoInteractions(jdbc,tx);
    }
    @Test void missingIsolationInventoryStillPreventsSignBeforeAuthorityOrSql() throws Exception {
        var jdbc=mock(NamedParameterJdbcTemplate.class); var tx=mock(PlatformTransactionManager.class); var sources=emptySources();
        var service=configure(jdbc,tx,sources,new MockEnvironment(),key("approval-self-attestation:configuration"),key("catalogue"),false);
        assertThatThrownBy(() -> service.sign(java.util.UUID.randomUUID(),new ApprovalSignatureDtos.Sign(1L,"a".repeat(64),java.util.UUID.randomUUID(),"original")))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        verifyNoInteractions(jdbc,tx);
    }
    ObjectProvider<ApprovalSignatureAuthority.Source> emptySources() {
        return new org.springframework.beans.factory.support.StaticListableBeanFactory().getBeanProvider(ApprovalSignatureAuthority.Source.class);
    }
    ApprovalSignatureService configure(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager tx,ObjectProvider<ApprovalSignatureAuthority.Source> source,
            MockEnvironment environment,RSAKey signing,RSAKey prohibited,boolean inventory) {
        return new ApprovalSignatureConfiguration().approvalSignatureService(jdbc,tx,new ObjectMapper(),source,environment,"",signing.toJSONString(),
                new JWKSet(prohibited.toPublicJWK()).toString(),inventory,"SELF_ATTESTATION_TERMS",1,"Internal self-attestation only.",
                "Internal self-attestation only.",Instant.now().plusSeconds(3600).toString());
    }
}
