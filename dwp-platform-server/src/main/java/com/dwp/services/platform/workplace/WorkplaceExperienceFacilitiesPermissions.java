package com.dwp.services.platform.workplace;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import java.util.Arrays;
import java.util.Locale;

final class WorkplaceExperienceFacilitiesPermissions {
    private WorkplaceExperienceFacilitiesPermissions() { }
    static void requirePermission(String values, String expected) {
        if (values == null || Arrays.stream(values.split(","))
                .map(String::trim).map(s -> s.toUpperCase(Locale.ROOT)).noneMatch(expected::equals)) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The required owner permission is missing.");
        }
    }
}
