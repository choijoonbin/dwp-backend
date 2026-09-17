package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class WidgetRegistryOwnerScopeGuardTest {
    private final WidgetRegistryOwnerScopeGuard guard = new WidgetRegistryOwnerScopeGuard(
            mock(WidgetDefinitionRepository.class),
            mock(WidgetDefinitionVersionRepository.class));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void permitsOnlyOwnersCarriedByTheTrustedProviderBff() {
        providerRequest("core.work");

        guard.requireOwner("core.work");
        assertThatThrownBy(() -> guard.requireOwner("core.calendar"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void providerRequestsFailClosedWithoutOneValidOwnerScopeHeader() {
        providerRequest(null);
        assertThatThrownBy(() -> guard.requireOwner("core.work"))
                .isInstanceOf(BaseException.class);

        providerRequest("../core.work");
        assertThatThrownBy(() -> guard.requireOwner("core.work"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void tenantPlaneDoesNotAcquireOrRequireProviderOwnerScope() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        guard.requireOwner("core.calendar");
    }

    private void providerRequest(String owners) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-DWP-Identity-Plane", "PROVIDER");
        request.addHeader("X-DWP-Control-Plane", "WIDGET_REGISTRY_PROVIDER");
        if (owners != null) request.addHeader(WidgetRegistryOwnerScopeGuard.OWNER_SCOPE_HEADER, owners);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
