package com.dwp.services.notification.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.domain.NotificationModels.PolicyChannelRule;
import com.dwp.services.notification.domain.NotificationModels.PolicyPublishRequest;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicy;
import com.dwp.services.notification.domain.NotificationModels.TenantPolicyChangeRequest;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAdminServiceTest {

    private final NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
    private final NotificationAdminRepository repository = mock(NotificationAdminRepository.class);
    private final NotificationIdempotencyRepository idempotency =
            mock(NotificationIdempotencyRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final NotificationAdminService service =
            new NotificationAdminService(databaseScope, repository, idempotency, audit);
    private final NotificationRequestContext.Actor actor = new NotificationRequestContext.Actor(
            42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");

    @BeforeEach
    void scopeExists() {
        when(repository.policyScopeExists(42L, "APP", "messaging")).thenReturn(true);
        when(repository.latestTenantPolicyVersion(42L, "APP", "messaging")).thenReturn(0L);
        when(repository.effectivePolicy(42L, "APP", "messaging")).thenReturn(Optional.empty());
        when(repository.affectedTypeCount(42L, "APP", "messaging")).thenReturn(3L);
        when(repository.observedRecipients30Days(42L, "APP", "messaging")).thenReturn(120L);
    }

    @Test
    void previewsUserImpactAndGovernanceRiskBeforeCreatingPolicy() {
        TenantPolicyChangeRequest request = changeRequest(
                true,
                true,
                List.of(new PolicyChannelRule("IN_APP", true, "IMMEDIATE", false, 100)));

        var preview = service.previewPolicy(actor, request);

        assertThat(preview.affectedTypeCount()).isEqualTo(3L);
        assertThat(preview.observedRecipients30Days()).isEqualTo(120L);
        assertThat(preview.proposedPolicy().version()).isEqualTo("1");
        assertThat(preview.riskFlags()).containsExactly(
                "MANDATORY_DELIVERY", "QUIET_HOURS_BYPASS", "USER_OVERRIDE_RESTRICTED");
    }

    @Test
    void rejectsUncertifiedExternalDeliveryAtThePolicyBoundary() {
        TenantPolicyChangeRequest request = changeRequest(
                false,
                false,
                List.of(
                        new PolicyChannelRule("IN_APP", true, "IMMEDIATE", true, 100),
                        new PolicyChannelRule("EMAIL", true, "IMMEDIATE", true, 20)));

        assertThatThrownBy(() -> service.previewPolicy(actor, request))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.NOTIFICATION_CAPABILITY_DISABLED));
    }

    @Test
    void preventsThePolicyAuthorFromPublishingTheSameVersion() {
        UUID policyId = UUID.randomUUID();
        TenantPolicy draft = new TenantPolicy(
                policyId,
                "APP",
                "messaging",
                "Messaging",
                "TENANT_POLICY",
                "DRAFT",
                false,
                false,
                "IMMEDIATE",
                List.of(new PolicyChannelRule("IN_APP", true, "IMMEDIATE", true, 100)),
                "Reduce noisy channel notifications",
                actor.userId(),
                null,
                null,
                "1",
                Instant.now());
        when(idempotency.begin(any(), any(), any(), any()))
                .thenReturn(new Request("policy:1", "TENANT_NOTIFICATION_POLICY_PUBLISH", "hash", null));
        when(repository.policy(42L, policyId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.publishPolicy(
                actor,
                policyId,
                new PolicyPublishRequest("1", "Independently reviewed user impact"),
                "policy:1"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(NotificationErrorCode.FORBIDDEN));
    }

    @Test
    void recordsAnExtendedAuditEventWhenAPolicyDraftIsCreated() {
        TenantPolicyChangeRequest request = changeRequest(
                false,
                false,
                List.of(new PolicyChannelRule("IN_APP", true, "IMMEDIATE", true, 100)));
        TenantPolicy draft = new TenantPolicy(
                UUID.randomUUID(),
                "APP",
                "messaging",
                "Messaging",
                "TENANT_POLICY",
                "DRAFT",
                false,
                false,
                "IMMEDIATE",
                request.channels(),
                request.changeReason(),
                actor.userId(),
                null,
                null,
                "1",
                Instant.now());
        when(idempotency.begin(any(), any(), any(), any()))
                .thenReturn(new Request("policy:draft", "TENANT_NOTIFICATION_POLICY_DRAFT", "hash", null));
        when(repository.createPolicyDraft(42L, 17L, request, 1L, null)).thenReturn(draft);

        service.createPolicyDraft(actor, request, "policy:draft");

        verify(audit).record(argThat(event ->
                "notification.policy.draft.created".equals(event.action())
                        && "EXTENDED".equals(event.retentionClass())
                        && draft.policyId().toString().equals(event.targetId())));
    }

    private TenantPolicyChangeRequest changeRequest(
            boolean mandatory,
            boolean quietHoursBypass,
            List<PolicyChannelRule> channels) {
        return new TenantPolicyChangeRequest(
                "APP",
                "messaging",
                mandatory,
                quietHoursBypass,
                "IMMEDIATE",
                channels,
                "Protect user attention with governed routing",
                "0");
    }
}
