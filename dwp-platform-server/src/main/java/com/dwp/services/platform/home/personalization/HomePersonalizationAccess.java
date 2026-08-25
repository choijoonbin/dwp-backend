package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class HomePersonalizationAccess {
    private static final String TEMPLATE_VIEW = "ADMIN.HOME_TEMPLATE:VIEW";
    private static final String TEMPLATE_MANAGE = "ADMIN.HOME_TEMPLATE:MANAGE";

    @Value("${dwp.platform.home.personalization-v2-enabled:false}")
    private boolean personalizationEnabled;

    @Value("${dwp.platform.home.composer-enabled:false}")
    private boolean composerEnabled;

    public void requirePersonalization() {
        if (!personalizationEnabled) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Advanced home personalization is not enabled.");
        }
    }

    public void requireComposer() {
        requirePersonalization();
        if (!composerEnabled) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The home composer is not enabled.");
        }
    }

    public void requireTemplateManage(String permissions) {
        requirePersonalization();
        if (!authorities(permissions).contains(TEMPLATE_MANAGE)) forbidden();
    }

    public boolean canViewDraftTemplates(String permissions) {
        Set<String> values = authorities(permissions);
        return values.contains(TEMPLATE_VIEW) || values.contains(TEMPLATE_MANAGE);
    }

    public Set<String> roles(String roles) {
        return authorities(roles);
    }

    private Set<String> authorities(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private void forbidden() {
        throw new BaseException(ErrorCode.FORBIDDEN,
                "Home template management permission is required.");
    }
}
