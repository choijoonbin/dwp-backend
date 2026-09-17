package com.dwp.services.platform.mail;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminMailCompletionControllerTest {

    @Test
    void destructiveMailAdministrationRequiresFreshElevatedAccess() {
        assertThatThrownBy(() -> AdminMailCompletionController.requireElevated(null))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> AdminMailCompletionController.requireElevated("NORMAL"))
                .isInstanceOf(BaseException.class);
        assertThatCode(() -> AdminMailCompletionController.requireElevated("ELEVATED"))
                .doesNotThrowAnyException();
    }
}
