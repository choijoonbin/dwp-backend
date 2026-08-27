package com.dwp.services.platform.savedview;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavedViewCustodyAccessGuardTest {

    private final SavedViewCustodyAccessGuard guard = new SavedViewCustodyAccessGuard();

    @Test
    void separatesReadAndMutationCustodyPermissions() {
        assertThatCode(() -> guard.view("ADMIN.SAVED_VIEW_CUSTODY:VIEW"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.view("ADMIN.SAVED_VIEW_CUSTODY:MANAGE"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.manage("ADMIN.SAVED_VIEW_CUSTODY:VIEW"))
                .isInstanceOf(BaseException.class);
        assertThatCode(() -> guard.manage("ADMIN.SAVED_VIEW_CUSTODY:MANAGE"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.view(null))
                .isInstanceOf(BaseException.class);
    }
}
