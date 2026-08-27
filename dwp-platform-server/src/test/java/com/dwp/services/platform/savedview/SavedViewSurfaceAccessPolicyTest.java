package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavedViewSurfaceAccessPolicyTest {

    private final SavedViewSurfaceAccessPolicy policy = new SavedViewSurfaceAccessPolicy();

    @Test
    void mapsEverySupportedSurfaceToOneExactViewPermission() {
        assertThatCode(() -> policy.requireRead(
                "workspace.work", "APP.WORK:VIEW"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.requireWrite(
                "workspace.activity", "APP.ACTIVITY:VIEW"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.requireUse(
                "workspace.apps", "APP.APPS:VIEW"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.requireRead(
                "people.workforce-directory", "APP.PEOPLE_DIRECTORY:VIEW"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.requireWrite(
                "workforce.operations-overview", "APP.WORKFORCE_MANAGEMENT:VIEW"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.requireUse(
                "calendar.schedule", "APP.CALENDAR:VIEW"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingSimilarAndOversizedActorPermissionEvidence() {
        assertActorForbidden(null);
        assertActorForbidden("APP.WORK:UPDATE");
        assertActorForbidden("PREFIX.APP.WORK:VIEW");
        assertActorForbidden("APP.WORK:VIEW" + " ".repeat(16_384));
    }

    @Test
    void rejectsUnknownSurfacesWithoutFallingBackToAResourcePrefix() {
        assertThatThrownBy(() -> policy.requireRead(
                "workspace.unknown", "APP.WORK:VIEW,APP.APPS:VIEW"))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage())
                            .contains(SavedViewSurfaceAccessPolicy.UNKNOWN_SURFACE_MESSAGE);
                });
        assertThatThrownBy(() -> policy.requireRead(
                "provider.customer-estate", "APP.PROVIDER:VIEW"))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage())
                            .contains(SavedViewSurfaceAccessPolicy.UNKNOWN_SURFACE_MESSAGE);
                });
    }

    @Test
    void targetEligibilityUsesTheSameExactSurfaceMapping() {
        SavedViewSubjectDirectory.Subject target = new SavedViewSubjectDirectory.Subject(
                3L, 17L, UUID.randomUUID(), UUID.randomUUID(), "Target", null, null,
                "ACTIVE", "TENANT", List.of("TENANT_ADMIN"), List.of(),
                List.of("APP.APPS:VIEW"));

        assertThatCode(() -> policy.requireEligibleTarget("workspace.apps", target))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireEligibleTarget("workspace.work", target))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE);
                    assertThat(exception.getMessage())
                            .contains(SavedViewSurfaceAccessPolicy.TARGET_NOT_ENTITLED_MESSAGE);
                });
    }

    private void assertActorForbidden(String permissions) {
        assertThatThrownBy(() -> policy.requireRead("workspace.work", permissions))
                .isInstanceOfSatisfying(BaseException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(exception.getMessage())
                            .contains(SavedViewSurfaceAccessPolicy.ACTOR_NOT_ENTITLED_MESSAGE);
                });
    }
}
