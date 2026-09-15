package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.services.platform.home.HomeExperienceDtos;
import com.dwp.services.platform.home.HomeExperienceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WidgetCatalogDeprecationTest {
    @Test
    void legacyMissingDeadlineDeniesDiscovery() throws Exception {
        assertDecision(null, WidgetRegistryDtos.EffectiveCatalogState.DENY);
    }

    @Test
    void elapsedDeadlineDeniesDiscovery() throws Exception {
        assertDecision(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), WidgetRegistryDtos.EffectiveCatalogState.DENY);
    }

    @Test
    void futureDeadlineKeepsDeprecatedDiscoveryReadOnly() throws Exception {
        assertDecision(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), WidgetRegistryDtos.EffectiveCatalogState.DEPRECATED);
    }

    private void assertDecision(OffsetDateTime deadline, WidgetRegistryDtos.EffectiveCatalogState expected)
            throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode fixture = json.readTree(Files.readString(
                Path.of("../contracts/widget-registry/native-widget-manifests.v1.json")))
                .path("fixtures").get(2);
        UUID definitionId = UUID.fromString("30000000-0000-0000-0000-000000000003");
        UUID versionId = UUID.fromString("31000000-0000-0000-0000-000000000003");
        WidgetDefinition definition = WidgetDefinition.builder().definitionId(definitionId)
                .definitionKey("core.work.focus").ownerProductKey("core.work").definitionState("ACTIVE").build();
        WidgetDefinitionVersion version = WidgetDefinitionVersion.builder().versionId(versionId)
                .definitionId(definitionId).semanticVersion("1.0.0").rendererKey("home.focus")
                .manifest(fixture.path("manifest")).manifestHash(fixture.path("expectedSha256").asText())
                .releaseState("DEPRECATED").safetyState("CLEAR").certificationStatus("NOT_RUN")
                .attestation(json.readTree("{\"source\":\"LEGACY_UNVERIFIED\"}"))
                .deprecationEndsAt(deadline).build();
        WidgetRegistryState state = new WidgetRegistryState();
        state.setMigrationMode("SHADOW");
        state.setRegistryRevision(1L);
        state.setPolicyRevision(1L);
        state.setSafetyRevision(1L);
        WidgetRegistryLedger ledger = mock(WidgetRegistryLedger.class);
        when(ledger.state()).thenReturn(state);
        WidgetDefinitionRepository definitions = mock(WidgetDefinitionRepository.class);
        when(definitions.findAll()).thenReturn(List.of(definition));
        WidgetDefinitionVersionRepository versions = mock(WidgetDefinitionVersionRepository.class);
        when(versions.findById(versionId)).thenReturn(Optional.of(version));
        WidgetRendererBindingRepository bindings = mock(WidgetRendererBindingRepository.class);
        WidgetRendererBinding binding = WidgetRendererBinding.builder().rendererKey("home.focus")
                .kind("NATIVE").bindingRevision(version.getManifestHash()).build();
        when(bindings.findByRendererKeyAndBindingState("home.focus", "ACTIVE")).thenReturn(Optional.of(binding));
        when(bindings.findByBindingStateOrderByRendererKey("ACTIVE")).thenReturn(List.of(binding));
        UUID revisionId = UUID.randomUUID();
        TenantWidgetPolicyHeadRepository heads = mock(TenantWidgetPolicyHeadRepository.class);
        when(heads.findByTenantId(1L)).thenReturn(List.of(TenantWidgetPolicyHead.builder()
                .tenantId(1L).definitionId(definitionId).currentRevisionId(revisionId).build()));
        TenantWidgetPolicyRevisionRepository policies = mock(TenantWidgetPolicyRevisionRepository.class);
        when(policies.findByPolicyRevisionIdAndTenantId(revisionId, 1L)).thenReturn(Optional.of(
                TenantWidgetPolicyRevision.builder().definitionId(definitionId).policyState("PUBLISHED")
                        .enabled(true).selectorType("PINNED").versionId(versionId)
                        .supportedSurfaceKeys(json.readTree("[\"workspace-home\"]"))
                        .audienceSelector(json.readTree("""
                                {"schemaVersion":1,"mode":"ALL_ENTITLED","roleCodes":[],"groupRefs":[]}
                                """)).build()));
        HomeExperienceService home = mock(HomeExperienceService.class);
        HomeExperienceDtos.HomeExperienceResponse experience = mock(HomeExperienceDtos.HomeExperienceResponse.class);
        when(home.get(1L)).thenReturn(experience);
        when(experience.effectiveExperienceVariant()).thenReturn("CLASSIC");
        when(experience.version()).thenReturn(1L);
        WidgetCatalogService catalog = new WidgetCatalogService(ledger, definitions, versions, bindings,
                mock(WidgetReleaseChannelRepository.class), heads, policies,
                mock(WidgetRegistryMutationGuard.class), home, mock(WidgetRegistryDefinitionService.class));

        var item = catalog.effective(1L, "workspace-home", "APP.WORK:VIEW", "", "")
                .contexts().getFirst().items().getFirst();

        assertThat(item.effectiveState()).isEqualTo(expected);
        assertThat(item.placementCapabilities().canAdd()).isFalse();
    }
}
