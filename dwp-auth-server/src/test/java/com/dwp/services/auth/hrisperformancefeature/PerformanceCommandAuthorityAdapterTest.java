package com.dwp.services.auth.hrisperformancefeature;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.service.ProductSurfaceAuthorityService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PerformanceCommandAuthorityAdapterTest {
    @Test @SuppressWarnings("unchecked")
    void independentlyResolvesTheNativeSubjectAndOnlyTheExactSharedAction() {
        var jdbc = mock(JdbcTemplate.class);
        doReturn(List.of(11L)).when(jdbc).query(anyString(), any(RowMapper.class), any(), any());
        var authority = mock(ProductSurfaceAuthorityService.class);
        var registry = mock(ProductAuthorizationContractRepository.class);
        var bundle = mock(ProductAuthorizationContractRepository.StoredBundle.class);
        when(bundle.version()).thenReturn(55L);
        when(registry.findActive("product-surfaces")).thenReturn(java.util.Optional.of(bundle));
        var result = mock(ProductSurfaceAuthorityDtos.AuthorityResult.class);
        when(result.decision()).thenReturn(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        when(result.routeGrantRef()).thenReturn("route-grant");
        when(result.authRevision()).thenReturn("auth-current");
        when(result.policyRevision()).thenReturn("policy-current");
        when(result.tenantControlRevision()).thenReturn("control-current");
        when(result.revalidateAt()).thenReturn(OffsetDateTime.parse("2026-10-19T05:00:30Z"));
        var grant = new ProductSurfaceAuthorityDtos.CapabilityGrant("hcm.configuration.policy.update",
                "CONFIG.HRIS_POLICY:UPDATE", ProductSurfaceAuthorityDtos.CapabilityAuthorityMode.PERMISSION,
                List.of(), ProductSurfaceAuthorityDtos.ResponsibilityRequirement.REQUIRED,
                null, List.of("RS_HCM_CONFIG"), false, false,
                ProductSurfaceAuthorityDtos.ActivationState.ACTIVE, null);
        when(result.effectiveGrants()).thenReturn(List.of(grant));
        when(authority.evaluate(any())).thenReturn(result);
        var request = new PerformanceFeatureContract.Request(7, "10000000-0000-0000-0000-000000000001",
                "route.hcm.configuration.configuration-version-create.action", "20000000-0000-0000-0000-000000000002",
                "PER", "a".repeat(64), PerformanceFeatureContract.authorizationRevision("auth-current"),
                "30000000-0000-0000-0000-000000000003");
        var evidence = new PerformanceCommandAuthorityAdapter(jdbc, authority, registry).evaluate(request);
        assertThat(evidence.allowed()).isTrue();
        assertThat(evidence.authorizationRevision()).isEqualTo(request.commandAuthorizationRevision());
        var evaluated = ArgumentCaptor.forClass(ProductSurfaceAuthorityDtos.EvaluateRequest.class);
        verify(authority).evaluate(evaluated.capture());
        assertThat(evaluated.getValue().actorId()).isEqualTo(11L);
        assertThat(evaluated.getValue().tenantId()).isEqualTo(7L);
        assertThat(evaluated.getValue().routeContractKey()).isEqualTo(request.exactSharedActionRouteContractKey());
        assertThat(evaluated.getValue().activeAccessMode()).isEqualTo(ProductSurfaceAuthorityDtos.AccessMode.ELEVATED);
    }
}
