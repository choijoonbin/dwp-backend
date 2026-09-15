package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.forms.ApprovalFormLifecycleAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

class ApprovalFormLifecycleRouteKindTest {
    final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    final ObjectProvider<HttpServletRequest> requests = mock(ObjectProvider.class);
    final ApprovalFormLifecycleAuthority authority = new ApprovalFormLifecycleAuthority(identities, requests);
    final UUID person = UUID.randomUUID();
    @BeforeEach void setup() {
        var permissions = Set.of("ADMIN.APPROVAL_DESIGN:VIEW", "ADMIN.APPROVAL_DESIGN:UPDATE", "ADMIN.APPROVAL_DESIGN:PUBLISH");
        ApprovalRequestContext.set(99L, 42L, person, Set.of("APP_CONFIG_ADMIN"), permissions);
        ApprovalManagementScopeContext.set("opaque", "RS_APPROVALS");
        var request = new MockHttpServletRequest(); request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        when(requests.getIfAvailable()).thenReturn(request);
        when(identities.require(42, 99)).thenReturn(new ApprovalIdentityDirectory.Subject(42L, 99L, UUID.randomUUID(), person,
                "Fixture", null, null, "ACTIVE", List.of("APP_CONFIG_ADMIN"), permissions.stream().sorted().toList()));
    }
    @AfterEach void clear() {
        ApprovalRequestContext.clear(); ApprovalManagementScopeContext.clear();
        ApprovalDecisionRevisionContext.clear(); ApprovalPilotAuthorizationContext.clear();
    }
    void profile(String leaf, String kind, boolean readOnly) {
        String route = "route.approvals.admin." + leaf;
        ApprovalDecisionRevisionContext.set("psr-" + "a".repeat(64), OffsetDateTime.now().plusMinutes(1), "context", "opaque", route, "110");
        String capability = Set.of("form-reviewed-publish.action", "form-publish-review-reject.action").contains(leaf) ? "approvals.design.publish"
                : leaf.endsWith(".action") ? "approvals.design.update" : "approvals.design.read";
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route, kind, "full-management", readOnly,
                Set.of(), capability, null, null, false, "projection", "schema")));
    }
    void forbidden(String leaf) {
        assertThatThrownBy(() -> authority.require(leaf, "VIEW")).isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }
    @Test void genuineReadOnlyDataKindsPassForAllFormAndPublishReviewReads() {
        for (String leaf : List.of("form-working-draft.data", "form-version-history.data", "form-version-detail.data",
                "form-version-diff.data", "form-publish-review.data", "form-publish-review-candidates.data",
                "form-publish-review-queue.data", "form-publish-review-request.data")) {
            profile(leaf, "DATA", true); assertThat(authority.require(leaf, "VIEW").resourceSetKey()).isEqualTo("RS_APPROVALS");
        }
    }
    @Test void nonReadOnlyDataIsForbiddenBeforeCurrentIdentityLookup() {
        String leaf = "form-version-history.data"; profile(leaf, "DATA", false); forbidden(leaf);
        verify(identities, never()).require(anyLong(), anyLong());
    }
    @Test void readOnlyActionIsForbiddenBeforeCurrentIdentityLookup() {
        String leaf = "form-working-draft-update.action"; profile(leaf, "ACTION", true); forbidden(leaf);
        verify(identities, never()).require(anyLong(), anyLong());
    }
    @Test void actualWriteActionStillPassesWithWriteAuthority() {
        String leaf = "form-working-draft-update.action"; profile(leaf, "ACTION", false);
        assertThat(authority.require(leaf, "VIEW", "UPDATE").resourceSetKey()).isEqualTo("RS_APPROVALS");
    }
    @Test void publishReviewRequestAndDecisionActionsRequireTheirExactCapability() {
        String request = "form-publish-review-request.action"; profile(request, "ACTION", false);
        assertThat(authority.require(request, "VIEW", "UPDATE").resourceSetKey()).isEqualTo("RS_APPROVALS");
        String reject = "form-publish-review-reject.action"; profile(reject, "ACTION", false);
        assertThat(authority.require(reject, "VIEW", "PUBLISH").resourceSetKey()).isEqualTo("RS_APPROVALS");
    }
    @Test void legacyPageAuthorityCannotBeBorrowedForData() {
        String leaf = "form-version-history.data"; profile(leaf, "PAGE", false); forbidden(leaf);
        verify(identities, never()).require(anyLong(), anyLong());
    }
}
