package com.dwp.services.platform.auditcontrol;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class AuditAccessGuard {

    private static final String VIEW = "ADMIN.AUDIT_VIEW:VIEW";
    private static final String INVESTIGATE = "ADMIN.AUDIT_INVESTIGATE:UPDATE";
    private static final String EXPORT = "ADMIN.AUDIT_EXPORT:EXPORT";
    private static final String CONFIGURE = "ADMIN.AUDIT_CONFIGURE:MANAGE";

    public void view(String permissions) { require(permissions, VIEW); }
    public void investigate(String permissions) { require(permissions, INVESTIGATE); }
    public void export(String permissions) { require(permissions, EXPORT); }
    public void configure(String permissions) { require(permissions, CONFIGURE); }

    private void require(String permissions, String required) {
        boolean permitted = permissions != null && Arrays.stream(permissions.split(","))
                .map(String::trim)
                .anyMatch(required::equalsIgnoreCase);
        if (!permitted) throw new BaseException(ErrorCode.FORBIDDEN);
    }
}
