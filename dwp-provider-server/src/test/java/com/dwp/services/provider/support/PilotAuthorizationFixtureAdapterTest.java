package com.dwp.services.provider.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PilotAuthorizationFixtureAdapterTest {

    private final PilotAuthorizationFixtureAdapter adapter =
            new PilotAuthorizationFixtureAdapter();

    @Test
    void projectsTheSameSignedTestCaseIntoTheProviderSupportFixtureDto() {
        PilotAuthorizationFixtureAdapter.ProviderSupportFixture projection =
                adapter.project("PS-A002");

        assertThat(projection.projectionTarget())
                .isEqualTo(PilotAuthorizationFixtureAdapter.ProjectionTarget.PROVIDER_SUPPORT);
        assertThat(projection.fixtureChecksum())
                .isEqualTo(PilotAuthorizationFixtureAdapter.EXPECTED_FIXTURE_CHECKSUM);
        assertThat(projection.testId()).isEqualTo("PS-A002");
        assertThat(projection.fixtureId()).isEqualTo("FX-A-DESIGNER");
        assertThat(projection.expectedOutcome()).isEqualTo("DESIGN_DRAFT_ONLY");
        assertThat(projection.composition())
                .extracting(PilotAuthorizationFixtureAdapter.SourceRecord::reference)
                .containsExactly("AP_WORK_MEMBER", "AP_DESIGN_DRAFT");
        assertThat(projection.composition())
                .extracting(PilotAuthorizationFixtureAdapter.SourceRecord::source)
                .containsOnly(PilotAuthorizationFixtureAdapter.SourceType.COMPONENT);
    }

    @Test
    void exposesCanonicalSourcesWithoutAnAllowGrantOrScopeOutputField() {
        PilotAuthorizationFixtureAdapter.ProviderSupportFixture projection =
                adapter.project("PS-A002");

        assertThat(Arrays.stream(projection.getClass().getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .toList())
                .doesNotContain("allow", "allowed", "grant", "grants", "scope", "scopes",
                        "relationships", "challenges");
        assertThat(projection.composition().get(1).canonicalJson())
                .contains("\"key\":\"AP_DESIGN_DRAFT\"")
                .doesNotContain("\"allowed\"");
    }

    @Test
    void preservesCaseDirectivesAndContractTestReferencesWithoutEvaluatingThem() {
        PilotAuthorizationFixtureAdapter.ProviderSupportFixture directive =
                adapter.project("PS-G008");
        PilotAuthorizationFixtureAdapter.ProviderSupportFixture overridden =
                adapter.project("PS-G006");

        assertThat(directive.composition())
                .contains(new PilotAuthorizationFixtureAdapter.SourceRecord(
                        PilotAuthorizationFixtureAdapter.SourceType.CASE_DIRECTIVE,
                        null,
                        "CASE:RESPONSIBILITY_ONLY_RS_SERVICES",
                        null));
        assertThat(overridden.testRegistryOverrideRef())
                .isEqualTo("test.management-and-app.v1");
    }

    @Test
    void returnsTheExactEarliestRegistryGateForEachCaseAndChallenge() {
        var v1 = new PilotAuthorizationFixtureAdapter.RegistryReference(
                "product-surfaces", 1L,
                "bc34f47b0ad783d27aa7979f25f75e2fdf29506a12a23c0088f94837abad0b67");
        var v2 = new PilotAuthorizationFixtureAdapter.RegistryReference(
                "product-surfaces", 2L,
                "5b634a35472ef98ecdd5ca9efe7a716020d8f3ae0d8f5025d76bbf072692c12c");
        var v3 = new PilotAuthorizationFixtureAdapter.RegistryReference(
                "product-surfaces", 3L,
                "f90c4e3a734204a4619ae77d3476ebc7cc802c43ed8574fcf4f3fc85def67a8e");

        assertThat(adapter.project("PS-C001").registryReference()).isEqualTo(v1);
        assertThat(adapter.project("PS-A003").registryReference()).isEqualTo(v2);
        assertThat(adapter.project("PS-H001").registryReference()).isEqualTo(v3);
        assertThat(adapter.project("PS-G004").registryReference()).isEqualTo(v2);
        assertThat(adapter.project("PS-G013").registryReference()).isEqualTo(v3);

        var approvalChallenge = adapter.project("PS-A003").composition().stream()
                .filter(record -> "stepUpChallenges".equals(record.catalog()))
                .findFirst()
                .orElseThrow();
        assertThat(approvalChallenge.requiredRegistryReference()).isEqualTo(v2);
    }

    @Test
    void failsClosedWhenTheCanonicalTestIdDoesNotResolveExactlyOnce() {
        assertThatThrownBy(() -> adapter.project("PS-A999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolved 0 records");
    }
}
