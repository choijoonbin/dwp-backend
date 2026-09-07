package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.MeetingWorkspaceDtos.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

class MeetingTemplatePostgresTest extends MeetingWorkspacePostgresFixture {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> POSTGRES =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");
    @Override org.testcontainers.containers.PostgreSQLContainer<?> postgres() { return POSTGRES; }
    @Test
    void persistsOrderedAgendaAndIsolatesOtherUsersAndTenants() {
        var created = own(() -> templates.create(input("Planning"), "template-create-1", "corr", false));
        assertThat(created.agendaItems()).extracting(AgendaItem::title).containsExactly("Decision", "Next steps");
        assertThat(own(() -> templates.get(created.templateId(), false))).isEqualTo(created);
        assertThat(as(1, 4, all(), () -> templates.list(TemplateFilter.ALL, "", 0, 30, false)).total()).isZero();
        assertThatThrownBy(() -> as(1, 4, all(), () -> templates.get(created.templateId(), false)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(2, 3, all(), () -> templates.get(created.templateId(), false)))
                .isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_template_revisions")).isOne();
    }

    @Test
    void preservesImmutableRevisionsAndRejectsStaleEdits() {
        var created = own(() -> templates.create(input("First"), "template-create-1", "corr", false));
        var updated = own(() -> templates.update(created.templateId(), new TemplateUpdate(0L, input("Second")),
                "template-update-1", "corr", false));
        assertThat(updated.version()).isOne();
        assertThat(count("vm_meeting_template_revisions")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT snapshot->>'name' FROM vm_meeting_template_revisions WHERE revision = 0",
                String.class)).isEqualTo("First");
        assertThatThrownBy(() -> jdbc.update("UPDATE vm_meeting_template_revisions SET snapshot = '{}'::jsonb"))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> own(() -> templates.update(created.templateId(),
                new TemplateUpdate(0L, input("Stale")), "template-stale-1", "corr", false)))
                .isInstanceOf(BaseException.class);
        own(() -> templates.delete(created.templateId(), 1, "template-delete-1", "corr", false));
        assertThat(count("vm_meeting_template_revisions")).isEqualTo(2);
        assertThat(own(() -> templates.list(TemplateFilter.ALL, "", 0, 30, false)).total()).isZero();
        assertThat(own(() -> templates.delete(created.templateId(), 1, "template-delete-1", "corr", false)).deleted()).isTrue();
    }

    @Test
    void organizationWriteRequiresAdminAndMemberCloneIsPersonal() {
        var org = own(() -> templates.create(input("Approved"), "org-create-001", "corr", true));
        var member = Set.of("APP.MEETINGS:VIEW", "APP.MEETINGS:CREATE", "APP.MEETINGS:UPDATE");
        assertThat(as(1, 4, member, () -> templates.get(org.templateId(), false)).canEdit()).isFalse();
        assertThatThrownBy(() -> as(1, 4, member, () -> templates.update(org.templateId(),
                new TemplateUpdate(0L, input("Overwrite")), "org-overwrite-1", "corr", false)))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> as(1, 4, member, () -> templates.create(input("Injected"),
                "org-create-002", "corr", true))).isInstanceOf(BaseException.class);
        var clone = as(1, 4, member, () -> templates.cloneTemplate(org.templateId(),
                new CloneCommand(0L, "My copy"), "template-clone-1", "corr"));
        assertThat(clone.scope()).isEqualTo(TemplateScope.PERSONAL);
        assertThat(clone.templateId()).isNotEqualTo(org.templateId());
        assertThat(as(1, 4, member, () -> templates.favorite(org.templateId(), new FavoriteCommand(true),
                "template-favorite-1", "corr")).favorite()).isTrue();
        assertThat(own(() -> templates.get(org.templateId(), false)).favorite()).isFalse();
    }

    @Test
    void sameKeyConcurrencyCommitsExactlyOneAggregateAndAudit() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                ready.countDown(); start.await();
                return own(() -> templates.create(input("One"), "concurrent-create", "corr", false));
            });
            var second = executor.submit(() -> {
                ready.countDown(); start.await();
                return own(() -> templates.create(input("One"), "concurrent-create", "corr", false));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).templateId()).isEqualTo(second.get(10, TimeUnit.SECONDS).templateId());
            assertThat(count("vm_meeting_templates")).isOne();
            assertThat(count("vm_meeting_workspace_commands")).isOne();
            assertThat(count("sys_audit_outbox")).isOne();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void changedPayloadCannotReuseKeyAndAuditFailureRollsBackRevisionAndReceipt() {
        own(() -> templates.create(input("One"), "template-create-1", "corr", false));
        assertThatThrownBy(() -> own(() -> templates.create(input("Other"), "template-create-1", "corr", false)))
                .isInstanceOf(BaseException.class);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).workspaceChanged(
                any(), eq("meeting.template.created"), anyString(), anyString(), any(), anyMap());
        assertThatThrownBy(() -> own(() -> templates.create(input("Rollback"), "template-create-2", "corr", false)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("vm_meeting_templates")).isOne();
        assertThat(count("vm_meeting_template_revisions")).isOne();
        assertThat(count("vm_meeting_workspace_commands")).isOne();
    }

    @Test
    void applyProducesOnlySafeDraftAndRevalidatesDisabledPolicy() {
        var created = own(() -> templates.create(input("Planning"), "template-create-1", "corr", false));
        long meetingCount = count("vm_meetings");
        var draft = own(() -> templates.apply(created.templateId(), new VersionCommand(0L), "template-apply-1", "corr"));
        assertThat(draft.sourceTemplateVersion()).isZero();
        assertThat(draft.sourceTemplateId()).isEqualTo(created.templateId());
        assertThat(draft.defaultCameraEnabled()).isFalse();
        assertThat(draft.defaultMicrophoneEnabled()).isFalse();
        assertThat(draft.waitingRoomEnabled()).isTrue();
        assertThat(draft.requiresPolicyRevalidation()).isTrue();
        assertThat(count("vm_meetings")).isEqualTo(meetingCount);
        jdbc.update("UPDATE vm_tenant_policies SET meetings_enabled = FALSE WHERE tenant_id = 1");
        assertThatThrownBy(() -> own(() -> templates.apply(created.templateId(), new VersionCommand(0L),
                "template-apply-1", "corr"))).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT string_agg(payload::text, '') FROM sys_audit_outbox", String.class))
                .doesNotContain("Confidential preparation", "Private objective", "Planning");
    }

    @Test
    void agendaCannotExceedDurationOrCreateCrossTenantFavorite() {
        var invalid = new TemplateInput("Too long", "", "PLANNING", 5,
                List.of(new AgendaItem("Long", "", "", 6)));
        assertThatThrownBy(() -> own(() -> templates.create(invalid, "template-invalid", "corr", false)))
                .isInstanceOf(BaseException.class);
        var created = own(() -> templates.create(input("Planning"), "template-create-1", "corr", false));
        assertThatThrownBy(() -> as(2, 3, all(), () -> templates.favorite(created.templateId(),
                new FavoriteCommand(true), "template-favorite-1", "corr"))).isInstanceOf(BaseException.class);
        assertThat(count("vm_meeting_template_favorites")).isZero();
    }

    @Test
    void serverFiltersFavoritesAndCategoryBeforePaginationAndTotal() {
        var first = own(() -> templates.create(input("First"), "template-first-01", "corr", false));
        own(() -> templates.create(input("Second"), "template-second-01", "corr", false));
        own(() -> templates.favorite(first.templateId(), new FavoriteCommand(true), "favorite-first-1", "corr"));
        var filtered = own(() -> templates.list(TemplateFilter.ALL, "", "PLANNING", true, 0, 1, false));
        assertThat(filtered.total()).isOne();
        assertThat(filtered.items()).extracting(TemplateResponse::templateId).containsExactly(first.templateId());
        assertThat(own(() -> templates.list(TemplateFilter.ALL, "", "OTHER", false, 0, 30, false)).total()).isZero();
        assertThat(as(1, 4, all(), () -> templates.list(TemplateFilter.ALL, "", "PLANNING", true, 0, 30, false)).total()).isZero();
    }
}
