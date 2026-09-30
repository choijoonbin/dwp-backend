package com.dwp.services.auth.tenantsettingregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.IdentityAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantSettingRegistryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T08:00:00Z");
    private final TenantSettingRegistryRepository repository =
            mock(TenantSettingRegistryRepository.class);
    private final TenantSettingRegistryAuthorization authorization =
            mock(TenantSettingRegistryAuthorization.class);
    private final TenantSettingOwnerAdapter adapter = mock(TenantSettingOwnerAdapter.class);
    private TenantSettingOwnerAdapterRegistry adapters;
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private TenantSettingRegistryService service;

    @BeforeEach
    void setUp() {
        when(adapter.settingKey()).thenReturn("authentication.requireMfa");
        when(adapter.ownerKey()).thenReturn("AUTH_POLICY");
        when(adapter.editorKind()).thenReturn("BOOLEAN");
        when(adapter.tenantEditable()).thenReturn(true);
        when(adapter.sourceUpdatedAt(anyLong())).thenReturn(NOW.minusSeconds(30));
        adapters = new TenantSettingOwnerAdapterRegistry(List.of(adapter));
        service = new TenantSettingRegistryService(
                repository, adapters, authorization, audit, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsAValueDraftWithObservedImpactAndPreview() {
        var owner = owner();
        when(repository.requireOwner("authentication.requireMfa")).thenReturn(owner);
        when(adapter.read(7L)).thenReturn(BooleanNode.FALSE);
        when(repository.activePrincipalCount(7L)).thenReturn(42L);
        when(repository.insert(anyLong(), any(), any(), any(), any(), any(), any(),
                anyLong(), any(), any(), anyLong())).thenReturn(change(
                        "DRAFT", "VALUE", BooleanNode.FALSE, BooleanNode.TRUE,
                        0L, 101L, null));
        when(authorization.can(7L, 101L, "MANAGE")).thenReturn(true);

        var result = service.create(
                7L, 101L, "correlation-create",
                new TenantSettingRegistryDtos.CreateChangeRequest(
                        "authentication.requireMfa", "VALUE", BooleanNode.TRUE,
                        "Require MFA for every tenant identity."));

        assertThat(result.preview().effectiveAfter()).isEqualTo(BooleanNode.TRUE);
        assertThat(result.preview().coverage()).isEqualTo("INTERNAL_AUTH_ACTIVE_IDENTITIES");
        assertThat(result.allowedActions()).containsExactly("SUBMIT");
        verify(repository).event(anyLong(), any(), any(), anyLong(), any(),
                anyLong(), any(), any(), any());
    }

    @Test
    void publishesThroughTheActualOwnerAdapterAndReturnsAReceipt() {
        var owner = owner();
        var approved = change(
                "APPROVED", "VALUE", BooleanNode.FALSE, BooleanNode.TRUE,
                2L, 101L, 102L);
        var published = change(
                "PUBLISHED", "VALUE", BooleanNode.FALSE, BooleanNode.TRUE,
                3L, 101L, 102L);
        when(repository.requireChange(7L, approved.changeId())).thenReturn(approved);
        when(repository.requireOwner(approved.settingKey())).thenReturn(owner);
        when(adapter.read(7L)).thenReturn(BooleanNode.FALSE, BooleanNode.TRUE);
        when(repository.publish(anyLong(), any(), anyLong(), anyLong(), any(), any()))
                .thenReturn(published);

        var result = service.publish(
                7L, 103L, "correlation-publish", approved.changeId(),
                new TenantSettingRegistryDtos.VersionedCommand(2L));

        verify(adapter).apply(7L, BooleanNode.TRUE, 103L);
        assertThat(result.lifecycleState()).isEqualTo("PUBLISHED");
        verify(repository).event(anyLong(), any(), any(), anyLong(), any(),
                anyLong(), any(), any(), any());
    }

    @Test
    void blocksPublisherWhoApprovedTheSameChange() {
        var approved = change(
                "APPROVED", "VALUE", BooleanNode.FALSE, BooleanNode.TRUE,
                2L, 101L, 102L);
        when(repository.requireChange(7L, approved.changeId())).thenReturn(approved);

        assertThatThrownBy(() -> service.publish(
                7L, 102L, null, approved.changeId(),
                new TenantSettingRegistryDtos.VersionedCommand(2L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));
    }

    @Test
    void userReaderUsesCanonicalAuthPolicyPublicationProvenance() {
        var owner = owner();
        when(repository.owners()).thenReturn(List.of(owner));
        when(adapter.read(7L)).thenReturn(BooleanNode.TRUE);
        when(repository.latestCanonicalAuthPolicyPublication(7L))
                .thenReturn(Optional.of(new TenantSettingRegistryRepository.CanonicalAuthPolicyPublication(
                        mapper.createObjectNode().put("requireMfa", true),
                        NOW.minusSeconds(60), UUID.randomUUID())));

        var result = service.effective(7L).getFirst();

        assertThat(result.localizedLabelKey())
                .isEqualTo("managed.effective.settings.authentication.requireMfa.title");
        assertThat(result.effectiveValue()).isEqualTo(BooleanNode.TRUE);
        assertThat(result.effectiveSource()).isEqualTo("OWNER_PUBLICATION");
        assertThat(result.overrideState()).isEqualTo("OWNER_LOCKED");
        assertThat(result.freshnessState()).isEqualTo("FRESH");
        assertThat(result.provenance()).singleElement()
                .satisfies(value -> {
                    assertThat(value.localizedOwnerLabelKey())
                            .isEqualTo("managed.effective.owners.authPolicy");
                    assertThat(value.reason()).isEqualTo("PUBLISHED_AUTH_POLICY");
                });
    }

    @Test
    void marksAuthOwnerDriftWhenObservedValueDiffersFromCanonicalPublication() {
        var owner = owner();
        when(repository.owners()).thenReturn(List.of(owner));
        when(adapter.read(7L)).thenReturn(BooleanNode.TRUE);
        when(repository.latestCanonicalAuthPolicyPublication(7L))
                .thenReturn(Optional.of(new TenantSettingRegistryRepository.CanonicalAuthPolicyPublication(
                        mapper.createObjectNode().put("requireMfa", false),
                        NOW.minusSeconds(60), UUID.randomUUID())));

        var result = service.effective(7L).getFirst();

        assertThat(result.effectiveSource()).isEqualTo("OWNER_CURRENT");
        assertThat(result.overrideState()).isEqualTo("DRIFTED");
        assertThat(result.freshnessState()).isEqualTo("DRIFTED");
        assertThat(result.provenance()).singleElement()
                .satisfies(value -> assertThat(value.reason())
                        .isEqualTo("OWNER_CHANGED_OUTSIDE_WORKFLOW"));
    }

    @Test
    void ownerDescriptorIsReadOnlyAndDriftedForCanonicalAuthMismatch() {
        var owner = ownerLocked();
        when(repository.owners()).thenReturn(List.of(owner));
        when(adapter.read(7L)).thenReturn(BooleanNode.TRUE);
        when(repository.latestCanonicalAuthPolicyPublication(7L))
                .thenReturn(Optional.of(new TenantSettingRegistryRepository.CanonicalAuthPolicyPublication(
                        mapper.createObjectNode().put("requireMfa", false),
                        NOW.minusSeconds(60), UUID.randomUUID())));
        when(authorization.can(7L, 101L, "MANAGE")).thenReturn(true);

        var result = service.owners(7L, 101L).getFirst();

        assertThat(result.overridePolicy()).isEqualTo("OWNER_LOCKED");
        assertThat(result.freshnessState()).isEqualTo("DRIFTED");
        assertThat(result.sourceUpdatedAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(result.allowedActions()).containsExactly("VIEW_EFFECTIVE");
    }

    @Test
    void failsClosedWhenARegisteredTypeHasNoSupportedEditor() {
        var unsupported = new TenantSettingRegistryRepository.OwnerRow(
                "FUTURE_OWNER", 1L, "future.objectSetting", "future", "OBJECT",
                "TENANT_OVERRIDE_OR_OWNER_DEFAULT", "TENANT_ALLOWED", "PUBLISH",
                mapper.createObjectNode().put("hidden", "internal"),
                "managed.effective.settings.future.objectSetting.title",
                "ACTIVE", NOW.minusSeconds(60));
        when(repository.owners()).thenReturn(List.of(unsupported));
        when(authorization.can(7L, 101L, "MANAGE")).thenReturn(true);

        var result = service.owners(7L, 101L).getFirst();

        assertThat(result.editorKind()).isEqualTo("OWNER_ONLY");
        assertThat(result.adapterState()).isEqualTo("UNAVAILABLE");
        assertThat(result.allowedActions()).containsExactly("VIEW_EFFECTIVE");
    }

    private TenantSettingRegistryRepository.OwnerRow owner() {
        return new TenantSettingRegistryRepository.OwnerRow(
                "AUTH_POLICY", 1L, "authentication.requireMfa", "auth", "BOOLEAN",
                "TENANT_OVERRIDE_OR_OWNER_DEFAULT", "TENANT_ALLOWED", "PUBLISH",
                BooleanNode.TRUE,
                "managed.effective.settings.authentication.requireMfa.title",
                "ACTIVE", NOW.minusSeconds(60));
    }

    private TenantSettingRegistryRepository.OwnerRow ownerLocked() {
        var value = owner();
        return new TenantSettingRegistryRepository.OwnerRow(
                value.ownerKey(), value.ownerVersion(), value.settingKey(), value.ownerService(),
                value.valueType(), value.resolutionStrategy(), "OWNER_LOCKED",
                value.activationMode(), value.defaultValue(), value.localizedLabelKey(),
                value.lifecycleState(), value.createdAt());
    }

    private TenantSettingRegistryDtos.Change change(
            String state,
            String desiredState,
            com.fasterxml.jackson.databind.JsonNode before,
            com.fasterxml.jackson.databind.JsonNode proposed,
            long version,
            Long requestedBy,
            Long approvedBy) {
        UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");
        return new TenantSettingRegistryDtos.Change(
                id, "authentication.requireMfa", "AUTH_POLICY", 1L, desiredState,
                before, proposed, state, 42L, "INTERNAL_AUTH_ACTIVE_IDENTITIES", NOW,
                "A sufficiently detailed setting change reason.", requestedBy,
                state.equals("DRAFT") ? null : NOW.minusSeconds(90), approvedBy,
                approvedBy == null ? null : NOW.minusSeconds(30),
                approvedBy == null ? null : "Independent approval completed.",
                state.equals("PUBLISHED") ? 103L : null,
                state.equals("PUBLISHED") ? NOW : null,
                state.equals("PUBLISHED") ? UUID.randomUUID() : null,
                version, NOW.minusSeconds(120), NOW, null, List.of());
    }
}
