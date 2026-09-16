package com.dwp.services.auth.support;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PilotAuthorizationFixtureAdapterTest {

    @Test
    void loadsV16LineageWithoutChangingTheFixedCaseAuthority() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "product-authorization/pilot-fixtures.v1.generated.json")) {
            assertThat(input).isNotNull();
            var fixture = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            assertThat(fixture.path("fixtureChecksum").asText())
                    .isEqualTo("6beb31fcc7addc5044620f37d37907d4bb9f6e1d4c630a164e823840d885cb27");
            assertThat(fixture.path("registryLineage").path("latestAliasVersion").asInt()).isEqualTo(16);
            assertThat(fixture.path("registryLineage").path("versions").size()).isEqualTo(16);
            assertThat(fixture.path("registryLineage").path("versions").get(9).path("sha256").asText())
                    .isEqualTo("1f97638c95a192f0ec7f01053c3965f79b7a3ee4eb9781ea56e3cf8eccc6889b");
            assertThat(fixture.path("registryLineage").path("versions").get(8).path("sha256").asText())
                    .isEqualTo("02b19c4119e560b63d4054ec317fe7e4d694e402a5af03960c63b20db4b41ab7");
            assertThat(fixture.path("registryLineage").path("versions").get(7).path("sha256").asText())
                    .isEqualTo("9449a516a2dbd96106e71963cbda764b80d83f0f61fa861d110d85517adac942");
            assertThat(fixture.path("registryLineage").path("versions").get(6).path("sha256").asText())
                    .isEqualTo("fe9721ef01164c64e03f8798f89765bdf35e55993cf98ad1f6f9c3611dd8d61a");
        }
        assertThat(adapter.project("PS-A003").registryReference().version()).isEqualTo(2);
        assertThat(adapter.project("PS-H001").registryReference().version()).isEqualTo(3);
    }

    @Test
    void rejectsChangedNegativeCaseContentEvenWithARecomputedCatalogChecksum() throws Exception {
        var mapper = org.mockito.Mockito.spy(new com.fasterxml.jackson.databind.ObjectMapper());
        com.fasterxml.jackson.databind.node.ObjectNode changed;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "product-authorization/pilot-fixtures.v1.generated.json")) {
            changed = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(input);
        }
        var negative = (com.fasterxml.jackson.databind.node.ObjectNode) changed.path("negativeCases").get(0);
        negative.put("input", negative.path("input").asText() + ":changedContent");
        changed.remove("fixtureChecksum");
        var hash = PilotAuthorizationFixtureAdapter.class.getDeclaredMethod(
                "sha256", com.fasterxml.jackson.databind.JsonNode.class);
        hash.setAccessible(true);
        changed.put("fixtureChecksum", (String) hash.invoke(adapter, changed));
        org.mockito.Mockito.doReturn(changed).when(mapper)
                .readTree(org.mockito.ArgumentMatchers.any(java.io.InputStream.class));
        assertThatThrownBy(() -> new PilotAuthorizationFixtureAdapter(mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unexpected pilot fixture checksum");
    }

    private final PilotAuthorizationFixtureAdapter adapter =
            new PilotAuthorizationFixtureAdapter();

    @Test
    void projectsTheSameSignedTestCaseIntoTheAuthContextFixtureDto() {
        PilotAuthorizationFixtureAdapter.AuthContextFixture projection =
                adapter.project("PS-A002");

        assertThat(projection.projectionTarget())
                .isEqualTo(PilotAuthorizationFixtureAdapter.ProjectionTarget.AUTH_CONTEXT);
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
        PilotAuthorizationFixtureAdapter.AuthContextFixture projection =
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
        PilotAuthorizationFixtureAdapter.AuthContextFixture directive =
                adapter.project("PS-G008");
        PilotAuthorizationFixtureAdapter.AuthContextFixture overridden =
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
