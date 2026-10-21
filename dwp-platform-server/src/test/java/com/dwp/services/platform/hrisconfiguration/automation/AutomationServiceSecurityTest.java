package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Profile;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CatalogPage;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationServiceSecurityTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-21T00:00:00Z");
    private static final UUID SUBJECT =
            UUID.fromString("00000000-0000-4000-8000-000000000631");
    private static final UUID DEFINITION =
            UUID.fromString("00000000-0000-4000-8000-000000000632");
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String E = "e".repeat(64);
    private static final String F = "f".repeat(64);

    private AutomationRepository repository;
    private AutomationRuntimePorts.SignedArtifactVerifierPort verifier;
    private AutomationService service;

    @BeforeEach
    void setUp() {
        repository = mock(AutomationRepository.class);
        verifier = mock(AutomationRuntimePorts.SignedArtifactVerifierPort.class);
        ObjectMapper json = new ObjectMapper();
        AutomationHandlerRegistryPort registry = identity -> Optional.empty();
        service = new AutomationService(
                repository, registry, verifier,
                new AutomationCursorCodec(json,
                        "sys006-cursor-secret-at-least-32-characters"),
                json, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    @Test
    void signedManifestAuthorityCannotBeSubstitutedByCommandFields() {
        AutomationDtos.CreateVersionCommand command = versionCommand();
        DefinitionRecord definition = new DefinitionRecord(
                UUID.randomUUID(), DEFINITION, 42, "payroll-close",
                AutomationDtos.OwnerDomain.PAY, "DRAFT", null, 7, SUBJECT);
        when(repository.lockDefinition(42, DEFINITION)).thenReturn(definition);
        AutomationHandlerManifestV1 substituted = new AutomationHandlerManifestV1(
                "hris.payroll", "dwp-pay-server", "AutomationInvocation.v1",
                "schema:input", "schema:receipt", E,
                List.of("ORGANIZATION"), "FAILED_ITEMS_ONLY", 3, "ACTIVE");
        SignedManifest signed = new SignedManifest(
                substituted, "signing-key-1", F, A, B, 9);
        when(verifier.verifyDraft(command)).thenReturn(new VerifiedArtifact(
                signed, "trusted-registry-r9", D, "compat-v1", C, B,
                NOW.plusMinutes(1)));

        assertThatThrownBy(() -> service.createVersion(
                authority(Set.of(), Set.of()), DEFINITION, command))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("AUTOMATION_SIGNED_ARTIFACT_MISMATCH");

        verify(repository, never()).beginCommand(any(), any());
        verify(repository, never()).insertVersion(any(), any(), any(), any(), any());
    }

    @Test
    void omittedTargetFactsRemovePersistedResourceActionsAndStayRedacted() {
        var source = new AutomationDtos.DefinitionItem(
                "AUTOMATION_DEFINITION", DEFINITION, "payroll-close",
                AutomationDtos.OwnerDomain.PAY, "ACTIVE", 3L, 7L, List.of());
        when(repository.catalog(
                42, NOW, 20,
                Set.of("AUTOMATION_DEFINITION", "AUTOMATION_VERSION",
                        "AUTOMATION_SCHEDULE"), null, null))
                .thenReturn(new CatalogPage(List.of(source), null));
        List<String> fields = List.of(
                "definitionPublicId", "automationKey", "ownerDomain",
                "derivedLifecycleState", "activeVersionNumber", "objectRevision");

        var response = service.catalog(
                authority(Set.of(), Set.of("automationKey")), NOW, fields,
                "FRESH_ONLY", 20, null, null, null);

        var projected = (AutomationDtos.DefinitionItem) response.items().getFirst();
        assertThat(projected.automationKey())
                .isInstanceOf(AutomationDtos.OmittedField.class);
        assertThat(projected.availableActions()).isEmpty();
        assertThat(response.fieldDecisionSummary().omitted())
                .contains("automationKey");
        assertThat(response.fieldDecisionSummary().visible())
                .doesNotContain("automationKey");
    }

    @Test
    void activeScheduleKeepsSafetySuspendWhenPinnedManifestIsUnavailable() {
        UUID scheduleId = UUID.fromString(
                "00000000-0000-4000-8000-000000000633");
        var source = new AutomationDtos.ScheduleItem(
                "AUTOMATION_SCHEDULE", scheduleId, DEFINITION, 3L,
                AutomationDtos.ScheduleState.ACTIVE, "Asia/Seoul",
                NOW.plusHours(1), 9L, List.of());
        when(repository.catalog(
                42, NOW, 20,
                Set.of("AUTOMATION_DEFINITION", "AUTOMATION_VERSION",
                        "AUTOMATION_SCHEDULE"), null, null))
                .thenReturn(new CatalogPage(List.of(source), null));
        when(repository.eligibleCatalogActions(42, SUBJECT, source))
                .thenReturn(Set.of(AutomationService.SUSPEND_SCHEDULE));

        var response = service.catalog(
                authority(Set.of(), Set.of()), NOW, List.of(
                        "schedulePublicId", "definitionPublicId",
                        "pinnedVersionNumber", "lifecycleState", "timezone",
                        "nextFireAt", "objectRevision"),
                "FRESH_ONLY", 20, null, null, null);

        var projected = (AutomationDtos.ScheduleItem) response.items().getFirst();
        assertThat(projected.availableActions())
                .extracting(AutomationDtos.ActionDescriptor::actionKey)
                .containsExactly(AutomationService.SUSPEND_SCHEDULE);
        verify(repository, never()).readVersion(42L, DEFINITION, 3L);
    }

    private static Authority authority(Set<String> masked, Set<String> omitted) {
        Set<Profile> profiles = Set.of(
                new Profile("CONFIG.HRIS_POLICY:UPDATE", "HRIS_CONFIG_AUTHOR",
                        "HRIS_DUTY_POLICY_AUTHOR"),
                new Profile("ACTION.WORKFORCE_DATA_OPERATIONS:EXECUTE",
                        "HRIS_INTEGRATION_EXECUTOR",
                        "HRIS_DUTY_INTEGRATION_EXECUTOR"));
        return new Authority(
                42, 84, SUBJECT, profiles,
                "route.hcm.configuration.automation-catalog.data",
                "psr-" + A, NOW.plusMinutes(5), B, 7, 11,
                "psc-" + C, "tenant:42", UUID.randomUUID(),
                "HRIS_SYSTEM_ADMIN", "sys006-idempotency-key-0001", 7L,
                D, masked, omitted);
    }

    private static AutomationDtos.CreateVersionCommand versionCommand() {
        ObjectMapper json = new ObjectMapper();
        return new AutomationDtos.CreateVersionCommand(
                json.createObjectNode(), A, json.createObjectNode(), B,
                "{tenantId}:{definitionPublicId}", null, B,
                "dwp-pay-server", "hris.payroll", "AutomationInvocation.v1",
                9, D, "signing-key-1", "signature", E,
                "schema:input", F, "schema:receipt", A,
                List.of("TENANT"), AutomationDtos.RetryMode.FAILED_ITEMS_ONLY, 3);
    }
}
