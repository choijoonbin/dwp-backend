package com.dwp.services.platform.workhub.personal;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class PersonalWorkAccess {
    public void read(PersonalWorkDtos.AccessContext context) {
        requireIdentity(context);
        require(context.permissions(), "APP.WORK:VIEW");
    }

    public void write(PersonalWorkDtos.AccessContext context) {
        read(context);
        require(context.permissions(), "APP.WORK:UPDATE");
    }

    private void requireIdentity(PersonalWorkDtos.AccessContext context) {
        if (context == null || context.tenantId() == null || context.tenantId() <= 0) {
            throw new BaseException(ErrorCode.TENANT_MISSING);
        }
        if (context.userId() == null || context.userId() <= 0) {
            throw new BaseException(ErrorCode.UNAUTHORIZED);
        }
    }

    private void require(String permissions, String required) {
        if (permissions == null || Arrays.stream(permissions.split(","))
                .map(String::trim).noneMatch(required::equals)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }
}
