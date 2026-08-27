package com.dwp.services.provider;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderTenantMutationOpenApiCompatibilityTest {

    @Test
    void lifecycleContractKeepsItsPublicPathRequestAndTenantSummaryEnvelope() throws Exception {
        Method method = ProviderControlPlaneController.class.getDeclaredMethod(
                "lifecycle", UUID.class, String.class, ProviderDtos.LifecycleRequest.class);

        assertThat(method.getAnnotation(PatchMapping.class).value())
                .containsExactly("/tenants/{tenantId}/lifecycle");
        assertThat(method.getGenericReturnType().getTypeName())
                .isEqualTo("com.dwp.core.common.ApiResponse<com.dwp.services.provider.ProviderDtos$TenantSummary>");
        assertThat(Arrays.stream(ProviderDtos.LifecycleRequest.class.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().getSimpleName()))
                .containsExactly("state:String", "justification:String", "version:Long");
    }

    @Test
    void entitlementContractKeepsItsPublicPathRequestAndTenantSummaryEnvelope() throws Exception {
        Method method = ProviderControlPlaneController.class.getDeclaredMethod(
                "replaceEntitlements", UUID.class, String.class,
                ProviderDtos.ReplaceEntitlementsRequest.class);

        assertThat(method.getAnnotation(PutMapping.class).value())
                .containsExactly("/tenants/{tenantId}/entitlements");
        assertThat(method.getGenericReturnType().getTypeName())
                .isEqualTo("com.dwp.core.common.ApiResponse<com.dwp.services.provider.ProviderDtos$TenantSummary>");
        assertThat(Arrays.stream(ProviderDtos.ReplaceEntitlementsRequest.class.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().getSimpleName()))
                .containsExactly("entitlementKeys:List", "justification:String", "version:Long");
    }
}
