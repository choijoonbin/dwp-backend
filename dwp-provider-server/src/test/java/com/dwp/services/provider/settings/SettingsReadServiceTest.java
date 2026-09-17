package com.dwp.services.provider.settings;

import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsReadServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-09-17T05:00:00Z");

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void filtersCatalogByTheOwnersExistingReadPermission() {
        SettingsReadService service = service(new StubOwner(definition(
                "provider.example.flag", SettingsContracts.Sensitivity.INTERNAL)));
        setPermissions(Set.of("OTHER_PERMISSION"));

        assertThat(service.catalog(null, null, null)).isEmpty();
        assertThat(service.effective(
                "provider.example.flag", target()).resolutionState())
                .isEqualTo(SettingsContracts.ResolutionState.UNSUPPORTED_SETTING);
    }

    @Test
    void returnsEffectiveValueProvenanceAndAnHonestUnobservedState() {
        SettingsReadService service = service(new StubOwner(definition(
                "provider.example.flag", SettingsContracts.Sensitivity.INTERNAL)));
        setPermissions(Set.of("EXAMPLE_READ"));

        SettingsContracts.Resolution result = service.effective(
                "provider.example.flag", target());

        assertThat(result.resolutionState())
                .isEqualTo(SettingsContracts.ResolutionState.RESOLVED);
        assertThat(result.effectiveValue()).isEqualTo(JSON.valueToTree(true));
        assertThat(result.provenance()).singleElement()
                .extracting(SettingsContracts.Provenance::sourceType)
                .isEqualTo(SettingsContracts.SourceType.PROVIDER_POLICY);
        assertThat(result.applicationStatus().state())
                .isEqualTo(SettingsContracts.ApplicationState.OBSERVATION_UNSUPPORTED);
    }

    @Test
    void redactsSecretValuesButKeepsVersionAndApplicationEvidence() {
        SettingsReadService service = service(new StubOwner(definition(
                "provider.example.secret", SettingsContracts.Sensitivity.SECRET)));
        setPermissions(Set.of("EXAMPLE_READ"));

        SettingsContracts.Resolution result = service.effective(
                "provider.example.secret", target());

        assertThat(result.resolutionState())
                .isEqualTo(SettingsContracts.ResolutionState.REDACTED);
        assertThat(result.effectiveValue()).isNull();
        assertThat(result.effectiveVersion()).isEqualTo("v7");
        assertThat(result.applicationStatus()).isNotNull();
    }

    @Test
    void returnsUnsupportedScopeWithoutInvokingTheOwner() {
        StubOwner owner = new StubOwner(definition(
                "provider.example.flag", SettingsContracts.Sensitivity.INTERNAL));
        SettingsReadService service = service(owner);
        setPermissions(Set.of("EXAMPLE_READ"));

        SettingsContracts.Resolution result = service.effective(
                "provider.example.flag",
                new SettingsContracts.ScopeTarget(
                        SettingsContracts.ScopeType.USER, "42", null));

        assertThat(result.resolutionState())
                .isEqualTo(SettingsContracts.ResolutionState.UNSUPPORTED_SCOPE);
        assertThat(owner.resolveCalls).isZero();
    }

    @Test
    void evaluatesFreshnessAgainstServiceTimeInsteadOfTheOwnerSnapshotTime() {
        Instant old = NOW.minus(Duration.ofHours(1));
        SettingsContracts.Definition definition = definition(
                "provider.example.observed", SettingsContracts.Sensitivity.INTERNAL);
        SettingsContracts.OwnerSnapshot snapshot = new SettingsContracts.OwnerSnapshot(
                JSON.valueToTree(true), "v7",
                List.of(new SettingsContracts.Provenance(
                        0, SettingsContracts.SourceType.PROVIDER_POLICY,
                        SettingsContracts.ScopeType.PROVIDER, "provider", "v7",
                        true, "POLICY_MATCH", old)),
                new SettingsContracts.ApplicationEvidence(
                        "v7", SettingsContracts.DesiredState.PUBLISHED,
                        "v7", old, true, 1,
                        List.of(new SettingsContracts.TargetObservation(
                                "cell-a", SettingsContracts.ObservationState.APPLIED,
                                "v7", old, old, null))),
                old);
        StubOwner owner = new StubOwner(definition, snapshot);
        SettingsReadService service = new SettingsReadService(
                new SettingsDefinitionRegistry(List.of(owner)),
                new SettingsApplicationStatusEvaluator(),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        setPermissions(Set.of("EXAMPLE_READ"));

        SettingsContracts.Resolution result = service.effective(
                "provider.example.observed", target());

        assertThat(result.applicationStatus().state())
                .isEqualTo(SettingsContracts.ApplicationState.OBSERVATION_STALE);
    }

    private SettingsReadService service(SettingsOwnerAdapter owner) {
        return new SettingsReadService(
                new SettingsDefinitionRegistry(List.of(owner)),
                new SettingsApplicationStatusEvaluator());
    }

    private SettingsContracts.Definition definition(
            String id,
            SettingsContracts.Sensitivity sensitivity) {
        return new SettingsContracts.Definition(
                id, "Example setting", "A test-owned setting.",
                new SettingsContracts.Owner(
                        "example-service", "EXAMPLE", "EXAMPLE_READ", "/example/settings"),
                Set.of(SettingsContracts.ScopeType.TENANT),
                new SettingsContracts.ValidationContract(
                        SettingsContracts.ValueType.BOOLEAN,
                        "v1", JSON.createObjectNode().put("type", "boolean"), true),
                sensitivity,
                new SettingsContracts.ChangeContract(
                        "L2", SettingsContracts.ChangeWorkflow.OWNER_MANAGED,
                        false, false),
                SettingsContracts.LifecycleState.ACTIVE,
                3);
    }

    private SettingsContracts.ScopeTarget target() {
        return new SettingsContracts.ScopeTarget(
                SettingsContracts.ScopeType.TENANT,
                "10000000-0000-0000-0000-000000000001",
                "production");
    }

    private void setPermissions(Set<String> permissions) {
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                7L, 8L, 9L, "operator", Set.of("PROVIDER_ADMIN"),
                permissions, UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }

    private static final class StubOwner implements SettingsOwnerAdapter {

        private final SettingsContracts.Definition definition;
        private final SettingsContracts.OwnerSnapshot snapshot;
        private int resolveCalls;

        private StubOwner(SettingsContracts.Definition definition) {
            this(definition, new SettingsContracts.OwnerSnapshot(
                    JSON.valueToTree(true), "v7",
                    List.of(new SettingsContracts.Provenance(
                            0, SettingsContracts.SourceType.PROVIDER_POLICY,
                            SettingsContracts.ScopeType.PROVIDER, "provider", "v7",
                            true, "POLICY_MATCH", NOW)),
                    new SettingsContracts.ApplicationEvidence(
                            "v7", SettingsContracts.DesiredState.PUBLISHED,
                            "v7", NOW, false, 0, List.of()),
                    NOW));
        }

        private StubOwner(
                SettingsContracts.Definition definition,
                SettingsContracts.OwnerSnapshot snapshot) {
            this.definition = definition;
            this.snapshot = snapshot;
        }

        @Override
        public String authority() {
            return "example";
        }

        @Override
        public String readPermission() {
            return "EXAMPLE_READ";
        }

        @Override
        public List<SettingsContracts.Definition> definitions() {
            return List.of(definition);
        }

        @Override
        public SettingsContracts.OwnerSnapshot resolve(
                SettingsContracts.Definition ignored,
                SettingsContracts.ScopeTarget target) {
            resolveCalls++;
            return snapshot;
        }
    }
}
