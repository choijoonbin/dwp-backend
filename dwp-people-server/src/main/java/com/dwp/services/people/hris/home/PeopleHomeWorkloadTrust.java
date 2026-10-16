package com.dwp.services.people.hris.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;

/** Owner-loaded one-key RS256 trust profile for dwp-platform-server. */
@Component
@ConditionalOnProperty(prefix = PeopleHomeAuthorityPort.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class PeopleHomeWorkloadTrust
        implements HomeMaterializationWorkloadProofV1.SigningKeyResolver {

    private final Map<String, HomeMaterializationWorkloadProofV1.VerifiedSigningKey> keys;

    PeopleHomeWorkloadTrust(Environment environment) {
        this(parse(environment.getProperty(
                        PeopleHomeAuthorityPort.PREFIX + "signing-public-jwk", "")),
                authentication(environment.getProperty(
                        PeopleHomeAuthorityPort.PREFIX + "workload-authentication",
                        "CLIENT_CREDENTIAL_JWT")));
    }

    PeopleHomeWorkloadTrust(
            RSAKey key,
            HomeMaterializationWorkloadProofV1.WorkloadAuthentication authentication) {
        if (key == null || authentication == null) {
            throw new IllegalStateException(
                    "Enabled HRM Home producer requires current RS256 workload trust.");
        }
        try {
            RSAPublicKey publicKey = key.toRSAPublicKey();
            this.keys = Map.of(key.getKeyID(),
                    new HomeMaterializationWorkloadProofV1.VerifiedSigningKey(
                            publicKey, authentication,
                            HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID));
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid HRM Home workload public key.", exception);
        }
    }

    @Override
    public HomeMaterializationWorkloadProofV1.VerifiedSigningKey resolve(String keyId) {
        return keys.get(keyId);
    }

    private static RSAKey parse(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 16_384) return null;
        try {
            RSAKey key = RSAKey.parse(raw);
            if (key.isPrivate() || key.size() < 2_048 || key.getKeyID() == null
                    || !key.getKeyID().matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")
                    || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                    || !JWSAlgorithm.RS256.equals(key.getAlgorithm())) return null;
            return key;
        } catch (Exception exception) {
            return null;
        }
    }

    private static HomeMaterializationWorkloadProofV1.WorkloadAuthentication authentication(
            String raw) {
        try {
            return HomeMaterializationWorkloadProofV1.WorkloadAuthentication.valueOf(raw);
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
