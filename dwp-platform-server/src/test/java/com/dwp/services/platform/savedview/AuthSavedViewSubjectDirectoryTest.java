package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthSavedViewSubjectDirectoryTest {

    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 11L;
    private static final String AUTH_URL = "http://auth.test";

    private MockRestServiceServer server;
    private AuthSavedViewSubjectDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        directory = new AuthSavedViewSubjectDirectory(builder, AUTH_URL, "internal-token");
    }

    @Test
    void preservesExactEligibilityEvidenceFromAuthResponses() {
        server.expect(requestTo(AUTH_URL
                        + "/internal/identity/v1/tenants/7/users/11"))
                .andRespond(withSuccess("""
                        {
                          "tenantId": 7,
                          "userId": 11,
                          "publicId": "b77a1472-68bb-4bf3-bb01-1ca2bb7d8996",
                          "personPublicId": "b9bbb9e5-9309-40d6-a796-0c916bd8a79c",
                          "displayName": "Jordan Kim",
                          "email": "jordan@example.com",
                          "jobTitle": "Operations Lead",
                          "status": "ACTIVE",
                          "identityPlane": "TENANT",
                          "roles": ["APP_ACCESS_MANAGER"],
                          "groupRefs": ["dd43f81f-7b90-486b-ab46-394bf05121b5"],
                          "permissionKeys": ["WORKSPACE.WORK:VIEW"]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(AUTH_URL
                        + "/internal/identity/v1/tenants/7/users"
                        + "?query=Jordan&limit=30&activeOnly=true"))
                .andRespond(withSuccess("""
                        [{
                          "tenantId": 7,
                          "userId": 11,
                          "publicId": "b77a1472-68bb-4bf3-bb01-1ca2bb7d8996",
                          "personPublicId": "b9bbb9e5-9309-40d6-a796-0c916bd8a79c",
                          "displayName": "Jordan Kim",
                          "email": "jordan@example.com",
                          "jobTitle": "Operations Lead",
                          "status": "ACTIVE",
                          "identityPlane": "TENANT",
                          "roles": ["APP_ACCESS_MANAGER"],
                          "groupRefs": ["dd43f81f-7b90-486b-ab46-394bf05121b5"],
                          "permissionKeys": ["WORKSPACE.WORK:VIEW"]
                        }]
                        """, MediaType.APPLICATION_JSON));

        SavedViewSubjectDirectory.Subject exact = directory.require(TENANT_ID, USER_ID);
        SavedViewSubjectDirectory.DirectorySubject candidate =
                directory.search(TENANT_ID, " Jordan ", true, 100).getFirst();

        assertThat(exact.hasPermission("workspace.work:view")).isTrue();
        assertThat(candidate.hasCompleteEligibilityEvidence()).isTrue();
        assertThat(candidate.exactSnapshot().hasPermission("WORKSPACE.WORK:VIEW")).isTrue();
        server.verify();
    }

    @Test
    void mapsValidationConnectionFailuresToControlledExternalServiceErrors() {
        server.expect(requestTo(AUTH_URL
                        + "/internal/identity/v1/tenants/7/users/11"))
                .andRespond(request -> {
                    throw new ResourceAccessException("auth is offline");
                });

        assertThatThrownBy(() -> directory.require(TENANT_ID, USER_ID))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR);
                    assertThat(exception.getCause())
                            .isInstanceOf(ResourceAccessException.class);
                });
        server.verify();
    }

    @Test
    void preservesNotFoundForUnknownTenantUsers() {
        server.expect(requestTo(AUTH_URL
                        + "/internal/identity/v1/tenants/7/users/11"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> directory.require(TENANT_ID, USER_ID))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        server.verify();
    }

    @Test
    void mapsSearchConnectionFailuresToControlledExternalServiceErrors() {
        server.expect(requestTo(AUTH_URL
                        + "/internal/identity/v1/tenants/7/users"
                        + "?query=&limit=20&activeOnly=true"))
                .andRespond(request -> {
                    throw new ResourceAccessException("auth is offline");
                });

        assertThatThrownBy(() -> directory.search(TENANT_ID, null, true, 20))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR);
                    assertThat(exception.getCause())
                            .isInstanceOf(ResourceAccessException.class);
                });
        server.verify();
    }
}
