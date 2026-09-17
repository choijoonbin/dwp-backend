package com.dwp.services.notification.api;

import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlMutationRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlImpactPreview;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControlPreviewRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionControls;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextDiscovery;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionContextOption;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRule;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleCreateRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleUpdateRequest;
import com.dwp.services.notification.domain.NotificationAttentionContextDiscoveryService;
import com.dwp.services.notification.domain.NotificationAttentionRuleService;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAttentionControllerTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");

    private final NotificationAttentionRuleService service =
            mock(NotificationAttentionRuleService.class);
    private final NotificationAttentionContextDiscoveryService contextDiscoveryService =
            mock(NotificationAttentionContextDiscoveryService.class);
    private final NotificationAttentionController controller =
            new NotificationAttentionController(service, contextDiscoveryService);

    @AfterEach
    void clearContext() {
        NotificationRequestContext.clear();
    }

    @Test
    void exposesFrontendRuleAndContextMutationContract() {
        UUID ruleId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        AttentionRule rule = rule(ruleId, "1");
        AttentionRuleCreateRequest create = new AttentionRuleCreateRequest(
                "ACTOR", "person:42", "Leader", "PRIORITIZE",
                Map.of("IN_APP", true), null, null, null, true);
        AttentionRuleUpdateRequest update = new AttentionRuleUpdateRequest(
                "ACTOR", "person:42", "Leader", "FOLLOW",
                Map.of(), null, null, "1", true);
        AttentionControlMutationRequest control = new AttentionControlMutationRequest(
                "PRIORITIZE_ACTOR", "PRIORITIZE", null, null, "a".repeat(64));
        AttentionControlPreviewRequest previewRequest = new AttentionControlPreviewRequest(
                "PRIORITIZE_ACTOR", "PRIORITIZE", null);
        AttentionControlImpactPreview preview = new AttentionControlImpactPreview(
                "PRIORITIZE_ACTOR", true, false, "PRIORITIZE",
                null, null, "a".repeat(64), null, null, Instant.now());
        AttentionControls controls = new AttentionControls(
                false, List.of(), null, notificationId,
                "This notification matched your settings.", List.of(), Instant.now());
        when(service.list(ACTOR)).thenReturn(List.of(rule));
        when(service.maxActiveRules(ACTOR)).thenReturn(75);
        when(service.create(ACTOR, create, "create-1")).thenReturn(rule);
        when(service.update(ACTOR, ruleId, update, "update-1"))
                .thenReturn(rule(ruleId, "2"));
        when(service.controls(ACTOR, notificationId)).thenReturn(controls);
        when(service.previewControl(ACTOR, notificationId, previewRequest)).thenReturn(preview);
        when(service.mutateControl(ACTOR, notificationId, control, "control-1"))
                .thenReturn(rule);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.listRules().data().items()).containsExactly(rule);
        assertThat(controller.listRules().data().maxActiveRules()).isEqualTo(75);
        assertThat(controller.createRule("create-1", create).data()).isSameAs(rule);
        assertThat(controller.updateRule(ruleId, "update-1", update).data().version())
                .isEqualTo("2");
        assertThat(controller.attentionControls(notificationId).data().whyReceived())
                .isEqualTo("This notification matched your settings.");
        assertThat(controller.previewAttentionControl(notificationId, previewRequest).data())
                .isSameAs(preview);
        assertThat(controller.applyAttentionControl(
                notificationId, "control-1", control).data())
                .isSameAs(rule);
        assertThat(controller.deleteRule(ruleId, "delete-1", "2").data()).isNull();
        verify(service).delete(ACTOR, ruleId, "2", "delete-1");
    }

    @Test
    void exposesBoundedRecipientOwnedContextDiscovery() {
        AttentionContextOption option = new AttentionContextOption(
                "RESOURCE",
                "PROJECT",
                "project:alpha",
                "Project Alpha",
                Instant.parse("2026-09-16T09:00:00Z"));
        AttentionContextDiscovery discovery = new AttentionContextDiscovery(
                List.of(option), 50, Instant.parse("2026-09-16T10:00:00Z"));
        when(contextDiscoveryService.discover(ACTOR, "RESOURCE", "alpha", 50))
                .thenReturn(discovery);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.discoverContexts("RESOURCE", "alpha", 50).data())
                .isSameAs(discovery);
        verify(contextDiscoveryService).discover(ACTOR, "RESOURCE", "alpha", 50);
    }

    private AttentionRule rule(UUID ruleId, String version) {
        Instant now = Instant.parse("2026-09-16T00:00:00Z");
        return new AttentionRule(
                ruleId, "ACTOR", "person:42", "Leader", "PRIORITIZE",
                Map.of("IN_APP", true), null, null, "USER", false, false,
                true, version, now, now);
    }
}
