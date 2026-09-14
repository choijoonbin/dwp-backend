package com.dwp.services.auth.approvalpolicyimpact;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolicyImpactEvidenceVectorTest {
    final PolicyImpactJson json = new PolicyImpactJson(new com.fasterxml.jackson.databind.ObjectMapper());
    @Test void semanticEvidenceSetOrderDoesNotChangeTheVector() {
        var first = Map.of("duties", List.of(Map.of("members", List.of("B", "A")), Map.of("roles", List.of("D", "C"))));
        var second = Map.of("duties", List.of(Map.of("roles", List.of("C", "D")), Map.of("members", List.of("A", "B"))));
        assertThat(json.evidenceDigest(first)).isEqualTo(json.evidenceDigest(second));
    }
    @Test void sameCountMemberScopeAndExpirySwapsDoChangeTheVector() {
        String original = json.evidenceDigest(Map.of("members", List.of("A", "B"), "scope", "RS_APPROVALS", "expiry", "2026-09-14T06:00:07Z"));
        assertThat(json.evidenceDigest(Map.of("members", List.of("A", "C"), "scope", "RS_APPROVALS", "expiry", "2026-09-14T06:00:07Z"))).isNotEqualTo(original);
        assertThat(json.evidenceDigest(Map.of("members", List.of("A", "B"), "scope", "RS_OTHER", "expiry", "2026-09-14T06:00:07Z"))).isNotEqualTo(original);
        assertThat(json.evidenceDigest(Map.of("members", List.of("A", "B"), "scope", "RS_APPROVALS", "expiry", "2026-09-14T06:00:08Z"))).isNotEqualTo(original);
    }
}
