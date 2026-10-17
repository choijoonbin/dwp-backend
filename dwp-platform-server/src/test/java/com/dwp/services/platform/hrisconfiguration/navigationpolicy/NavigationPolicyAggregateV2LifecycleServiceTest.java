package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.LifecycleCommand;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.LifecycleState;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.RolloutMode;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.AccessContext;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.CommandContext;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NavigationPolicyAggregateV2LifecycleServiceTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-17T00:00:00Z");

    @Test
    void commandRevalidatesAfterGuardsBeforeMutation() {
        NavigationPolicyAggregateV2Repository repository =
                mock(NavigationPolicyAggregateV2Repository.class);
        when(repository.findReceipt(anyLong(), anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        NavigationPolicyAggregateV2LifecycleService service =
                new NavigationPolicyAggregateV2LifecycleService(
                        repository, request -> request.requestedLegalEmployerPublicId(),
                        evidence -> true, JsonMapper.builder().findAndAddModules().build(),
                        new AdvancingClock(
                                NOW.toInstant(), NOW.plusMinutes(2).toInstant()));
        AggregatePolicyDocument policy =
                NavigationPolicyAggregateV2CanonicalTest.policy(ScopeKind.TENANT, null);
        AggregateDraftCommand command = new AggregateDraftCommand(
                ScopeKind.TENANT, null, null, RolloutMode.ENFORCED,
                policy.workbenches(), 0L, "a".repeat(64));

        assertThatThrownBy(() -> service.createDraft(
                access(), commandContext(), command))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("STALE_AT_COMPLETION");
        verify(repository, never()).createDraft(
                anyLong(), any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void legalEntityLifecycleRevalidatesCurrentManagementOwnershipBeforeTransition() {
        NavigationPolicyAggregateV2Repository repository =
                mock(NavigationPolicyAggregateV2Repository.class);
        LegalEntityContextResolver resolver = mock(LegalEntityContextResolver.class);
        UUID legalEntity = UUID.fromString(
                "00000000-0000-0000-0000-000000000501");
        UUID foreignLegalEntity = UUID.fromString(
                "00000000-0000-0000-0000-000000000999");
        UUID revisionId = UUID.randomUUID();
        AggregatePolicyDocument policy =
                NavigationPolicyAggregateV2CanonicalTest.policy(
                        ScopeKind.LEGAL_ENTITY, legalEntity);
        AggregateRevisionView revision = new AggregateRevisionView(
                UUID.randomUUID(), revisionId, 1, LifecycleState.VALIDATED,
                RolloutMode.ENFORCED, policy, "a".repeat(64), 0, 303,
                null, null, null,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION,
                NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256,
                NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.SHELL_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256,
                NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256,
                null, NOW, NOW);
        when(repository.findReceipt(anyLong(), anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.findByRevisionId(42, revisionId))
                .thenReturn(Optional.of(revision));
        when(resolver.resolve(any())).thenReturn(foreignLegalEntity);
        NavigationPolicyAggregateV2LifecycleService service =
                new NavigationPolicyAggregateV2LifecycleService(
                        repository, resolver, evidence -> true,
                        JsonMapper.builder().findAndAddModules().build(),
                        Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.submit(
                access(), commandContext(
                        "route.hcm.configuration.navigation-policy-v2-draft-submit.action"),
                revisionId, new LifecycleCommand(0L, "a".repeat(64))))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("LEGAL_ENTITY_SCOPE_MISMATCH");
        verify(repository, never()).transition(
                anyLong(), any(), anyLong(), any(), any(), anyLong(), any(), any());
    }

    private AccessContext access() {
        return new AccessContext(
                42, 101, UUID.randomUUID(),
                "APP.HCM:VIEW,CONFIG.HRIS_NAVIGATION_POLICY:UPDATE",
                "TENANT_ADMIN", "hcm-management-context",
                "hcm-scope-" + "a".repeat(40), "decision-7",
                NOW.plusMinutes(1), NavigationPolicyAggregateV2LifecycleService.PURPOSE);
    }

    private CommandContext commandContext() {
        return commandContext(
                "route.hcm.configuration.navigation-policy-v2-draft-create.action");
    }

    private CommandContext commandContext(String route) {
        var authority = new com.dwp.services.platform.security.HcmPlatformPepRegistry
                .RouteAuthority(route, "ACTION", "POST", "/test", "test-profile",
                false, "test-capability", "ENABLED", null);
        return new CommandContext(
                "create-v2-policy-key", UUID.randomUUID(), null, null,
                "POST", "/test", authority, route, "hcm-management-context",
                "hcm-scope-" + "a".repeat(40), "decision-7", "b".repeat(64),
                1, 1);
    }

    private static final class AdvancingClock extends Clock {
        private final List<Instant> instants;
        private int index;

        private AdvancingClock(Instant... instants) {
            this.instants = List.of(instants);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant value = instants.get(Math.min(index, instants.size() - 1));
            index++;
            return value;
        }
    }
}
