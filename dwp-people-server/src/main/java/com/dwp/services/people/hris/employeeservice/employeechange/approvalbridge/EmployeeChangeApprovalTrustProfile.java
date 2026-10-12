package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-loaded Approval trust. No proof claim may select its own trust profile. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeApprovalTrustProfile
        implements HrisWorkloadProofV2.SigningKeyResolver,
        HrisWorkloadProofV2.TenantAdmissionVerifier {
    private static final String PREFIX =
            "/internal/hris-people/v1/employee-change-requests/";
    private static final String SUFFIX = "/approval-decisions";

    private final RSAKey approvalKey;
    private final HrisWorkloadProofV2.WorkloadAuthentication approvalAuthentication;
    private final Set<Long> approvalTenantGrants;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    EmployeeChangeApprovalTrustProfile(
            Environment environment,
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.approvalKey = publicKey(environment.getProperty(
                "dwp.hris.employee-change.approval-bridge.approval-public-jwk", ""));
        this.approvalAuthentication = authentication(environment.getProperty(
                "dwp.hris.employee-change.approval-bridge.approval-authentication", ""));
        this.approvalTenantGrants = tenantGrants(environment.getProperty(
                "dwp.hris.employee-change.approval-bridge.approval-tenant-grants", ""));
        if (approvalKey == null || approvalAuthentication == null
                || approvalTenantGrants.isEmpty()) {
            throw new IllegalStateException(
                    "Enabled HRM Approval callback requires a valid Approval RS256 profile and tenant grants.");
        }
    }

    @Override
    public HrisWorkloadProofV2.VerifiedSigningKey resolve(String keyId) {
        if (!approvalKey.getKeyID().equals(keyId)) {
            throw new IllegalArgumentException("Unknown HRIS Approval workload key.");
        }
        try {
            return new HrisWorkloadProofV2.VerifiedSigningKey(
                    approvalKey.toRSAPublicKey(), approvalAuthentication,
                    HrisApprovalBridgeV2.DECISION_CALLBACK.callerWorkloadId());
        } catch (com.nimbusds.jose.JOSEException exception) {
            throw new IllegalStateException(
                    "Configured HRIS Approval workload key is invalid.", exception);
        }
    }

    @Override
    public boolean admits(
            HrisApprovalBridgeV2.Endpoint endpoint,
            String exactPath,
            String callerWorkloadId,
            long signedTenantId) {
        UUID requestPublicId = requestPublicId(exactPath);
        if (!HrisApprovalBridgeV2.DECISION_CALLBACK.equals(endpoint)
                || requestPublicId == null
                || !endpoint.callerWorkloadId().equals(callerWorkloadId)
                || !approvalTenantGrants.contains(signedTenantId)) {
            return false;
        }
        Boolean admitted = transactions.execute(status -> {
            EmployeeChangeTenantScope.setLocal(jdbc, signedTenantId);
            Integer count = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM sys_service_tenants tenant
                      JOIN ppl_employee_change_requests request
                        ON request.tenant_id=tenant.tenant_id
                     WHERE tenant.tenant_id=:tenant
                       AND tenant.lifecycle_state='ACTIVE'
                       AND request.public_id=:request
                    """, new MapSqlParameterSource()
                    .addValue("tenant", signedTenantId)
                    .addValue("request", requestPublicId), Integer.class);
            return count != null && count == 1;
        });
        return Boolean.TRUE.equals(admitted);
    }

    boolean grants(long tenantId) {
        return approvalTenantGrants.contains(tenantId);
    }

    private static UUID requestPublicId(String exactPath) {
        if (!HrisApprovalBridgeV2.DECISION_CALLBACK.matchesPath(exactPath)
                || !exactPath.startsWith(PREFIX) || !exactPath.endsWith(SUFFIX)) {
            return null;
        }
        String raw = exactPath.substring(PREFIX.length(), exactPath.length() - SUFFIX.length());
        try {
            UUID value = UUID.fromString(raw);
            return value.toString().equals(raw) ? value : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static RSAKey publicKey(String json) {
        if (json == null || json.isBlank() || json.length() > 16_384) return null;
        try {
            RSAKey key = RSAKey.parse(json);
            if (key.isPrivate() || key.size() < 2048
                    || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                    || !JWSAlgorithm.RS256.equals(key.getAlgorithm())
                    || key.getKeyID() == null
                    || !key.getKeyID().matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")) {
                return null;
            }
            return key;
        } catch (Exception exception) {
            return null;
        }
    }

    private static HrisWorkloadProofV2.WorkloadAuthentication authentication(String value) {
        try {
            HrisWorkloadProofV2.WorkloadAuthentication profile =
                    HrisWorkloadProofV2.WorkloadAuthentication.valueOf(value);
            return profile == HrisWorkloadProofV2.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT
                    ? profile : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Set<Long> tenantGrants(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        try {
            String[] entries = csv.split(",", -1);
            Set<Long> values = Arrays.stream(entries).map(String::strip)
                    .map(Long::valueOf).filter(value -> value > 0)
                    .collect(Collectors.toUnmodifiableSet());
            return values.size() == entries.length ? values : Set.of();
        } catch (RuntimeException exception) {
            return Set.of();
        }
    }

    /** Per-attempt People signer. Exact request bytes remain stable while proof IDs rotate. */
    static final class ApprovalRequestSigner {
        private final ObjectMapper mapper;
        private final Clock clock;
        private final RSAKey key;

        ApprovalRequestSigner(ObjectMapper mapper, Clock clock, String privateJwk) {
            this.mapper = mapper;
            this.clock = clock;
            this.key = privateKey(privateJwk);
            if (key == null) {
                throw new IllegalStateException(
                        "Enabled HRM Approval relay requires a People RS256 private JWK.");
            }
        }

        String sign(String exactPath, long tenantId, String bodySha256) {
            long issued = clock.instant().getEpochSecond();
            var claims = new TreeMap<String, Object>();
            claims.put("iss", HrisApprovalBridgeV2.CREATE_AND_SUBMIT.callerWorkloadId());
            claims.put("aud", HrisApprovalBridgeV2.CREATE_AND_SUBMIT.ownerWorkloadId());
            claims.put("sub", HrisApprovalBridgeV2.CREATE_AND_SUBMIT.callerWorkloadId());
            claims.put("iat", issued);
            claims.put("nbf", issued);
            claims.put("exp", issued + 30);
            claims.put("jti", UUID.randomUUID().toString());
            claims.put("requestNonce", UUID.randomUUID().toString());
            claims.put("bindingId", HrisApprovalBridgeV2.CREATE_AND_SUBMIT.bindingId());
            claims.put("purpose", HrisApprovalBridgeV2.CREATE_AND_SUBMIT.purpose());
            claims.put("method", HrisApprovalBridgeV2.CREATE_AND_SUBMIT.method());
            claims.put("path", exactPath);
            claims.put("tenantId", tenantId);
            claims.put("requestBodySha256", bodySha256);
            claims.put("authn",
                    HrisWorkloadProofV2.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT.name());
            try {
                var token = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                        new Payload(mapper.writeValueAsString(claims)));
                token.sign(new RSASSASigner(key));
                return token.serialize();
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "HRM Approval request proof could not be signed.", exception);
            }
        }

        private static RSAKey privateKey(String json) {
            if (json == null || json.isBlank() || json.length() > 16_384) return null;
            try {
                RSAKey key = RSAKey.parse(json);
                if (!key.isPrivate() || key.size() < 2048
                        || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                        || !JWSAlgorithm.RS256.equals(key.getAlgorithm())
                        || key.getKeyID() == null
                        || !key.getKeyID().matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}")) {
                    return null;
                }
                return key;
            } catch (Exception exception) {
                return null;
            }
        }
    }
}
