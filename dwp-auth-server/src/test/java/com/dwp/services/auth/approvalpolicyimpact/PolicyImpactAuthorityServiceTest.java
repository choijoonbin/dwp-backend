package com.dwp.services.auth.approvalpolicyimpact;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.exception.BaseException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyImpactAuthorityServiceTest {
    final PolicyImpactProtocolFixture fixture = new PolicyImpactProtocolFixture();
    final PolicyImpactAuthorityPort authority = mock(PolicyImpactAuthorityPort.class);
    final PolicyImpactReplayStore replay = mock(PolicyImpactReplayStore.class);
    final PolicyImpactAuthorityIssuer issuer = mock(PolicyImpactAuthorityIssuer.class);
    PolicyImpactAuthorityPort.Current current(String vector, int seconds) {
        var expires = PolicyImpactProtocolFixture.NOW.plusSeconds(seconds);
        var grants = PolicyImpactProtocol.REQUIRED.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new PolicyImpactAuthorityPort.Grant(entry.getKey(), entry.getValue(), "RS_APPROVALS", "opaque-original", expires)).toList();
        return new PolicyImpactAuthorityPort.Current("auth-" + "c".repeat(64), "policy-9-1-" + "d".repeat(64),
                "apia-" + vector, vector, PolicyImpactProtocolFixture.NOW, expires, grants);
    }
    PolicyImpactProofVerifier.Verified proof() {
        var exchange = fixture.exchange(fixture.binding()); return fixture.verifier().verify(exchange.body(), exchange.token());
    }
    PolicyImpactAuthorityService service() { return new PolicyImpactAuthorityService(fixture::verifier, authority, replay, issuer, true); }
    @Test void usesActualShortestAuthorityExpiryForAtomicReplayAndChecksAgainAfterConsumption() {
        var proof = proof(); var current = current("e".repeat(64), 7); when(authority.requireCurrent(proof)).thenReturn(current);
        when(issuer.issue(proof, current)).thenReturn("signed"); assertThat(service().evaluate(proof)).isEqualTo("signed");
        var order = inOrder(authority, replay, issuer);
        order.verify(authority).requireRegistered(); order.verify(replay).requireReady();
        order.verify(authority, times(2)).requireCurrent(proof); order.verify(replay).consume(proof, current.expiresAt());
        order.verify(authority).requireCurrent(proof); order.verify(issuer).issue(proof, current);
    }
    @Test void sameCountScopeSwapBeforeReplayCannotIssueOrConsume() {
        var proof = proof(); when(authority.requireCurrent(proof)).thenReturn(current("a".repeat(64), 7), current("b".repeat(64), 7));
        assertThatThrownBy(() -> service().evaluate(proof)).isInstanceOf(BaseException.class);
        verify(replay, never()).consume(any(), any()); verifyNoInteractions(issuer);
    }
    @Test void revocationDuringRedisConsumptionCannotIssue() {
        var proof = proof(); when(authority.requireCurrent(proof)).thenReturn(current("a".repeat(64), 7), current("a".repeat(64), 7))
                .thenThrow(PolicyImpactJson.denied());
        assertThatThrownBy(() -> service().evaluate(proof)).isInstanceOf(BaseException.class); verifyNoInteractions(issuer);
    }
    @Test void missingRedisFailsBeforeCurrentAuthReadAndNeverFallsBack() {
        doThrow(PolicyImpactJson.unavailable()).when(replay).requireReady();
        assertThatThrownBy(() -> service().evaluate(proof())).isInstanceOf(BaseException.class);
        verify(authority, never()).requireCurrent(any()); verifyNoInteractions(issuer);
    }
    @Test void replayConflictCannotIssueEvenWithCurrentGrants() {
        var proof = proof(); when(authority.requireCurrent(proof)).thenReturn(current("a".repeat(64), 7));
        doThrow(PolicyImpactJson.denied()).when(replay).consume(eq(proof), any());
        assertThatThrownBy(() -> service().evaluate(proof)).isInstanceOf(BaseException.class); verifyNoInteractions(issuer);
    }
}
