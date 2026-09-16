package com.dwp.platform.contract.home;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeWidgetProviderSecurityTest {

    private static final String TOKEN = "provider-purpose-token-1234567890";

    @Test
    void bindsCurrentRecipientAndAuthority() {
        var context = HomeWidgetProviderSecurity.authorize(TOKEN, evidence(null, null, false));
        assertThat(context.tenantId()).isEqualTo(7);
        assertThat(context.userId()).isEqualTo(11);
        assertThat(context.authorityDecisionRevision()).isEqualTo("decision-42");
        assertThat(context.has("APP.MEETINGS", "VIEW")).isTrue();
    }

    @Test
    void rejectsWrongPurposeTokenAndServiceIdentity() {
        assertThatThrownBy(() -> HomeWidgetProviderSecurity.authorize(
                TOKEN, new HomeWidgetProviderSecurity.Evidence(
                        "dwp-gateway", "gateway-token-123456789012345",
                        "7", "11", null, "APP.MEETINGS:VIEW", "", "",
                        "decision-42", future(60), future(2), "ko-KR",
                        null, null, null, null, null, false)))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo("HOME_PROVIDER_IDENTITY_INVALID");
    }

    @Test
    void rejectsAmbientAuthorityAndDuplicateSecurityHeaders() {
        assertThatThrownBy(() -> HomeWidgetProviderSecurity.authorize(
                TOKEN, evidence("Bearer browser-token", null, false)))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo("HOME_PROVIDER_AMBIENT_AUTHORITY_REJECTED");
        assertThatThrownBy(() -> HomeWidgetProviderSecurity.authorize(
                TOKEN, evidence(null, "JSESSIONID=ambient-browser-session", false)))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo("HOME_PROVIDER_AMBIENT_AUTHORITY_REJECTED");
        assertThatThrownBy(() -> HomeWidgetProviderSecurity.authorize(
                TOKEN, evidence(null, null, true)))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo("HOME_PROVIDER_HEADER_DUPLICATED");
    }

    @Test
    void rejectsExpiredAuthorityAndUnboundedDeadline() {
        var expired = new HomeWidgetProviderSecurity.Evidence(
                HomeWidgetProviderSecurity.TRUSTED_SERVICE_IDENTITY, TOKEN,
                "7", "11", null, "APP.MEETINGS:VIEW", "", "",
                "decision-42", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1).toString(),
                future(2), "ko-KR", null, null, null, null, null, false);
        assertThatThrownBy(() -> HomeWidgetProviderSecurity.authorize(TOKEN, expired))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo("HOME_PROVIDER_AUTHORITY_EXPIRED");

        var unbounded = new HomeWidgetProviderSecurity.Evidence(
                HomeWidgetProviderSecurity.TRUSTED_SERVICE_IDENTITY, TOKEN,
                "7", "11", null, "APP.MEETINGS:VIEW", "", "",
                "decision-42", future(60), future(31), "ko-KR",
                null, null, null, null, null, false);
        assertThatThrownBy(() -> HomeWidgetProviderSecurity.authorize(TOKEN, unbounded))
                .isInstanceOf(HomeWidgetProviderRequestException.class)
                .extracting(failure -> ((HomeWidgetProviderRequestException) failure).reasonCode())
                .isEqualTo("HOME_PROVIDER_DEADLINE_UNBOUNDED");
    }

    private HomeWidgetProviderSecurity.Evidence evidence(
            String authorization,
            String cookie,
            boolean duplicate) {
        return new HomeWidgetProviderSecurity.Evidence(
                HomeWidgetProviderSecurity.TRUSTED_SERVICE_IDENTITY, TOKEN,
                "7", "11", null, "APP.MEETINGS:VIEW", "MEMBER", "team-a",
                "decision-42", future(60), future(2), "ko-KR",
                authorization, cookie, null, null, null, duplicate);
    }

    private String future(long seconds) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(seconds).toString();
    }
}
