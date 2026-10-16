package com.dwp.services.people.hris.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeopleHomeAdmissionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-16T09:10:00Z");
    private static final UUID SUBJECT = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final String REF = "href_abcdefghijklmnop";
    private static final String PATH =
            "/internal/hris-people/v1/home-contributions/" + REF;
    private static KeyPair keys;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
    }

    @Test
    void exactSignedProofIsBoundToCurrentOwnerAuthority() throws Exception {
        var authority = authority(12);
        PeopleHomeAdmissionService service = service(authority, true);

        PeopleHomeAdmissionService.Admission admitted =
                service.admit(PATH, HomeMaterializationWorkloadProofV1.headers(proof(12)));

        assertThat(admitted.authority()).isEqualTo(authority);
        assertThat(admitted.verified().tenantId()).isEqualTo(7);
        assertThat(admitted.verified().subjectPrincipalPublicId()).isEqualTo(SUBJECT);
    }

    @Test
    void changedCurrentPermissionRevisionFailsClosedBeforeReplayReservation() throws Exception {
        PeopleHomeAdmissionService service = service(authority(13), true);

        assertThatThrownBy(() -> service.admit(
                PATH, HomeMaterializationWorkloadProofV1.headers(proof(12))))
                .isInstanceOf(HomeMaterializationWorkloadProofV1.AdmissionDenied.class)
                .extracting(error -> ((HomeMaterializationWorkloadProofV1.AdmissionDenied) error)
                        .reason())
                .isEqualTo("CURRENT_HOME_AUTHORITY_REQUIRED");
    }

    @Test
    void independentlyRejectsAnAdapterThatReturnsMismatchedAuthority() throws Exception {
        PeopleHomeAuthorityPort.CurrentAuthority mismatched = authority(13);
        PeopleHomeAuthorityPort permissiveAdapter = new PeopleHomeAuthorityPort() {
            @Override
            public Optional<CurrentAuthority> current(
                    HomeMaterializationWorkloadProofV1.ProofClaims claims) {
                return Optional.of(mismatched);
            }

            @Override
            public List<CurrentAuthority> currentGrants() {
                return List.of(mismatched);
            }
        };

        assertThatThrownBy(() -> service(permissiveAdapter, true).admit(
                PATH, HomeMaterializationWorkloadProofV1.headers(proof(12))))
                .isInstanceOf(HomeMaterializationWorkloadProofV1.AdmissionDenied.class)
                .extracting(error -> ((HomeMaterializationWorkloadProofV1.AdmissionDenied) error)
                        .reason())
                .isEqualTo("CURRENT_HOME_AUTHORITY_REQUIRED");
    }

    @Test
    void replayAndCallerTenantHeaderAreRejected() throws Exception {
        PeopleHomeAdmissionService replayed = service(authority(12), false);
        assertThatThrownBy(() -> replayed.admit(
                PATH, HomeMaterializationWorkloadProofV1.headers(proof(12))))
                .isInstanceOf(HomeMaterializationWorkloadProofV1.AdmissionDenied.class)
                .extracting(error -> ((HomeMaterializationWorkloadProofV1.AdmissionDenied) error)
                        .reason())
                .isEqualTo("HOME_WORKLOAD_PROOF_REPLAYED");

        List<HomeMaterializationWorkloadProofV1.Header> forbidden = new java.util.ArrayList<>(
                HomeMaterializationWorkloadProofV1.headers(proof(12)));
        forbidden.add(new HomeMaterializationWorkloadProofV1.Header("X-DWP-Tenant-ID", "7"));
        assertThatThrownBy(() -> service(authority(12), true).admit(PATH, forbidden))
                .isInstanceOf(HomeMaterializationWorkloadProofV1.AdmissionDenied.class)
                .extracting(error -> ((HomeMaterializationWorkloadProofV1.AdmissionDenied) error)
                        .reason())
                .isEqualTo("BROWSER_END_USER_OR_TENANT_HEADER_FORBIDDEN");
    }

    private static PeopleHomeAdmissionService service(
            PeopleHomeAuthorityPort.CurrentAuthority authority,
            boolean reserve) {
        PeopleHomeAuthorityPort port = new PeopleHomeAuthorityPort() {
            @Override
            public Optional<CurrentAuthority> current(
                    HomeMaterializationWorkloadProofV1.ProofClaims claims) {
                return authority.exactlyMatches(claims)
                        ? Optional.of(authority) : Optional.empty();
            }

            @Override
            public List<CurrentAuthority> currentGrants() {
                return List.of(authority);
            }
        };
        return service(port, reserve);
    }

    private static PeopleHomeAdmissionService service(
            PeopleHomeAuthorityPort port,
            boolean reserve) {
        HomeMaterializationWorkloadProofV1.SigningKeyResolver resolver = ignored ->
                new HomeMaterializationWorkloadProofV1.VerifiedSigningKey(
                        keys.getPublic(),
                        HomeMaterializationWorkloadProofV1.WorkloadAuthentication
                                .CLIENT_CREDENTIAL_JWT,
                        HomeMaterializationWorkloadProofV1.CALLER_WORKLOAD_ID);
        return new PeopleHomeAdmissionService(
                MAPPER, Clock.fixed(NOW, ZoneOffset.UTC), resolver, port,
                (proofId, nonce, tenant, expiresAt) -> reserve);
    }

    private static PeopleHomeAuthorityPort.CurrentAuthority authority(long permissionRevision) {
        return new PeopleHomeAuthorityPort.CurrentAuthority(
                7, SUBJECT, "hcm.personal.profile.view",
                HrisHomeContributionMaterializationV1.BrowserAudience.SELF,
                HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF,
                11, "a".repeat(64), permissionRevision, 13,
                "people-profile-summary", Set.of(SUBJECT), Map.of(
                "employmentStatus", HrisHomeContributionMaterializationV1.Decision.VIEW,
                "organizationLabel", HrisHomeContributionMaterializationV1.Decision.MASK,
                "pendingActionCount", HrisHomeContributionMaterializationV1.Decision.OMIT));
    }

    private static String proof(long permissionRevision) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", "platform-home-key-v1");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "dwp-platform-server");
        claims.put("aud", "dwp-people-server");
        claims.put("sub", "dwp-platform-server");
        claims.put("iat", NOW.getEpochSecond());
        claims.put("nbf", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(20).getEpochSecond());
        claims.put("jti", "30000000-0000-0000-0000-000000000003");
        claims.put("requestNonce", "40000000-0000-0000-0000-000000000004");
        claims.put("purpose", HrisHomeContributionMaterializationV1.PURPOSE);
        claims.put("method", "GET");
        claims.put("path", PATH);
        claims.put("tenantId", 7);
        claims.put("subjectPrincipalPublicId", SUBJECT.toString());
        claims.put("correlationId", "50000000-0000-0000-0000-000000000005");
        claims.put("capability", "hcm.personal.profile.view");
        claims.put("ownerPopulationScope", "SELF");
        claims.put("scopeRevision", 11);
        claims.put("populationScopeDigest", "a".repeat(64));
        claims.put("effectivePermissionRevision", permissionRevision);
        claims.put("fieldPolicyRevision", 13);
        claims.put("widgetKey", "people-profile-summary");
        claims.put("browserAudience", "SELF");
        claims.put("authn", "CLIENT_CREDENTIAL_JWT");
        String encodedHeader = encode(MAPPER.writeValueAsBytes(header));
        String encodedClaims = encode(MAPPER.writeValueAsBytes(claims));
        String signingInput = encodedHeader + "." + encodedClaims;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + encode(signer.sign());
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
