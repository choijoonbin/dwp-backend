package com.dwp.services.notification.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthNotificationRecipientEntitlementDirectoryTest {

    private static final String AUTH_URL = "http://auth.test";
    private static final String SUBJECT_URL =
            AUTH_URL + "/internal/identity/v1/tenants/7/users/11";

    private MockRestServiceServer server;
    private AuthNotificationRecipientEntitlementDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        directory = new AuthNotificationRecipientEntitlementDirectory(
                builder, AUTH_URL, "internal-identity-token");
    }

    @Test
    void returnsTheExactTenantUserPermissionSnapshot() {
        server.expect(requestTo(SUBJECT_URL))
                .andExpect(header(
                        AuthNotificationRecipientEntitlementDirectory.TOKEN_HEADER,
                        "internal-identity-token"))
                .andRespond(withSuccess("""
                        {
                          "tenantId": 7,
                          "userId": 11,
                          "status": "ACTIVE",
                          "identityPlane": "TENANT",
                          "permissionKeys": ["APP.MESSAGING:VIEW"]
                        }
                        """, MediaType.APPLICATION_JSON));

        var subject = directory.find(7L, 11L).orElseThrow();

        assertThat(subject.tenantId()).isEqualTo(7L);
        assertThat(subject.userId()).isEqualTo(11L);
        assertThat(subject.permissionKeys()).containsExactly("APP.MESSAGING:VIEW");
        server.verify();
    }

    @Test
    void treatsMissingUsersAsNotEntitled() {
        server.expect(requestTo(SUBJECT_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(directory.find(7L, 11L)).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"UNAUTHORIZED", "FORBIDDEN"})
    void failsClosedOnRejectedIdentityResponses(HttpStatus status) {
        server.expect(requestTo(SUBJECT_URL))
                .andRespond(withStatus(status));

        assertThatThrownBy(() -> directory.find(7L, 11L))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void failsClosedOnTenantOrUserBindingMismatch() {
        server.expect(requestTo(SUBJECT_URL))
                .andRespond(withSuccess("""
                        {
                          "tenantId": 8,
                          "userId": 11,
                          "status": "ACTIVE",
                          "identityPlane": "TENANT",
                          "permissionKeys": ["APP.MESSAGING:VIEW"]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> directory.find(7L, 11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant or user binding");
        server.verify();
    }

    @Test
    void failsClosedOnUserBindingMismatch() {
        server.expect(requestTo(SUBJECT_URL))
                .andRespond(withSuccess("""
                        {
                          "tenantId": 7,
                          "userId": 12,
                          "status": "ACTIVE",
                          "identityPlane": "TENANT",
                          "permissionKeys": ["APP.MESSAGING:VIEW"]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> directory.find(7L, 11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant or user binding");
        server.verify();
    }

    @Test
    void exposesTransientFailuresForRetryAndCircuitBreakerPolicies() {
        server.expect(requestTo(SUBJECT_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> directory.find(7L, 11L))
                .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    @Test
    void failsClosedWhenAuthCannotBeReached() {
        server.expect(requestTo(SUBJECT_URL))
                .andRespond(request -> {
                    throw new ResourceAccessException("auth timeout");
                });

        assertThatThrownBy(() -> directory.find(7L, 11L))
                .isInstanceOf(ResourceAccessException.class);
        server.verify();
    }
}
