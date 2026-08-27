package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProviderSupportActivationGate {

    private final ProviderSupportSessionRepository repository;
    private final boolean deploymentEnabled;

    public ProviderSupportActivationGate(
            ProviderSupportSessionRepository repository,
            @Value("${dwp.provider.support-activation-enabled:false}")
            boolean deploymentEnabled) {
        this.repository = repository;
        this.deploymentEnabled = deploymentEnabled;
    }

    public boolean enabled() {
        return deploymentEnabled && repository.activationState().enabled();
    }

    public void requireEnabled() {
        if (!enabled()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Standard support activation is disabled by an operational safety control.");
        }
    }
}
