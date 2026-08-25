package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthApprovalRecoveryAuditorResolverTest {

    private static final UUID OUTBOX_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");

    @Test
    void sendsOnlyTheDedicatedServiceCredentialsAndTypedServerEvidence() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthApprovalRecoveryAuditorResolver resolver =
                new AuthApprovalRecoveryAuditorResolver(
                        builder, "https://auth.example.test", "recovery-secret");
        server.expect(once(), requestTo(
                        "https://auth.example.test/internal/auth/v1/"
                                + "approval-recovery-auditor/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(
                        AuthApprovalRecoveryAuditorResolver.TOKEN_HEADER,
                        "recovery-secret"))
                .andExpect(header(
                        AuthApprovalRecoveryAuditorResolver.SERVICE_IDENTITY_HEADER,
                        AuthApprovalRecoveryAuditorResolver.SERVICE_IDENTITY))
                .andExpect(content().json("""
                        {"tenantId":42,"outboxId":"10000000-0000-0000-0000-000000000001",
                         "originatorUserId":100,"resourceSetKey":"RS_TEAM_A"}
                        """))
                .andRespond(withSuccess("""
                        {"selectedUserId":300,"resourceSetKey":"RS_TEAM_A",
                         "assignmentRevision":"auth-revision-17"}
                        """, MediaType.APPLICATION_JSON));

        ApprovalRecoveryAuditorResolver.Assignment assignment =
                resolver.resolve(42, OUTBOX_ID, 100, "RS_TEAM_A");

        assertThat(assignment).isEqualTo(new ApprovalRecoveryAuditorResolver.Assignment(
                300, "RS_TEAM_A", "auth-revision-17"));
        server.verify();
    }

    @Test
    void treatsNoCandidateAsRetryableAssignmentUnavailability() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthApprovalRecoveryAuditorResolver resolver =
                new AuthApprovalRecoveryAuditorResolver(
                        builder, "https://auth.example.test", "recovery-secret");
        server.expect(once(), requestTo(
                        "https://auth.example.test/internal/auth/v1/"
                                + "approval-recovery-auditor/resolve"))
                .andRespond(withResourceNotFound());

        assertUnavailable(() -> resolver.resolve(
                42, OUTBOX_ID, 100, "RS_TEAM_A"));
        server.verify();
    }

    @Test
    void rejectsOriginatorOrWrongResourceSetReturnedByAuth() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthApprovalRecoveryAuditorResolver resolver =
                new AuthApprovalRecoveryAuditorResolver(
                        builder, "https://auth.example.test", "recovery-secret");
        server.expect(once(), requestTo(
                        "https://auth.example.test/internal/auth/v1/"
                                + "approval-recovery-auditor/resolve"))
                .andRespond(withSuccess("""
                        {"selectedUserId":100,"resourceSetKey":"RS_APPROVALS_B",
                         "assignmentRevision":"auth-revision-17"}
                        """, MediaType.APPLICATION_JSON));

        assertUnavailable(() -> resolver.resolve(
                42, OUTBOX_ID, 100, "RS_TEAM_A"));
        server.verify();
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
    }
}
