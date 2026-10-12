package com.dwp.services.approval.hrisbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Owner-loaded workload trust; no proof claim can select its own key profile. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hris-bridge", name = "enabled", havingValue = "true")
final class HrisApprovalTrustProfile
        implements HrisWorkloadProofV2.SigningKeyResolver,
        HrisWorkloadProofV2.TenantAdmissionVerifier {
    private final RSAKey peopleKey;
    private final HrisWorkloadProofV2.WorkloadAuthentication peopleAuthentication;
    private final Set<Long> peopleTenantGrants;
    private final NamedParameterJdbcTemplate jdbc;

    HrisApprovalTrustProfile(Environment environment, NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.peopleKey = publicKey(environment.getProperty(
                "dwp.approval.hris-bridge.people-public-jwk", ""));
        this.peopleAuthentication = authentication(environment.getProperty(
                "dwp.approval.hris-bridge.people-authentication", ""));
        this.peopleTenantGrants = tenantGrants(environment.getProperty(
                "dwp.approval.hris-bridge.people-tenant-grants", ""));
        if (peopleKey == null || peopleAuthentication == null || peopleTenantGrants.isEmpty()) {
            throw new IllegalStateException(
                    "Enabled HRIS Approval ingress requires a valid People RS256 profile and tenant grants.");
        }
    }

    @Override
    public HrisWorkloadProofV2.VerifiedSigningKey resolve(String keyId) {
        if (!peopleKey.getKeyID().equals(keyId)) {
            throw new IllegalArgumentException("Unknown HRIS People workload key.");
        }
        try {
            return new HrisWorkloadProofV2.VerifiedSigningKey(
                    peopleKey.toRSAPublicKey(), peopleAuthentication, "dwp-people-server");
        } catch (com.nimbusds.jose.JOSEException exception) {
            throw new IllegalStateException("Configured HRIS People workload key is invalid.", exception);
        }
    }

    @Override
    public boolean admits(HrisApprovalBridgeV2.Endpoint endpoint, String exactPath,
            String callerWorkloadId, long signedTenantId) {
        if (!HrisApprovalBridgeV2.CREATE_AND_SUBMIT.equals(endpoint)
                || !endpoint.path(null).equals(exactPath)
                || !endpoint.callerWorkloadId().equals(callerWorkloadId)
                || !peopleTenantGrants.contains(signedTenantId)) {
            return false;
        }
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM apr_tenants
                 WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                """, new MapSqlParameterSource("tenant", signedTenantId), Integer.class);
        return active != null && active == 1;
    }

    private static RSAKey publicKey(String json) {
        if (json == null || json.isBlank() || json.length() > 16384) return null;
        try {
            RSAKey key = RSAKey.parse(json);
            if (key.isPrivate() || key.size() < 2048 || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                    || !JWSAlgorithm.RS256.equals(key.getAlgorithm()) || key.getKeyID() == null
                    || !key.getKeyID().matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")) return null;
            return key;
        } catch (Exception exception) {
            return null;
        }
    }

    private static HrisWorkloadProofV2.WorkloadAuthentication authentication(String value) {
        try {
            HrisWorkloadProofV2.WorkloadAuthentication profile =
                    HrisWorkloadProofV2.WorkloadAuthentication.valueOf(value);
            // This adapter is a registered client-key profile. MTLS_SPIFFE must
            // be implemented from the live peer certificate, never simulated by config.
            return profile == HrisWorkloadProofV2.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT
                    ? profile : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Set<Long> tenantGrants(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        try {
            Set<Long> values = Arrays.stream(csv.split(",", -1)).map(String::strip)
                    .map(Long::valueOf).filter(value -> value > 0).collect(Collectors.toUnmodifiableSet());
            return values.size() == csv.split(",", -1).length ? values : Set.of();
        } catch (RuntimeException exception) {
            return Set.of();
        }
    }

    /** Per-attempt callback signer; exact body bytes remain stable while proof IDs rotate. */
    static final class CallbackSigner {
        private final ObjectMapper mapper;
        private final Clock clock;
        private final RSAKey key;

        CallbackSigner(ObjectMapper mapper, Clock clock, String privateJwk) {
            this.mapper = mapper;
            this.clock = clock;
            this.key = privateKey(privateJwk);
            if (key == null) {
                throw new IllegalStateException("Enabled HRIS callback relay requires an Approval RS256 private JWK.");
            }
        }

        String sign(String exactPath, long tenantId, String bodySha256) {
            long issued = clock.instant().getEpochSecond();
            var claims = new TreeMap<String, Object>();
            claims.put("iss", HrisApprovalBridgeV2.DECISION_CALLBACK.callerWorkloadId());
            claims.put("aud", HrisApprovalBridgeV2.DECISION_CALLBACK.ownerWorkloadId());
            claims.put("sub", HrisApprovalBridgeV2.DECISION_CALLBACK.callerWorkloadId());
            claims.put("iat", issued);
            claims.put("nbf", issued);
            claims.put("exp", issued + 30);
            claims.put("jti", UUID.randomUUID().toString());
            claims.put("requestNonce", UUID.randomUUID().toString());
            claims.put("bindingId", HrisApprovalBridgeV2.DECISION_CALLBACK.bindingId());
            claims.put("purpose", HrisApprovalBridgeV2.DECISION_CALLBACK.purpose());
            claims.put("method", HrisApprovalBridgeV2.DECISION_CALLBACK.method());
            claims.put("path", exactPath);
            claims.put("tenantId", tenantId);
            claims.put("requestBodySha256", bodySha256);
            claims.put("authn", HrisWorkloadProofV2.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT.name());
            try {
                var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                        new Payload(mapper.writeValueAsString(claims)));
                token.sign(new RSASSASigner(key));
                return token.serialize();
            } catch (Exception exception) {
                throw new IllegalStateException("HRIS callback proof could not be signed.", exception);
            }
        }

        private static RSAKey privateKey(String json) {
            if (json == null || json.isBlank() || json.length() > 16384) return null;
            try {
                RSAKey key = RSAKey.parse(json);
                if (!key.isPrivate() || key.size() < 2048 || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                        || !JWSAlgorithm.RS256.equals(key.getAlgorithm()) || key.getKeyID() == null
                        || !key.getKeyID().matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")) return null;
                return key;
            } catch (Exception exception) {
                return null;
            }
        }
    }
}
