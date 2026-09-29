package com.dwp.services.auth.productaccess;

import com.dwp.services.auth.dto.PermissionDTO;
import com.dwp.services.auth.service.AppGovernanceService;
import com.dwp.services.auth.service.AuthService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
final class DwpHrisAccessEvidenceProvider implements HrisAccessEvidenceProvider {

    private final AuthService authService;
    private final AppGovernanceService governance;
    private final HrisExplicitDenyEvidenceReader explicitDenies;

    DwpHrisAccessEvidenceProvider(
            AuthService authService,
            AppGovernanceService governance,
            HrisExplicitDenyEvidenceReader explicitDenies) {
        this.authService = authService;
        this.governance = governance;
        this.explicitDenies = explicitDenies;
    }

    @Override
    public HrisProductAccessPolicy.Evidence load(Long tenantId, Long subjectId) {
        Set<String> allowed = new LinkedHashSet<>();
        Set<String> denied = new LinkedHashSet<>();
        for (PermissionDTO permission : authService.getPermissions(subjectId, tenantId)) {
            String key = permissionKey(permission);
            if (key == null) continue;
            if ("DENY".equalsIgnoreCase(permission.getEffect())) {
                denied.add(key);
                allowed.remove(key);
            } else if ("ALLOW".equalsIgnoreCase(permission.getEffect()) && !denied.contains(key)) {
                allowed.add(key);
            }
        }
        List<HrisProductAccessPolicy.ScopedResponsibility> scopedResponsibilities = governance
                .resourceRoles(tenantId, subjectId).stream()
                .map(value -> new HrisProductAccessPolicy.ScopedResponsibility(
                        value.responsibilityCode(), value.resourceType(), value.resourceKey(),
                        value.resourceSetKey(), value.validTo()))
                .toList();
        denied.addAll(explicitDenies.load(tenantId, subjectId));
        denied.forEach(allowed::remove);
        return new HrisProductAccessPolicy.Evidence(
                Set.copyOf(allowed), Set.copyOf(denied), scopedResponsibilities);
    }

    private String permissionKey(PermissionDTO permission) {
        if (permission.getResourceKey() == null || permission.getPermissionCode() == null) return null;
        String resource = permission.getResourceKey().trim().toUpperCase(Locale.ROOT);
        if ("APP.HRIS".equals(resource)) resource = "APP.HCM";
        return resource + ":" + permission.getPermissionCode().trim().toUpperCase(Locale.ROOT);
    }
}
