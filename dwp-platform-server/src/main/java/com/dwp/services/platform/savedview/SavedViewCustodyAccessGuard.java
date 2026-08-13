package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class SavedViewCustodyAccessGuard {

    private static final String VIEW = "ADMIN.SAVED_VIEW_CUSTODY:VIEW";
    private static final String MANAGE = "ADMIN.SAVED_VIEW_CUSTODY:MANAGE";

    public void view(String permissions) {
        require(permissions, VIEW, MANAGE);
    }

    public void manage(String permissions) {
        require(permissions, MANAGE);
    }

    private void require(String permissions, String... accepted) {
        boolean permitted = permissions != null && Arrays.stream(permissions.split(","))
                .map(String::trim)
                .anyMatch(value -> Arrays.stream(accepted).anyMatch(value::equalsIgnoreCase));
        if (!permitted) throw new BaseException(ErrorCode.FORBIDDEN);
    }
}
