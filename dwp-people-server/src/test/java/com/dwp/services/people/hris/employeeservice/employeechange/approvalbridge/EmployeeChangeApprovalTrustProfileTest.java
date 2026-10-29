package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeStoresV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

class EmployeeChangeApprovalTrustProfileTest {
    @Test
    void enabledProfileFailsClosedWithoutRegisteredKeyAndGrants() {
        assertThatThrownBy(() -> new EmployeeChangeApprovalTrustProfile(
                new MockEnvironment(), mock(NamedParameterJdbcTemplate.class),
                mock(PlatformTransactionManager.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid Approval RS256 profile");
    }

    @Test
    void registeredClientKeyDerivesJwtAndRejectsConfiguredMtlsSimulation()
            throws Exception {
        String publicKey = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256).keyID("approval-key-1")
                .generate().toPublicJWK().toJSONString();
        MockEnvironment jwt = environment(publicKey, "CLIENT_CREDENTIAL_JWT");
        var profile = new EmployeeChangeApprovalTrustProfile(
                jwt, mock(NamedParameterJdbcTemplate.class),
                mock(PlatformTransactionManager.class));
        assertThat(profile.resolve("approval-key-1").authentication())
                .isEqualTo(HrisWorkloadProofV2.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT);

        assertThatThrownBy(() -> new EmployeeChangeApprovalTrustProfile(
                environment(publicKey, "MTLS_SPIFFE"),
                mock(NamedParameterJdbcTemplate.class),
                mock(PlatformTransactionManager.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requestSignerMintsFreshExactProofForStablePersistedBody() throws Exception {
        var key = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256).keyID("people-key-1").generate();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant now = Instant.parse("2026-10-12T08:00:00Z");
        var signer = new EmployeeChangeApprovalTrustProfile.ApprovalRequestSigner(
                mapper, Clock.fixed(now, ZoneOffset.UTC), key.toJSONString());
        ApprovalRequestV1 body = new ApprovalRequestV1(
                uuid(1), "hris.employee-change.v1", 1,
                "employee-change:" + uuid(2), 3, "person:" + uuid(3),
                "EMPLOYEE_CHANGE_APPLY", "hris-change:" + uuid(2),
                "a".repeat(64), "PENDING", now.minusSeconds(1), uuid(4),
                "employee-change-command-0004");
        var outbound = HrisApprovalBridgeV2.outbound(
                mapper, HrisApprovalBridgeV2.CREATE_AND_SUBMIT, null, 17, body);

        String first = signer.sign(outbound.path(), 17, outbound.bodySha256());
        String second = signer.sign(outbound.path(), 17, outbound.bodySha256());
        var publicKey = key.toRSAPublicKey();

        assertThat(first).isNotEqualTo(second);
        var admission = HrisWorkloadProofV2.verify(
                mapper, Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC),
                HrisApprovalBridgeV2.CREATE_AND_SUBMIT.verificationContract(),
                outbound.path(),
                HrisWorkloadProofV2.headers("dwp-people-server", first),
                outbound.exactBodyBytes(), keyId ->
                        new HrisWorkloadProofV2.VerifiedSigningKey(
                                publicKey,
                                HrisWorkloadProofV2.WorkloadAuthentication
                                        .CLIENT_CREDENTIAL_JWT,
                                "dwp-people-server"),
                (endpoint, path, caller, tenant) -> tenant == 17,
                new HrisApprovalBridgeStoresV2.Replay());
        assertThat(admission.commandDigest()).isEqualTo(outbound.commandDigest());
    }

    private static MockEnvironment environment(String publicKey, String authentication) {
        return new MockEnvironment()
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.approval-public-jwk",
                        publicKey)
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.approval-authentication",
                        authentication)
                .withProperty(
                        "dwp.hris.employee-change.approval-bridge.approval-tenant-grants",
                        "17");
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
