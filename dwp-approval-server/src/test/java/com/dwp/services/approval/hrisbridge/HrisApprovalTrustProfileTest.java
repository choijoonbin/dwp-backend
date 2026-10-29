package com.dwp.services.approval.hrisbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeStoresV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class HrisApprovalTrustProfileTest {
    @Test
    void enabledProfileFailsClosedWithoutRegisteredKeyAndGrants() {
        assertThatThrownBy(() -> new HrisApprovalTrustProfile(
                new MockEnvironment(), mock(NamedParameterJdbcTemplate.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a valid People RS256 profile");
    }

    @Test
    void registeredClientKeyDerivesJwtAuthenticationAndRejectsConfiguredMtlsSimulation()
            throws Exception {
        var publicKey = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256).keyID("people-key-1")
                .generate().toPublicJWK().toJSONString();
        MockEnvironment jwt = new MockEnvironment()
                .withProperty("dwp.approval.hris-bridge.people-public-jwk", publicKey)
                .withProperty("dwp.approval.hris-bridge.people-authentication",
                        "CLIENT_CREDENTIAL_JWT")
                .withProperty("dwp.approval.hris-bridge.people-tenant-grants", "17");
        var profile = new HrisApprovalTrustProfile(
                jwt, mock(NamedParameterJdbcTemplate.class));
        assertThat(profile.resolve("people-key-1").authentication())
                .isEqualTo(HrisWorkloadProofV2.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT);

        MockEnvironment mtls = new MockEnvironment()
                .withProperty("dwp.approval.hris-bridge.people-public-jwk", publicKey)
                .withProperty("dwp.approval.hris-bridge.people-authentication", "MTLS_SPIFFE")
                .withProperty("dwp.approval.hris-bridge.people-tenant-grants", "17");
        assertThatThrownBy(() -> new HrisApprovalTrustProfile(
                mtls, mock(NamedParameterJdbcTemplate.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void callbackSignerMintsFreshExactProofForStablePersistedBody() throws Exception {
        var key = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256).keyID("approval-key-1").generate();
        var mapper = new ObjectMapper().findAndRegisterModules();
        Instant now = Instant.parse("2026-10-12T07:00:00Z");
        var signer = new HrisApprovalTrustProfile.CallbackSigner(
                mapper, Clock.fixed(now, ZoneOffset.UTC), key.toJSONString());
        UUID change = UUID.fromString("00000000-0000-4000-8000-000000000081");
        var callback = new HrisApprovalBridgeV2.ApprovalDecisionCallbackV1(
                UUID.fromString("00000000-0000-4000-8000-000000000082"),
                UUID.fromString("00000000-0000-4000-8000-000000000083"),
                "APPROVED", now, UUID.fromString(
                "00000000-0000-4000-8000-000000000084"), 7,
                UUID.fromString("00000000-0000-4000-8000-000000000085"),
                "a".repeat(64));
        var outbound = HrisApprovalBridgeV2.outbound(mapper,
                HrisApprovalBridgeV2.DECISION_CALLBACK, change, 17, callback);
        String first = signer.sign(outbound.path(), 17, outbound.bodySha256());
        String second = signer.sign(outbound.path(), 17, outbound.bodySha256());
        var publicKey = key.toRSAPublicKey();

        assertThat(first).isNotEqualTo(second);
        var admission = HrisWorkloadProofV2.verify(mapper,
                Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC),
                HrisApprovalBridgeV2.DECISION_CALLBACK.verificationContract(),
                outbound.path(),
                HrisWorkloadProofV2.headers("dwp-approval-server", first),
                outbound.exactBodyBytes(), kid ->
                        new HrisWorkloadProofV2.VerifiedSigningKey(
                                publicKey,
                                HrisWorkloadProofV2.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT,
                                "dwp-approval-server"),
                (endpoint, path, caller, tenant) -> tenant == 17,
                new HrisApprovalBridgeStoresV2.Replay());
        assertThat(admission.commandDigest()).isEqualTo(outbound.commandDigest());
    }
}
