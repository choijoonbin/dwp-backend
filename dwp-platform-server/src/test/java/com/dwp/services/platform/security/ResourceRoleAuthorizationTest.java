package com.dwp.services.platform.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceRoleAuthorizationTest {

    @Test
    void resolvesOnlyAcceptedResponsibilitiesAndValidResourceKeys() {
        assertThat(ResourceRoleAuthorization.resourcesFor(
                "APP_OWNER@APP.MAIL,APP_ACCESS_APPROVER@APP.HRIS,"
                        + "APP_ACCESS_APPROVER@../../escape,UNKNOWN@APP.WORK",
                "APP_ACCESS_APPROVER"))
                .containsExactly("APP.HRIS");
    }

    @Test
    void matchesResponsibilityAndResourceWithoutTrustingHeaderCase() {
        assertThat(ResourceRoleAuthorization.has(
                "app_access_approver@app.mail",
                "APP_ACCESS_APPROVER", "APP.MAIL"))
                .isTrue();
    }
}
