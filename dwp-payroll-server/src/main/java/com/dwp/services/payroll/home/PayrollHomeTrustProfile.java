package com.dwp.services.payroll.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static com.dwp.services.payroll.home.PayrollHomePorts.PayrollHomeSigningKeySource;

final class PayrollHomeTrustProfile implements PayrollHomeSigningKeySource {

    private final String keyId;
    private final HomeMaterializationWorkloadProofV1.VerifiedSigningKey signingKey;

    PayrollHomeTrustProfile(PayrollHomeProducerProperties properties) {
        properties.validateForActivation();
        this.keyId = properties.getSigningKeyId();
        this.signingKey = new HomeMaterializationWorkloadProofV1.VerifiedSigningKey(
                parseRsaPublicKey(properties.getSigningPublicKeyPem()),
                HomeMaterializationWorkloadProofV1.WorkloadAuthentication.valueOf(
                        properties.getWorkloadAuthentication()),
                HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID);
    }

    @Override
    public HomeMaterializationWorkloadProofV1.VerifiedSigningKey resolve(String requestedKeyId) {
        if (!keyId.equals(requestedKeyId)) {
            throw new IllegalArgumentException("Untrusted Payroll Home signing key.");
        }
        return signingKey;
    }

    private PublicKey parseRsaPublicKey(String pem) {
        try {
            String compact = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(compact)));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Payroll Home Platform public key is invalid.", exception);
        }
    }
}
