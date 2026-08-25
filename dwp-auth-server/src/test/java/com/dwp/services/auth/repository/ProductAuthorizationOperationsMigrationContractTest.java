package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductAuthorizationOperationsMigrationContractTest {

    @Test
    void createsAnImmutableExactBundleMakerCheckerAuditLedger() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V93__audit_product_authorization_operations.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "CREATE TABLE auth_product_authorization_governance_event",
                "FOREIGN KEY (bundle_id, bundle_key, version, checksum)",
                "operation IN ('APPROVE', 'ACTIVATE', 'ROLLBACK')",
                "resulting_revision = expected_revision + 1",
                "lower(requester_ref) <> lower(decision_actor_ref)",
                "caller_service_identity = 'dwp-provider-server'",
                "caller_service_identity = 'dwp-platform-server'",
                "trg_product_authorization_governance_event_immutable",
                "dwp_reject_authorization_descriptor_mutation");
        assertThat(migration).doesNotContain("ON DELETE CASCADE", "DELETE FROM");
    }

    @Test
    void permitsOnlyOneGovernedApprovalForEachImmutableBundle() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V94__require_single_governed_bundle_approval.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "CREATE UNIQUE INDEX ux_product_authorization_single_governed_approval",
                "auth_product_authorization_governance_event(bundle_id)",
                "WHERE operation = 'APPROVE'");
        assertThat(migration).doesNotContain("DELETE", "UPDATE");
    }

    @Test
    void releaseAuditRequiresBothSidesOfTheCasRevision() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V95__require_governance_resulting_revision.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "DROP CONSTRAINT ck_product_authorization_governance_revision",
                "ADD CONSTRAINT ck_product_authorization_governance_revision",
                "expected_revision IS NOT NULL",
                "resulting_revision IS NOT NULL",
                "resulting_revision = expected_revision + 1");
        assertThat(migration).doesNotContain("DELETE FROM", "UPDATE auth_product_authorization");
    }

    @Test
    void releaseAuditIsUniquePerCasRevisionAndAllowsGovernedReactivation() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V96__key_governance_release_events_by_cas_revision.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "DROP CONSTRAINT uk_product_authorization_governance_change",
                "CREATE UNIQUE INDEX ux_product_authorization_governance_release_revision",
                "(bundle_key, resulting_revision)",
                "WHERE operation IN ('ACTIVATE', 'ROLLBACK')");
        assertThat(migration).doesNotContain("DELETE FROM", "UPDATE auth_product_authorization");
    }

    @Test
    void releaseAuditIsForeignKeyedToTheExactActivationLineage() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V97__bind_governance_to_activation_lineage.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "UNIQUE (bundle_key, resulting_revision, to_bundle_id, operation)",
                "FOREIGN KEY (bundle_key, resulting_revision, bundle_id, operation)",
                "REFERENCES auth_product_authorization_activation_event",
                "bundle_key, resulting_revision, to_bundle_id, operation");
        assertThat(migration).doesNotContain("ON DELETE CASCADE", "DELETE FROM");
    }

    @Test
    void databaseGuardReusesExactProviderApprovalAndThreePartyEvidence() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V98__guard_governance_approval_evidence.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "operation = 'APPROVE'",
                "caller_service_identity = 'dwp-provider-server'",
                "NEW.requester_ref <> approval_requester",
                "lower(NEW.decision_actor_ref)",
                "lower(approval_requester), lower(approval_actor)",
                "NEW.operation = 'ACTIVATE' AND NEW.change_ref <> approval_change_ref",
                "trg_product_authorization_governance_evidence_guard");
        assertThat(migration).doesNotContain("DELETE FROM", "UPDATE auth_product_authorization");
    }
}
