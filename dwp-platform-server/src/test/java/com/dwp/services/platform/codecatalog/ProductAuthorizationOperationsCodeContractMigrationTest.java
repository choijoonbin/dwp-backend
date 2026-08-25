package com.dwp.services.platform.codecatalog;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationOperationsCodeContractMigrationTest {

    @Test
    void registersBothClosedAuthGovernanceCheckContracts() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V182__register_product_authorization_operations_contracts.sql"));

        assertThat(migration).contains(
                "AUTH.PRODUCT_AUTHORIZATION.GOVERNANCE_OPERATION",
                "auth_product_authorization_governance_event.operation",
                "'APPROVE'",
                "'ACTIVATE'",
                "'ROLLBACK'",
                "AUTH.PRODUCT_AUTHORIZATION.CALLER_SERVICE_IDENTITY",
                "auth_product_authorization_governance_event.caller_service_identity",
                "'dwp-provider-server'",
                "'dwp-platform-server'",
                "'dwp-auth-server', 'DATABASE_COLUMN'",
                "'CHECK', 'ACTIVE'");
    }
}
