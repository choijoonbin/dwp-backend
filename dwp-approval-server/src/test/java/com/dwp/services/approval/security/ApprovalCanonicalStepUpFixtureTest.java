package com.dwp.services.approval.security;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.support.PilotAuthorizationFixtureAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalCanonicalStepUpFixtureTest {

    private static final String EMPTY_OBJECT_SHA256 =
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PilotAuthorizationFixtureAdapter fixtures =
            new PilotAuthorizationFixtureAdapter();

    @Test
    void allApprovalHighCommandsUseRealCommandBoundRs256FixtureChallenges() throws Exception {
        for (String testId : List.of("PS-A003", "PS-A004", "PS-A013", "PS-A014")) {
            var fixture = fixtures.project(testId);
            var challenge = fixture.stepUpChallenge();
            ApprovalStepUpVerifier verifier = verifier(fixture);
            JsonNode payload = objectMapper.readTree(challenge.payloadCanonicalJson());
            var binding = binding(challenge);

            assertThat(verifier.payloadSha256(payload)).isEqualTo(challenge.payloadSha256());
            assertThat(challenge.stepUpCommandBindingKey())
                    .isEqualTo(challenge.commandContractKey() + ".binding.01");
            assertThat(challenge.targetIdSource()).isEqualTo("PATH_PARAMETER");
            assertThat(challenge.targetIdPathParameter()).isNotBlank();
            assertThat(challenge.targetIdBodyFields()).isEmpty();
            ApprovalStepUpVerifier.VerifiedChallenge verified =
                    verifier.verify(challenge.compactToken(), binding);

            assertThat(verified.challengeId()).isEqualTo(challenge.challengeId());
            assertThat(verified.nonce()).isEqualTo(challenge.nonce());
            assertThat(verified.binding()).isEqualTo(binding);
        }
    }

    @Test
    void recoveryChallengeUsesTheCanonicalEmptyObjectDigest() {
        var fixture = fixtures.project("PS-A004");
        var challenge = fixture.stepUpChallenge();
        ApprovalStepUpVerifier verifier = verifier(fixture);

        assertThat(challenge.payloadCanonicalJson()).isEqualTo("{}");
        assertThat(challenge.expectedObjectVersionSource()).isEqualTo("COMMAND_HEADER");
        assertThat(challenge.expectedObjectVersionName())
                .isEqualTo("X-DWP-Expected-Object-Version");
        assertThat(challenge.payloadSha256())
                .isEqualTo(EMPTY_OBJECT_SHA256)
                .isEqualTo(verifier.payloadSha256(Map.of()));
        assertThat(verifier.payloadSha256(Map.of(
                "expectedVersion", challenge.targetVersion())))
                .isNotEqualTo(challenge.payloadSha256());
    }

    @Test
    void rejectsCrossTargetAndChallengeSwapAgainstTheCanonicalCommandBinding() throws Exception {
        var workflow = fixtures.project("PS-A003");
        var form = fixtures.project("PS-A014");
        var workflowChallenge = workflow.stepUpChallenge();
        var formBinding = binding(form.stepUpChallenge());
        var crossTarget = new ApprovalStepUpVerifier.CommandBinding(
                workflowChallenge.actorUserId(), workflowChallenge.tenantId(),
                workflowChallenge.commandContractKey(), workflowChallenge.contextKey(),
                workflowChallenge.policy(), workflowChallenge.capabilityContractKey(),
                workflowChallenge.scopeRef(), workflowChallenge.targetType(),
                "00000000-0000-0000-0000-000000000099",
                workflowChallenge.targetVersion(), workflowChallenge.method(),
                workflowChallenge.path(), workflowChallenge.idempotencyKey(),
                workflowChallenge.payloadSha256(), workflowChallenge.decisionRevision());

        assertThatThrownBy(() -> verifier(workflow).verify(
                workflowChallenge.compactToken(), crossTarget))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> verifier(workflow).verify(
                workflowChallenge.compactToken(), formBinding))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void theRealReplayRepositoryConsumesEachCanonicalChallengeOnlyOnce() {
        Set<String> consumed = new LinkedHashSet<>();
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class)))
                .thenAnswer(invocation -> {
                    MapSqlParameterSource params = invocation.getArgument(1);
                    return consumed.contains(params.getValue("challengeId") + "@"
                            + params.getValue("nonce"));
                });
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenAnswer(invocation -> {
                    MapSqlParameterSource params = invocation.getArgument(1);
                    String key = params.getValue("challengeId") + "@" + params.getValue("nonce");
                    if (!consumed.add(key)) throw new DuplicateKeyException("replay");
                    return 1;
                });
        ApprovalStepUpReplayRepository replay = new ApprovalStepUpReplayRepository(jdbc);
        var fixture = fixtures.project("PS-A003");
        var challenge = fixture.stepUpChallenge();
        ApprovalStepUpVerifier.VerifiedChallenge verified =
                verifier(fixture).verify(challenge.compactToken(), binding(challenge));

        replay.assertNotConsumed(verified.challengeId(), verified.nonce());
        replay.consume(verified);

        assertThatThrownBy(() -> replay.assertNotConsumed(
                verified.challengeId(), verified.nonce()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> replay.consume(verified))
                .isInstanceOf(BaseException.class);
    }

    private ApprovalStepUpVerifier verifier(
            PilotAuthorizationFixtureAdapter.ApprovalPepFixture fixture) {
        var verification = fixture.stepUpChallenge().verification();
        return new ApprovalStepUpVerifier(
                objectMapper,
                Clock.fixed(fixture.fixedClock(), ZoneOffset.UTC),
                publicKey(verification.publicKeyPem()),
                verification.issuer(),
                verification.audience(),
                verification.keyId(),
                verification.requiredAcr(),
                600,
                900);
    }

    private ApprovalStepUpVerifier.CommandBinding binding(
            PilotAuthorizationFixtureAdapter.ApprovalStepUpFixture challenge) {
        return new ApprovalStepUpVerifier.CommandBinding(
                challenge.actorUserId(), challenge.tenantId(),
                challenge.commandContractKey(), challenge.contextKey(), challenge.policy(),
                challenge.capabilityContractKey(), challenge.scopeRef(), challenge.targetType(),
                challenge.targetId(), challenge.targetVersion(), challenge.method(), challenge.path(),
                challenge.idempotencyKey(), challenge.payloadSha256(), challenge.decisionRevision());
    }

    private PublicKey publicKey(String pem) {
        try {
            String encoded = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception exception) {
            throw new IllegalStateException("Fixture public key is invalid.", exception);
        }
    }
}
