package com.dwp.core.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HcmEligibilityScopeKeyTest {

    @Test
    void preservesThePeopleOwnedDerivedScopeEncoding() {
        String key = HcmEligibilityScopeKey.derived(
                1L,
                900018L,
                "hcm.personal",
                "scope-e8d22a5b84de795fa1265d38d7f7bf7f",
                "worker-rev-1",
                "self:18:worker-rev-1");

        assertThat(key)
                .isEqualTo("hcm-scope-c91ebf9e034f694fc1aee6d54b86ae2e7e2c6b0a");
        assertThat(HcmEligibilityScopeKey.isCanonical(key)).isTrue();
    }

    @Test
    void canonicalValidationRejectsSourceScopesAndMalformedDerivedEvidence() {
        assertThat(HcmEligibilityScopeKey.isCanonical(
                "scope-e8d22a5b84de795fa1265d38d7f7bf7f")).isFalse();
        assertThat(HcmEligibilityScopeKey.isCanonical(
                "hcm-scope-" + "A".repeat(40))).isFalse();
        assertThat(HcmEligibilityScopeKey.isCanonical(
                "hcm-scope-" + "a".repeat(39))).isFalse();
        assertThat(HcmEligibilityScopeKey.isCanonical(
                "hcm-scope-" + "a".repeat(41))).isFalse();
        assertThat(HcmEligibilityScopeKey.isCanonical(null)).isFalse();
    }

    @Test
    void derivationRejectsUnboundOrNonCanonicalMaterial() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                HcmEligibilityScopeKey.derived(
                        0L, 900018L, "hcm.personal", "scope-source", "rel", "pop"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                HcmEligibilityScopeKey.derived(
                        1L, 900018L, " hcm.personal", "scope-source", "rel", "pop"));
    }
}
