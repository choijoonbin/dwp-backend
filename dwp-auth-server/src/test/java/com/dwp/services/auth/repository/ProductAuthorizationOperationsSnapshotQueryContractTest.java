package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationOperationsSnapshotQueryContractTest {

    @Test
    void activePreflightReadsTheBundleAndCasRevisionFromOneStatement() {
        assertThat(ProductAuthorizationContractRepository.ACTIVE_BUNDLE_SNAPSHOT_SQL)
                .contains(
                        "JOIN auth_product_authorization_bundle bundle",
                        "active_pointer.revision AS active_revision",
                        "active_pointer.bundle_id",
                        "active_pointer.bundle_key")
                .doesNotContain("FOR UPDATE");
    }

    @Test
    void versionPreflightReadsStatusAndMatchingPointerRevisionFromOneStatement() {
        assertThat(ProductAuthorizationContractRepository.VERSION_BUNDLE_SNAPSHOT_SQL)
                .contains(
                        "LEFT JOIN auth_product_authorization_active active_pointer",
                        "active_pointer.bundle_id = bundle.bundle_id",
                        "active_pointer.bundle_key = bundle.bundle_key",
                        "COALESCE(active_pointer.revision, 0) AS active_revision")
                .doesNotContain("FOR UPDATE");
    }
}
