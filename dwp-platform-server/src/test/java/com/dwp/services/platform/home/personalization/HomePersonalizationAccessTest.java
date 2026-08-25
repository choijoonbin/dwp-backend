package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomePersonalizationAccessTest {

    @Test
    void phaseTwoAndComposerRemainFailClosedWhenFlagsAreOff() {
        HomePersonalizationAccess access = new HomePersonalizationAccess();

        assertThatThrownBy(access::requirePersonalization)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(access::requireComposer)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
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
        HomePersonalizationAccess access = new HomePersonalizationAccess();
        ReflectionTestUtils.setField(access, "personalizationEnabled", true);
        return access;
    }
}
