package com.dwp.services.platform.widgetregistry;

import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryManifestContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class Wave4OwnerWidgetRegistryContractTest {

    private static final Path FIXTURE = Path.of(
            "../contracts/widget-registry/wave4-owner-widget-manifests.v1.json");
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V261__seed_wave4_owner_home_widget_providers.sql");

    @Test
    void fixturePinsTwelveUniquePlaceableOwnerContractsAndRealBackendEvidence() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(Files.readString(FIXTURE));
        assertThat(fixture.path("registryMode").asText()).isEqualTo("SHADOW");
        assertThat(fixture.path("runtimeActivationReady").asBoolean()).isFalse();
        assertThat(fixture.path("fixtures")).hasSize(12);
        Set<String> definitions = new HashSet<>();
        Set<String> aliases = new HashSet<>();
        Set<String> renderers = new HashSet<>();
        Set<String> providers = new HashSet<>();
        for (JsonNode item : fixture.path("fixtures")) {
            JsonNode manifest = item.path("manifest");
            String definition = manifest.path("definitionKey").asText();
            String alias = item.path("legacyWidgetKey").asText();
            assertThat(definitions.add(definition)).as(definition).isTrue();
            assertThat(aliases.add(alias)).as(alias).isTrue();
            assertThat(alias).matches("[a-z][a-z0-9-]{0,39}");
            assertThat(renderers.add(
                    manifest.path("renderer").path("rendererKey").asText())).isTrue();
            providers.add(item.path("providerKey").asText());
            assertThat(WidgetRegistryManifestContract.validate(manifest).manifestHash())
                    .isEqualTo(item.path("expectedSha256").asText());
            assertThat(item.path("backendEvidence")).hasSize(3);
            assertThat(java.util.stream.StreamSupport.stream(
                            item.path("backendEvidence").spliterator(), false)
                    .map(evidence -> evidence.path("type").asText()).toList())
                    .containsExactly("MANIFEST", "SECURITY", "PRIVACY");
            for (JsonNode evidence : item.path("backendEvidence")) {
                String type = evidence.path("type").asText();
                if ("MANIFEST".equals(type)) {
                    assertThat(evidence.path("sha256").asText())
                            .isEqualTo(item.path("expectedSha256").asText());
                    continue;
                }
                String reference = evidence.path("ref").asText();
                assertThat(reference).startsWith(
                        "git:6e453915088b0274eb87638cbaa30c468a796d24:");
                String path = reference.substring(reference.indexOf(':', 4) + 1)
                        .replaceFirst("#.*$", "");
                Path artifact = Path.of("..").resolve(path).normalize();
                assertThat(Files.exists(artifact)).as(reference).isTrue();
                assertThat(evidence.path("sha256").asText())
                        .as(reference).isEqualTo(sha256(artifact));
            }
        }
        assertThat(providers).containsExactlyInAnyOrder(
                "approval", "meeting", "notification", "space", "messaging", "people");
    }

    @Test
    void migrationKeepsCertificationPendingAndForbidsInventedFrontendEvidence() throws Exception {
        String migration = Files.readString(MIGRATION);
        assertThat(migration).contains("WAVE4_OWNER_PROVIDER_SHADOW")
                .contains("'NOT_RUN'")
                .contains("migration_mode = 'SHADOW'")
                .contains("runtime_activation_ready = FALSE")
                .doesNotContain("'A11Y'")
                .doesNotContain("'PERFORMANCE'")
                .doesNotContain("'LOCALIZATION'")
                .doesNotContain("'AUTHORITATIVE'");
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
