package com.dwp.services.people.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HcmStepUpReplayRepositoryTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final HcmStepUpReplayRepository repository = new HcmStepUpReplayRepository(jdbc);

    @Test
    void insertsTheOneTimeEvidenceInTheCallersTransaction() {
        when(jdbc.update(contains("INSERT INTO ppl_step_up_replay_ledger"),
                any(MapSqlParameterSource.class))).thenReturn(1);

        repository.consume(challenge());

        verify(jdbc).update(contains("INSERT INTO ppl_step_up_replay_ledger"),
                any(MapSqlParameterSource.class));
    }

    @Test
    void duplicateChallengeAndNonceIsAReplayDenial() {
        when(jdbc.update(contains("INSERT INTO ppl_step_up_replay_ledger"),
                any(MapSqlParameterSource.class)))
                .thenThrow(new DuplicateKeyException("uk_ppl_step_up_challenge_nonce"));

        assertThatThrownBy(() -> repository.consume(challenge()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_CHALLENGE_REPLAY));
    }

    @Test
    void replayAuthorityStoreOutageIsReportedAsUnavailable() {
        when(jdbc.update(contains("INSERT INTO ppl_step_up_replay_ledger"),
                any(MapSqlParameterSource.class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        assertThatThrownBy(() -> repository.consume(challenge()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge challenge() {
        ProductSurfaceStepUpChallengeVerifier.CommandBinding binding =
                new ProductSurfaceStepUpChallengeVerifier.CommandBinding(
                        17L, 3L, "route.hcm.management.org-publish.action",
                        "hcm.management", "STEPUP-MGMT-HIGH-V1",
                        "hcm.org-design.publish", "scope-1", "ORG_SCENARIO",
                        "scenario-1", 7, "POST", "/api/people/publish", "idem-1",
                        "a".repeat(64), "psr-" + "b".repeat(64));
        return new ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge(
                "challenge-1", "nonce-1", "issuer", binding,
                Instant.parse("2099-01-01T00:00:00Z"));
    }
}
