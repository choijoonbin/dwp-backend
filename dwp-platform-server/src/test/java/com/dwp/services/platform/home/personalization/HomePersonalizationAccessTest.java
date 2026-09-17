package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.home.HomeModeV4ActivationGate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomePersonalizationAccessTest {

    @Test
    void phaseTwoAndComposerRemainFailClosedWhenFlagsAreOff() {
        HomeModeV4ActivationGate gate = new HomeModeV4ActivationGate(false);
        gate.markContinuityReady();
        HomePersonalizationAccess access = new HomePersonalizationAccess(gate);

        assertThatThrownBy(access::requirePersonalization)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(access::requireComposer)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void modeScopedApisRemainClosedUntilTheFleetActivationInterlockOpens() {
        HomeModeV4ActivationGate gate = new HomeModeV4ActivationGate(true);
        HomePersonalizationAccess access = new HomePersonalizationAccess(gate);
        ReflectionTestUtils.setField(access, "personalizationEnabled", true);

        assertThatThrownBy(access::requirePersonalization)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        gate.markContinuityReady();
        assertThatCode(access::requirePersonalization).doesNotThrowAnyException();
    }

    @Test
    void templateManagementRequiresTheExactManageAuthority() {
        HomePersonalizationAccess access = enabledAccess();

        assertThatCode(() -> access.requireTemplateManage(
                "ADMIN.HOME_TEMPLATE:MANAGE"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> access.requireTemplateManage(
                "ADMIN.HOME_TEMPLATE:VIEW"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> access.requireTemplateManage(
                "ADMIN.HOME_TEMPLATE_LEGACY:MANAGE"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void draftVisibilityAcceptsViewOrManageAndRemainsClosedWithoutEither() {
        HomePersonalizationAccess access = enabledAccess();

        assertThat(access.canViewDraftTemplates("ADMIN.HOME_TEMPLATE:VIEW")).isTrue();
        assertThat(access.canViewDraftTemplates("ADMIN.HOME_TEMPLATE:MANAGE")).isTrue();
        assertThat(access.canViewDraftTemplates(null)).isFalse();
        assertThat(access.canViewDraftTemplates("ADMIN.HOME_TEMPLATE_LEGACY:VIEW")).isFalse();
    }

    private HomePersonalizationAccess enabledAccess() {
        HomeModeV4ActivationGate gate = new HomeModeV4ActivationGate(true);
        gate.markContinuityReady();
        HomePersonalizationAccess access = new HomePersonalizationAccess(gate);
        ReflectionTestUtils.setField(access, "personalizationEnabled", true);
        return access;
    }
}
