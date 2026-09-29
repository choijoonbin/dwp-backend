package com.dwp.core.database;

import java.util.Map;
import java.util.Set;

/** Neutral value views keep the runtime facade and catalog verifiers acyclic. */
final class RuntimeMigrationDatabaseTypes {
    private RuntimeMigrationDatabaseTypes() {
    }

    interface AuxiliaryAuthorityPolicyView {
        Set<String> protectedObjectOwners();

        Set<String> protectedAclGrantees();

        String administrativePrincipal();

        Set<AuxiliaryRoleAclGuard.AllowedPrivilege> allowedAclPrivileges();

        Set<AuxiliaryRoleAclGuard.AllowedPrivilege> requiredAclPrivileges();

        Map<String, String> protectedSchemaOwners();
    }

    interface LoginPostureView {
        boolean login();

        boolean inherit();

        boolean replication();
    }
}
