package com.dwp.services.platform.productivity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class ProductivityAccessGuard {

    private static final String VIEW = "ADMIN.PRODUCTIVITY_CONNECTOR:VIEW";
    private static final String MANAGE = "ADMIN.PRODUCTIVITY_CONNECTOR:MANAGE";
    private static final String USE = "APP.MAIL_CALENDAR:VIEW";

    public void view(String permissions) { require(permissions, VIEW); }

    public void manage(String permissions) { require(permissions, MANAGE); }

    public void use(String permissions) { require(permissions, USE); }

    private void require(String permissions, String required) {
        boolean permitted = permissions != null && Arrays.stream(permissions.split(","))
                .map(String::trim)
                .anyMatch(required::equalsIgnoreCase);
        if (!permitted) throw new BaseException(ErrorCode.FORBIDDEN);
    }
}
