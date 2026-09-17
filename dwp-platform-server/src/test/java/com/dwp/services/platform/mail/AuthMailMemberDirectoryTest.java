package com.dwp.services.platform.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthMailMemberDirectoryTest {

    private static final String AUTH_URL = "http://auth.test";

    private MockRestServiceServer server;
    private AuthMailMemberDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        directory = new AuthMailMemberDirectory(builder, AUTH_URL, "internal-token");
    }

    @Test
    void exactLookupReturnsOnlyAnActiveTenantIdentityWithAuthoritativeDisplayFields() {
        server.expect(requestTo(AUTH_URL
                        + "/internal/identity/v1/tenants/7/users/11"))
                .andExpect(header("X-DWP-Identity-Sync-Token", "internal-token"))
                .andRespond(withSuccess("""
                        {
                          "tenantId": 7,
                          "userId": 11,
                          "displayName": "Jordan Kim",
                          "email": "jordan@example.com",
                          "department": "People Operations",
                          "status": "ACTIVE",
                          "identityPlane": "TENANT"
                        }
                        """, MediaType.APPLICATION_JSON));

        MailMemberDirectory.MemberIdentity result = directory.requireActive(7, 11);

        assertThat(result.displayName()).isEqualTo("Jordan Kim");
        assertThat(result.department()).isEqualTo("People Operations");
        server.verify();
    }

    @Test
    void crossTenantOrInactiveResponseFailsClosed() {
        server.expect(requestTo(AUTH_URL
                        + "/internal/identity/v1/tenants/7/users/11"))
                .andRespond(withSuccess("""
                        {
                          "tenantId": 8,
                          "userId": 11,
                          "displayName": "Wrong tenant",
                          "status": "ACTIVE",
                          "identityPlane": "TENANT"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> directory.requireActive(7, 11))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SHARED_MEMBER_USER_NOT_ACTIVE");
        server.verify();
    }

    @Test
    void candidateSearchIsTenantScopedActiveOnlyAndBounded() {
        server.expect(requestTo(AUTH_URL
                        + "/internal/identity/v1/tenants/7/users"
                        + "?query=Jordan&activeOnly=true&limit=30"))
                .andRespond(withSuccess("""
                        [{
                          "tenantId": 7,
                          "userId": 11,
                          "displayName": "Jordan Kim",
                          "email": "jordan@example.com",
                          "department": "People Operations",
                          "status": "ACTIVE",
                          "identityPlane": "TENANT"
                        }]
                        """, MediaType.APPLICATION_JSON));

        assertThat(directory.searchActive(7, " Jordan ", 500))
                .extracting(MailMemberDirectory.MemberIdentity::userId)
                .containsExactly(11L);
        server.verify();
    }
}
