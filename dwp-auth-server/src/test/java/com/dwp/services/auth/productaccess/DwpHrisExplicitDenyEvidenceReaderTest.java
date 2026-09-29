package com.dwp.services.auth.productaccess;

import com.dwp.services.auth.entity.Permission;
import com.dwp.services.auth.entity.Resource;
import com.dwp.services.auth.entity.RolePermission;
import com.dwp.services.auth.repository.PermissionRepository;
import com.dwp.services.auth.repository.PrincipalResourceGrantRepository;
import com.dwp.services.auth.repository.ResourceRepository;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.repository.RolePermissionRepository;
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
class DwpHrisExplicitDenyEvidenceReaderTest {

    @Mock private RoleMemberRepository roleMembers;
    @Mock private RolePermissionRepository rolePermissions;
    @Mock private ResourceRepository resources;
    @Mock private PermissionRepository permissions;
    @Mock private PrincipalResourceGrantRepository principalGrants;
    private DwpHrisExplicitDenyEvidenceReader reader;

    @BeforeEach
    void setUp() {
        reader = new DwpHrisExplicitDenyEvidenceReader(
                roleMembers, rolePermissions, resources, permissions, principalGrants);
    }

    @Test
    void resolvesTenantBoundRoleAndPrincipalDeniesAndDropsCrossTenantResources() {
        when(roleMembers.findRoleIds(7L, 11L)).thenReturn(List.of(20L));
        when(rolePermissions.findByTenantIdAndRoleIdInAndEffect(
                7L, List.of(20L), "DENY")).thenReturn(List.of(
                        rolePermission(1L, 10L), rolePermission(2L, 10L)));
        when(resources.findAllById(List.of(1L, 2L))).thenReturn(List.of(
                resource(1L, 7L, "DATA.WORKFORCE"),
                resource(2L, 8L, "DATA.HR_TALENT")));
        when(permissions.findAllById(List.of(10L, 10L))).thenReturn(List.of(
                Permission.builder().permissionId(10L).code("VIEW").name("View").build()));
        when(principalGrants.findEffective(7L, 11L)).thenReturn(List.of(
                new PrincipalResourceGrantRepository.EffectiveGrant(
                        "grant-1", "DATA", "DATA.HR_PAY", "Pay", "VIEW", "View", "DENY")));

        assertThat(reader.load(7L, 11L))
                .containsExactlyInAnyOrder("DATA.WORKFORCE:VIEW", "DATA.HR_PAY:VIEW")
                .doesNotContain("DATA.HR_TALENT:VIEW");
        verify(principalGrants).findEffective(7L, 11L);
    }

    private RolePermission rolePermission(Long resourceId, Long permissionId) {
        return RolePermission.builder()
                .tenantId(7L).roleId(20L).resourceId(resourceId)
                .permissionId(permissionId).effect("DENY").build();
    }

    private Resource resource(Long resourceId, Long tenantId, String key) {
        return Resource.builder()
                .resourceId(resourceId).tenantId(tenantId).type("DATA")
                .key(key).name(key).enabled(true).build();
    }
}
