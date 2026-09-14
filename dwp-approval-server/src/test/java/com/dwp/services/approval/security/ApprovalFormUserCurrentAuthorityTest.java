package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

class ApprovalFormUserCurrentAuthorityTest {
    private final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final FormBinding form = new FormBinding(42, 99, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64));
    private final OffsetDateTime expiry = OffsetDateTime.now().plusSeconds(55);
    private ApprovalFormUserCurrentAuthority source;
    private Set<String> permissions;

    @BeforeEach void setUp() {
        permissions = Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:CREATE", "ADMIN.APPROVAL_DESIGN:VIEW",
                ApprovalFormUserCurrentAuthority.SOURCE_VIEW);
        actor(permissions);
        when(identities.require(42, 99)).thenReturn(subject(permissions));
        @SuppressWarnings("unchecked") ObjectProvider<HttpServletRequest> requests = mock(ObjectProvider.class);
        when(requests.getIfAvailable()).thenReturn(request);
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        source = new ApprovalFormUserCurrentAuthority(new ApprovalWorkAuthority(identities), identities, requests);
        evidence(false);
    }

    @AfterEach void tearDown() { ApprovalDraftPostgresFixture.clear(); }

    @Test void currentSourceViewAndCreateReturnExactlyOriginalOwnerExpiry() {
        var first = source.requireCurrent(form);
        var second = source.requireCurrent(form);
        assertThat(first).isEqualTo(second);
        assertThat(first.validUntil()).isEqualTo(expiry);
        assertThat(first.routeContractKey()).isEqualTo(ApprovalFormUserCurrentAuthority.WORK_ROUTE);
    }

    @Test void adminRequiresItsOwnExactManagementScopeAndFormPreviewPurpose() {
        evidence(true);
        ApprovalManagementScopeContext.set("scope", "RS_APPROVALS");
        assertThat(source.requireCurrent(form).referencePurpose()).isEqualTo("FORM_PREVIEW");
        ApprovalManagementScopeContext.set("other-scope", "RS_APPROVALS");
        assertForbidden(() -> source.requireCurrent(form));
    }

    @Test void createCannotAutomaticallyGrantDirectorySourceView() {
        Set<String> createOnly = Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:CREATE");
        actor(createOnly);
        when(identities.require(42, 99)).thenReturn(subject(createOnly));
        assertForbidden(() -> source.requireCurrent(form));
    }

    @Test void sourceViewDoesNotReplaceFormCreateAuthority() {
        Set<String> sourceOnly = Set.of("APP.APPROVALS:VIEW", ApprovalFormUserCurrentAuthority.SOURCE_VIEW);
        actor(sourceOnly);
        when(identities.require(42, 99)).thenReturn(subject(sourceOnly));
        assertForbidden(() -> source.requireCurrent(form));
    }

    @Test void permissionRevokeImmediatelyFailsDespiteTrustedPreviousEvidence() {
        source.requireCurrent(form);
        when(identities.require(42, 99)).thenReturn(subject(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:CREATE")));
        assertForbidden(() -> source.requireCurrent(form));
    }

    @ParameterizedTest @ValueSource(strings = {"PROVIDER_SUPPORT", "normal", "NORMAL,ELEVATED", ""})
    void noncanonicalOrSupportAccessModesCannotBorrowTenantDirectory(String mode) {
        request.removeHeader("X-DWP-Active-Access-Mode");
        request.addHeader("X-DWP-Active-Access-Mode", mode);
        assertForbidden(() -> source.requireCurrent(form));
    }

    @Test void duplicateModeAndSupportSessionFailClosed() {
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        assertForbidden(() -> source.requireCurrent(form));
        request.removeHeader("X-DWP-Active-Access-Mode");
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        request.addHeader("X-DWP-Support-Session-ID", UUID.randomUUID().toString());
        assertForbidden(() -> source.requireCurrent(form));
    }

    @Test void foreignBindingAndMissingReferencePredicateAreDenied() {
        assertForbidden(() -> source.requireCurrent(new FormBinding(43, 99, form.formId(), form.formVersionId(), form.schemaSha256())));
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(
                ApprovalFormUserCurrentAuthority.WORK_ROUTE, "DATA", "owner", false, Set.of(), null, null, null, false, null, null)));
        assertForbidden(() -> source.requireCurrent(form));
    }

    @Test void missingOrMalformedTrustedEvidenceIs503() {
        ApprovalDecisionRevisionContext.clear();
        assertThatThrownBy(() -> source.requireCurrent(form)).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        ApprovalDecisionRevisionContext.set(null, expiry, "context", "scope", ApprovalFormUserCurrentAuthority.WORK_ROUTE, "110");
        assertThatThrownBy(() -> source.requireCurrent(form)).isInstanceOf(BaseException.class);
    }

    private void evidence(boolean admin) {
        String route = admin ? ApprovalFormUserCurrentAuthority.ADMIN_ROUTE : ApprovalFormUserCurrentAuthority.WORK_ROUTE;
        String predicate = admin ? "predicate.approval.form-scoped-reference.v1" : "predicate.approval.form-published-reference.v1";
        ApprovalDecisionRevisionContext.set("psr-" + "b".repeat(64), expiry, "context", "scope", route, "110");
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route, "DATA", "owner", false,
                Set.of(predicate), null, null, null, false, null, null)));
    }
    private void actor(Set<String> grants) { ApprovalRequestContext.set(99L, 42L, null, Set.of("APPROVAL_OPERATOR"), grants); }
    private ApprovalIdentityDirectory.Subject subject(Set<String> grants) {
        return new ApprovalIdentityDirectory.Subject(42L, 99L, null, null, "Owner", null, null, "ACTIVE",
                List.of("APPROVAL_OPERATOR"), List.copyOf(grants));
    }
    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
