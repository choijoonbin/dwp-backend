package com.dwp.services.auth.productaccess;

import com.dwp.services.auth.dto.PermissionDTO;
import com.dwp.services.auth.service.AppGovernanceService;
import com.dwp.services.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DwpHrisAccessEvidenceProviderTest {

    @Mock private AuthService authService;
    @Mock private AppGovernanceService governance;
    @Mock private HrisExplicitDenyEvidenceReader explicitDenies;
    private DwpHrisAccessEvidenceProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DwpHrisAccessEvidenceProvider(authService, governance, explicitDenies);
    }

    @Test
    void explicitDenyWinsAcrossTheHcmCompatibilityAlias() {
        when(authService.getPermissions(11L, 7L)).thenReturn(List.of(
                permission("APP.HCM", "VIEW", "ALLOW"),
                permission("APP.HRIS", "VIEW", "DENY"),
                permission("DATA.WORKFORCE", "VIEW_TEAM", "ALLOW")));
        when(governance.resourceRoles(7L, 11L)).thenReturn(List.of());
        when(explicitDenies.load(7L, 11L)).thenReturn(java.util.Set.of());

        var evidence = provider.load(7L, 11L);

        assertThat(evidence.permissionKeys())
                .containsExactly("DATA.WORKFORCE:VIEW_TEAM")
                .doesNotContain("APP.HCM:VIEW");
        assertThat(evidence.deniedPermissionKeys()).containsExactly("APP.HCM:VIEW");
        verify(authService).getPermissions(11L, 7L);
        verify(governance).resourceRoles(7L, 11L);
    }

    private PermissionDTO permission(String resource, String action, String effect) {
        return PermissionDTO.builder()
                .resourceKey(resource).permissionCode(action).effect(effect).build();
    }
}
