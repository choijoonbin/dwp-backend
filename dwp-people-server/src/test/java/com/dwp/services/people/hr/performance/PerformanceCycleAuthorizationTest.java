package com.dwp.services.people.hr.performance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerformanceCycleAuthorizationTest {

    @Test
    void doesNotFallBackToAdministrativeRoles() {
        PeopleRequestContext.Actor roleOnly = new PeopleRequestContext.Actor(
                10L, 20L, UUID.randomUUID(), Set.of("ADMIN", "HR_ADMIN"), Set.of());

        assertThatThrownBy(() -> PerformanceCycleAuthorization.requireRead(roleOnly))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(PerformanceCycleAuthorization.allowedActions(roleOnly, "VALIDATED", 99L))
                .isEmpty();
    }

    @Test
    void mapsEachActionOnlyFromItsExplicitPermission() {
        PeopleRequestContext.Actor actor = new PeopleRequestContext.Actor(
                10L, 20L, UUID.randomUUID(), Set.of(), Set.of(
                "DATA.HR_TALENT:VIEW",
                "DATA.HR_TALENT:CREATE",
                "DATA.HR_TALENT:UPDATE"));

        assertThat(PerformanceCycleAuthorization.allowedActions(actor, "VALIDATED", 99L))
                .containsExactly("VIEW", "CREATE_DRAFT", "UPDATE_DRAFT",
                        "PREVIEW_PARTICIPANTS")
                .doesNotContain("PUBLISH");
        assertThatThrownBy(() -> PerformanceCycleAuthorization.requirePublish(actor))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void manageGrantsAllNonRetiredActions() {
        PeopleRequestContext.Actor actor = new PeopleRequestContext.Actor(
                10L, 20L, UUID.randomUUID(), Set.of(), Set.of("DATA.HR_TALENT:MANAGE"));

        assertThat(PerformanceCycleAuthorization.allowedActions(actor, "VALIDATED", 99L))
                .containsExactly("VIEW", "CREATE_DRAFT", "UPDATE_DRAFT",
                        "PREVIEW_PARTICIPANTS", "PUBLISH");
        assertThat(PerformanceCycleAuthorization.allowedActions(actor, "RETIRED", 99L))
                .containsExactly("VIEW", "CREATE_DRAFT");
    }

    @Test
    void constrainsPreviewByStateAndPublishBySeparationOfDuties() {
        PeopleRequestContext.Actor actor = new PeopleRequestContext.Actor(
                10L, 20L, UUID.randomUUID(), Set.of(), Set.of("DATA.HR_TALENT:MANAGE"));

        assertThat(PerformanceCycleAuthorization.allowedActions(actor, "PUBLISHED", 99L))
                .containsExactly("VIEW", "CREATE_DRAFT", "UPDATE_DRAFT")
                .doesNotContain("PREVIEW_PARTICIPANTS", "PUBLISH");
        assertThat(PerformanceCycleAuthorization.allowedActions(actor, "VALIDATED", 10L))
                .containsExactly("VIEW", "CREATE_DRAFT", "UPDATE_DRAFT",
                        "PREVIEW_PARTICIPANTS")
                .doesNotContain("PUBLISH");
        assertThat(PerformanceCycleAuthorization.allowedActions(actor, null, null))
                .containsExactly("VIEW", "CREATE_DRAFT");
    }
}
