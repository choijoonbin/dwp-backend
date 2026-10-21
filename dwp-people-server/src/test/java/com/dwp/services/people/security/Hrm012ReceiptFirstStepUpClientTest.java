package com.dwp.services.people.security;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.Hrm012OwnerReceiptAuthorityContract;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class Hrm012ReceiptFirstStepUpClientTest {

    @Test
    void sendsOnlyPeopleServiceAuthorityAndRequiresAnExactAllowedResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var client = new Hrm012ReceiptFirstStepUpClient(builder.build(), "owner-token");
        server.expect(once(), requestTo(
                        "http://localhost/internal/auth/v1/hris/hrm012/receipt-first-step-up/verify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DWP-Service-Identity", "dwp-people-server"))
                .andExpect(header("X-DWP-Hris-Origin-Authority-Token", "owner-token"))
                .andRespond(withSuccess("""
                        {"decision":"ALLOWED","authorizationRevision":"psr-1",
                         "dutyEvidenceRevision":"auth-1","featureDecisionRevision":"feature-1",
                         "stepUpChallengeId":"challenge-1",
                         "stepUpExpiresAt":"2099-01-01T00:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.verify(request()).decision()).isEqualTo("ALLOWED");
        server.verify();
    }

    @Test
    void rejectsAnAllowedResponseWithAnyUnknownField() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var client = new Hrm012ReceiptFirstStepUpClient(builder.build(), "owner-token");
        server.expect(once(), requestTo(
                        "http://localhost/internal/auth/v1/hris/hrm012/receipt-first-step-up/verify"))
                .andRespond(withSuccess("""
                        {"decision":"ALLOWED","authorizationRevision":"psr-1",
                         "dutyEvidenceRevision":"auth-1","featureDecisionRevision":"feature-1",
                         "stepUpChallengeId":"challenge-1",
                         "stepUpExpiresAt":"2099-01-01T00:00:00Z","extra":"forbidden"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.verify(request())).isInstanceOf(BaseException.class);
        server.verify();
    }

    private Hrm012ReceiptFirstStepUpDtos.VerifyRequest request() {
        return new Hrm012ReceiptFirstStepUpDtos.VerifyRequest(
                7L, "11", Hrm012OwnerReceiptAuthorityContract.ROUTE, "POST",
                Hrm012OwnerReceiptAuthorityContract.PUBLIC_PATH,
                Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH,
                "connector-1", "\"3\"", "HRIS_INTEGRATION", "challenge-token",
                "idempotency-1", "context-1", "scope-1", "a".repeat(64),
                "psr-1", "receipt-1", "b".repeat(64), "correlation-1");
    }
}
