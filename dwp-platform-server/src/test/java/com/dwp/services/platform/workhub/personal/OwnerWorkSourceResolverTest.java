package com.dwp.services.platform.workhub.personal;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.mail.MailService;
import com.dwp.services.platform.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.dwp.services.platform.workhub.personal.PersonalWorkDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OwnerWorkSourceResolverTest {
    private final MailService mail = mock(MailService.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final OwnerWorkSourceResolver resolver = new OwnerWorkSourceResolver(workspace, mail);

    @Test
    void remoteBookmarkContainsNoAccessAssertionOrSourceMetadata() {
        var reference = reference("APPROVAL_TASK");
        var result = resolver.resolve(context("APP.WORK:VIEW,APP.APPROVALS:VIEW"), reference).orElseThrow();
        assertThat(result).isEqualTo(new ResolvedSource(reference, null, null, null, null));
        verifyNoInteractions(mail, workspace);
        assertThat(resolver.resolve(context("APP.WORK:VIEW"), reference)).isEmpty();
    }

    @Test
    void serviceReferenceUsesCanonicalSourcePermissionWithoutCreatingMetadataAccess() {
        var reference = reference("SERVICE_REQUEST");
        assertThat(resolver.resolve(context("APP.WORK:VIEW,APP.EMPLOYEE_SERVICES:VIEW"), reference))
                .contains(new ResolvedSource(reference, null, null, null, null));
        assertThat(resolver.resolve(context("APP.WORK:VIEW,APP.SERVICES:VIEW"), reference)).isEmpty();
        verifyNoInteractions(mail, workspace);
    }

    @Test
    void mailReferenceRechecksCurrentTenantOwnerAclAndHidesRevokedSource() {
        var reference = reference("MAIL_THREAD");
        when(mail.thread(1L, 7L, UUID.fromString(reference.sourceReference())))
                .thenThrow(new BaseException(ErrorCode.NOT_FOUND));
        assertThat(resolver.resolve(context("APP.WORK:VIEW,APP.MAIL:VIEW"), reference)).isEmpty();
        verify(mail).thread(1L, 7L, UUID.fromString(reference.sourceReference()));
    }

    @Test
    void unauthorizedOrMalformedSourceCannotTriggerARead() {
        assertThat(resolver.resolve(context("APP.WORK:VIEW"), reference("MAIL_THREAD"))).isEmpty();
        assertThat(resolver.resolve(context("APP.WORK:VIEW,APP.MAIL:VIEW"),
                new SourceReference("MAIL_THREAD", "../../../other", null))).isEmpty();
        verifyNoInteractions(mail, workspace);
    }

    @Test
    void anOwnerOutageMustNotBecomeADeletedSource() {
        var reference = reference("MAIL_THREAD");
        when(mail.thread(1L, 7L, UUID.fromString(reference.sourceReference())))
                .thenThrow(new IllegalStateException("owner unavailable"));
        assertThatThrownBy(() -> resolver.resolve(context("APP.WORK:VIEW,APP.MAIL:VIEW"), reference))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AccessContext context(String permissions) {
        return new AccessContext(1L, 7L, permissions, null, null, "ko");
    }

    private static SourceReference reference(String source) {
        return new SourceReference(source, UUID.randomUUID().toString(), null);
    }
}
