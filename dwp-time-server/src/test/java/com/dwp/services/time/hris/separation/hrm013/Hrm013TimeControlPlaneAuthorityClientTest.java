package com.dwp.services.time.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class Hrm013TimeControlPlaneAuthorityClientTest {
    @Test
    void resolverAndDispatchTuplesAreClosedAndPublicJwkIsUsable() throws Exception {
        assertThat(AuthHrm013ServiceAuthorityClient.RESOLVER_BINDING_ID)
                .isEqualTo("SVC-AUT-HRM-015");
        assertThat(Hrm013TaskDispatchV1.TIME.bindingId()).isEqualTo("SVC-PEP-TIM-004");
        assertThat(Hrm013TaskDispatchV1.TIME.authorityResolverBindingId())
                .isEqualTo("SVC-AUT-HRM-015");
        assertThat(Hrm013WorkloadProofV1.resolverBootstrap(
                Hrm013WorkloadProofV1.ResolverCaller.TIME).callerWorkloadId())
                .isEqualTo("dwp-time-server");

        var pair = KeyPairGenerator.getInstance("RSA");
        pair.initialize(2048);
        RSAPublicKey key = (RSAPublicKey) pair.generateKeyPair().getPublic();
        var jwk = new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", base64(unsigned(key.getPublicExponent().toByteArray())),
                "time-test-key", "RSA", base64(unsigned(key.getModulus().toByteArray())),
                "sig");
        assertThat(AuthHrm013ServiceAuthorityClient.publicKey(jwk).getEncoded())
                .isEqualTo(key.getEncoded());
    }

    private static byte[] unsigned(byte[] value) {
        return value.length > 1 && value[0] == 0 ? Arrays.copyOfRange(value, 1, value.length)
                : value;
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
