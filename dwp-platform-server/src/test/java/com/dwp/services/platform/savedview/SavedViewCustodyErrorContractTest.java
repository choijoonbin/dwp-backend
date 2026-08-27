package com.dwp.services.platform.savedview;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class SavedViewCustodyErrorContractTest {

    @Test
    void exposesAStableMachineReadableDiscriminatorForRefreshRecovery() {
        ApiResponse<Object> envelope = ApiResponse.error(
                ErrorCode.SAVED_VIEW_CUSTODY_STALE);

        assertThat(ErrorCode.SAVED_VIEW_CUSTODY_STALE.getHttpStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(envelope.getErrorCode()).isEqualTo("SAVED_VIEW_CUSTODY_STALE");
        assertThat(envelope.getMessage())
                .isEqualTo("Saved-view custody changed. Refresh the plan and retry.");
    }

    @Test
    void exposesAStableMachineReadableDiscriminatorForTargetRecovery() {
        ApiResponse<Object> envelope = ApiResponse.error(
                ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE);

        assertThat(ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE.getHttpStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(envelope.getErrorCode()).isEqualTo("SAVED_VIEW_TARGET_INELIGIBLE");
        assertThat(envelope.getMessage())
                .isEqualTo("The selected target is not eligible for the affected saved views.");
    }

    @Test
    void distinguishesPersonalAndSharedNameConflictRecovery() {
        ApiResponse<Object> personal = ApiResponse.error(
                ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT);
        ApiResponse<Object> shared = ApiResponse.error(
                ErrorCode.SAVED_VIEW_SHARED_NAME_CONFLICT);

        assertThat(ErrorCode.SAVED_VIEW_PERSONAL_NAME_CONFLICT.getHttpStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(personal.getErrorCode())
                .isEqualTo("SAVED_VIEW_PERSONAL_NAME_CONFLICT");
        assertThat(personal.getMessage()).contains("selected target");
        assertThat(ErrorCode.SAVED_VIEW_SHARED_NAME_CONFLICT.getHttpStatus())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(shared.getErrorCode())
                .isEqualTo("SAVED_VIEW_SHARED_NAME_CONFLICT");
        assertThat(shared.getMessage()).contains("Rename or archive");
    }
}
