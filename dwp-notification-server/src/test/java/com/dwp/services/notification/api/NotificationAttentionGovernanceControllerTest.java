package com.dwp.services.notification.api;

import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.DecisionRequest;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.DraftRequest;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Revision;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Workspace;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceService;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationAttentionGovernanceControllerTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42L, 17L, Set.of("TENANT_ADMIN"),
                    Set.of("ADMIN.NOTIFICATION_POLICY:MANAGE"), false, "dwp-gateway");

    private final NotificationAttentionGovernanceService service =
            mock(NotificationAttentionGovernanceService.class);
    private final NotificationAttentionGovernanceController controller =
            new NotificationAttentionGovernanceController(service);

    @AfterEach
    void clearContext() {
        NotificationRequestContext.clear();
    }

    @Test
    void exposesWorkspaceAndGovernedLifecycleWithoutInventingRuntimeValues() {
        Revision revision = revision();
        Workspace workspace = new Workspace(
                null, List.of(revision), "1", Instant.parse("2026-09-17T00:00:00Z"));
        DraftRequest draft = new DraftRequest(
                revision.settings(), "Protect tenant attention governance", "0");
        DecisionRequest decision = new DecisionRequest("1", "Independent review completed");
        when(service.workspace(ACTOR)).thenReturn(workspace);
        when(service.createDraft(ACTOR, draft, "draft-1")).thenReturn(revision);
        when(service.publish(ACTOR, revision.governanceId(), decision, "publish-1"))
                .thenReturn(revision);
        NotificationRequestContext.set(ACTOR);

        assertThat(controller.workspace().data()).isSameAs(workspace);
        assertThat(controller.createDraft("draft-1", draft).data()).isSameAs(revision);
        assertThat(controller.publish(
                revision.governanceId(), "publish-1", decision).data()).isSameAs(revision);
    }

    private Revision revision() {
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        return new Revision(
                UUID.randomUUID(), "DRAFT",
                new Settings(20, 10, 15, List.of("#sec-soc-alert"), true, 10, true),
                1, "1", "Protect tenant attention governance", 18L, now,
                null, null, 18L, now, null, null);
    }
}
