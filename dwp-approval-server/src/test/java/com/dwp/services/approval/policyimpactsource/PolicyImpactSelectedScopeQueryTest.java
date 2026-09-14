package com.dwp.services.approval.policyimpactsource;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyImpactSelectedScopeQueryTest {
    final String scope = "scope-" + "a".repeat(64);
    @Test void permitsDefaultResolvedQueryAndOneExactInstalledOpaqueScope() {
        assertThat(PolicyImpactInstalledContext.queryMatches("expectedVersion=0", 0, scope)).isTrue();
        assertThat(PolicyImpactInstalledContext.queryMatches("expectedVersion=7&contextScopeKey=" + scope, 7, scope)).isTrue();
        assertThat(PolicyImpactInstalledContext.queryMatches("expectedVersion=9007199254740991&contextScopeKey=" + scope, 9007199254740991L, scope)).isTrue();
    }
    @Test void rejectsWrongScopeDuplicatesReorderingAliasesAdditionalFieldsAndMalformedUtf8OrPercents() {
        for (String query : List.of("expectedVersion=7&contextScopeKey=scope-" + "b".repeat(64),
                "expectedVersion=7&contextScopeKey=" + scope + "&contextScopeKey=" + scope,
                "contextScopeKey=" + scope + "&expectedVersion=7", "expectedVersion=7&contextScopeKey=%73" + scope.substring(1),
                "expectedVersion=7&contextScope%4bey=" + scope, "expectedVersion=7&contextScopeKey=" + scope + "&unknown=true",
                "expectedVersion=7&contextScopeKey=%FF", "expectedVersion=7&contextScopeKey=%", "expectedVersion=7&contextScopeKey=%C0%AF",
                "expectedVersion=7&contextScopeKey=" + scope + "+", "expectedVersion=7&contextScopeKey=", "expectedVersion=07", "expectedVersion=7.0", "expectedVersion=7e0"))
            assertThat(PolicyImpactInstalledContext.queryMatches(query, 7, scope)).as(query).isFalse();
    }
    @Test void unsafeMissingOrNegativeVersionAndMissingSelectedScopeFailClosed() {
        assertThat(PolicyImpactInstalledContext.queryMatches(null, 7, scope)).isFalse();
        assertThat(PolicyImpactInstalledContext.queryMatches("expectedVersion=-1", -1, scope)).isFalse();
        assertThat(PolicyImpactInstalledContext.queryMatches("expectedVersion=9007199254740992", 9007199254740992L, scope)).isFalse();
        assertThat(PolicyImpactInstalledContext.queryMatches("expectedVersion=7&contextScopeKey=" + scope, 7, null)).isFalse();
    }
}
