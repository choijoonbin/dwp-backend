package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

final class HomeViewConflictException extends BaseException {
    private static final long serialVersionUID = 1L;

    private final transient HomeViewDtos.HomeViewConflictResponse conflict;

    HomeViewConflictException(HomeViewDtos.HomeViewConflictResponse conflict) {
        super(ErrorCode.HOME_VIEW_VERSION_CONFLICT);
        this.conflict = conflict;
    }

    HomeViewDtos.HomeViewConflictResponse conflict() {
        return conflict;
    }
}
