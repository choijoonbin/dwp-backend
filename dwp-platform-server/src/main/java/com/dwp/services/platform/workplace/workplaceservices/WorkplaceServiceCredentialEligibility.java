package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.ProviderLifecycleState;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsRepository.*;

final class WorkplaceServiceCredentialEligibility {
    private static final Duration PROVIDER_FRESHNESS = Duration.ofMinutes(15);

    private WorkplaceServiceCredentialEligibility() { }

    static void requireEligible(
            TaskContextRow context, long expectedOrderVersion, OffsetDateTime now) {
        if (context.orderVersion() != expectedOrderVersion) {
            throw versionConflict("The service order changed. Refresh before issuing access.");
        }
        if (!context.providerCapabilities().contains("EPHEMERAL_CREDENTIAL")) {
            throw conflict("This provider does not support ephemeral credentials.");
        }
        if (context.providerLifecycleState() != ProviderLifecycleState.ACTIVE
                || context.credentialBindingReference() == null
                || !context.providerConfigured()
                || !Objects.equals(context.observedConfigurationVersion(),
                    context.providerConfigurationVersion())
                || !"HEALTHY".equals(context.reportedState())
                || context.evidenceReference() == null || context.receivedAt() == null
                || context.receivedAt().isBefore(now.minus(PROVIDER_FRESHNESS))) {
            throw conflict("Provider readiness is not fresh enough to issue access.");
        }
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }
}
