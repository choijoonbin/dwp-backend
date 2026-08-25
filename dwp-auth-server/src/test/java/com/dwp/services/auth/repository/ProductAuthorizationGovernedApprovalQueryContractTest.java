package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationGovernedApprovalQueryContractTest {

    @Test
    void approvalEvidenceIsBoundToTheExactImmutableBundleAndProviderLane() {
        assertThat(ProductAuthorizationContractRepository.GOVERNED_APPROVAL_EVIDENCE_SQL)
                .contains(
                        "bundle_id = ?",
                        "bundle_key = ?",
                        "version = ?",
                        "checksum = ?",
                        "operation = 'APPROVE'",
                        "caller_service_identity = 'dwp-provider-server'",
                        "requester_ref",
                        "decision_actor_ref",
                        "change_ref");
    }
}
