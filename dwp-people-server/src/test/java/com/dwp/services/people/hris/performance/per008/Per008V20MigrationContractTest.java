package com.dwp.services.people.hris.performance.per008;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Per008V20MigrationContractTest {

    private static final String MIGRATION_ROOT = "db/performance-migration/";
    private static final String V20 = MIGRATION_ROOT + "V20__per_base_tfr_per_008.sql";
    private static final List<String> CREATED_TABLES = List.of(
            "prf_feedback_requests",
            "prf_feedback_assignments",
            "prf_feedback_responses",
            "prf_feedback_release_snapshots");
    private static final List<String> HARDENED_TABLES = List.of(
            "prf_cycles",
            "prf_cycle_versions",
            "prf_command_receipts",
            "prf_outbox_events");
    private static final Map<String, HistoricalMigration> SEALED_HISTORY = sealedHistory();

    @Test
    void createsExactlyFourOwnerTablesAndHardensExactlyFourReusedTables() throws IOException {
        String sql = migration(V20);

        assertThat(matches(sql, "(?mi)^CREATE TABLE hris_performance\\.([a-z0-9_]+)\\s*\\("))
                .containsExactlyElementsOf(CREATED_TABLES);
        assertThat(matches(sql, "(?mi)^ALTER TABLE hris_performance\\.([a-z0-9_]+)\\s+ENABLE ROW LEVEL SECURITY;"))
                .containsExactlyInAnyOrderElementsOf(union(CREATED_TABLES, HARDENED_TABLES));
        assertThat(matches(sql, "(?mi)^ALTER TABLE hris_performance\\.([a-z0-9_]+)\\s+FORCE ROW LEVEL SECURITY;"))
                .containsExactlyInAnyOrderElementsOf(union(CREATED_TABLES, HARDENED_TABLES));
        assertThat(matches(sql, "(?mi)^CREATE POLICY [a-z0-9_]+\\s+ON hris_performance\\.([a-z0-9_]+)"))
                .containsExactlyInAnyOrderElementsOf(union(CREATED_TABLES, HARDENED_TABLES));

        assertThat(sql)
                .contains("REFERENCES hris_performance.prf_cycle_versions (tenant_id, cycle_version_id)")
                .contains("REFERENCES hris_performance.prf_workforce_projection_revisions")
                .contains("REFERENCES hris_performance.prf_workforce_projection_members")
                .contains("REFERENCES hris_performance.prf_per004_configuration_version_bindings")
                .contains("REFERENCES hris_performance.prf_command_receipts")
                .doesNotContain("CREATE TABLE hris_performance.prf_feedback_command_receipts")
                .doesNotContain("CREATE TABLE hris_performance.prf_feedback_outbox")
                .doesNotContain("CREATE TABLE hris_performance.prf_feedback_audit")
                .doesNotContain("CREATE TABLE hris_performance.prf_feedback_providers")
                .doesNotContain("CREATE TABLE hris_performance.prf_feedback_scheduler");
    }

    @Test
    void sealsTenantIdentityEncryptionAppendOnlyAndLifecycleGuards() throws IOException {
        String sql = migration(V20);

        for (String table : union(CREATED_TABLES, HARDENED_TABLES)) {
            assertThat(sql)
                    .contains("REVOKE ALL ON TABLE hris_performance." + table + " FROM PUBLIC")
                    .contains("ON hris_performance." + table)
                    .contains("current_setting('dwp.tenant_id', TRUE)");
        }
        assertThat(sql)
                .contains("encryption_envelope_version = 1")
                .contains("encryption_algorithm = 'AES_256_GCM'")
                .contains("octet_length(encryption_nonce) = 12")
                .contains("octet_length(authentication_tag) = 16")
                .contains("PerformanceFeedbackResponse.v1")
                .contains("PerformanceFeedbackAggregate.v1")
                .contains("canonical_content_hmac CHAR(64) NOT NULL")
                .contains("PER008_APPEND_ONLY_EVIDENCE")
                .contains("BEFORE UPDATE OR DELETE ON hris_performance.prf_feedback_responses")
                .contains("BEFORE UPDATE OR DELETE ON hris_performance.prf_feedback_release_snapshots")
                .contains("BEFORE TRUNCATE ON hris_performance.prf_feedback_responses")
                .contains("BEFORE TRUNCATE ON hris_performance.prf_feedback_release_snapshots")
                .contains("IF NEW.aggregate_version <> 1")
                .contains("OR NEW.request_state <> 'DRAFT'")
                .contains("OR NEW.open_command_receipt_id IS NOT NULL")
                .contains("OR NEW.aggregated_at IS NOT NULL")
                .contains("NEW.submitted_at >= request.opens_at")
                .contains("NEW.submitted_at < request.closes_at")
                .contains("receipt.originating_action = 'PEP-PER-022'")
                .contains("receipt.subject_principal_public_id = assignment.provider_worker_public_id")
                .contains("receipt.population_scope_digest = NEW.execution_binding_digest")
                .contains("prf_per008_assert_create_commit")
                .contains("receipt.command_type = 'FEEDBACK_REQUEST_CREATE'")
                .contains("receipt.applied_aggregate_version = 1")
                .contains("assignment_count < request_row.effective_minimum_release_count")
                .contains("distinct_provider_count <> assignment_count")
                .contains("assignment.created_at = request_row.created_at")
                .contains("trg_prf_feedback_requests_create_commit")
                .contains("trg_prf_feedback_assignments_create_commit")
                .contains("trg_prf_command_receipts_create_commit")
                .contains("actual_submitted_count <> NEW.distinct_submitted_count")
                .contains("actual_submitted_count < request_row.effective_minimum_release_count")
                .contains("actual_source_set_digest <> NEW.source_set_digest")
                .contains("PerformanceFeedbackAggregate.v1/mean-text-v1")
                .contains("NEW.aggregate_version <> OLD.aggregate_version + 1")
                .contains("PER008_LIFECYCLE_RECEIPT_INVALID")
                .contains("receipt.command_type = 'FEEDBACK_REQUEST_LIFECYCLE'")
                .contains("receipt.purpose_code = 'PERFORMANCE_FEEDBACK_LIFECYCLE'")
                .contains("NEW.assignment_state NOT IN ('SUBMITTED', 'CANCELLED')")
                .contains("PER008_SUBMISSION_COMMIT_INCOMPLETE")
                .contains("ARRAY['feedbackAssignmentId', 'feedbackRequestId', 'submittedAt']::TEXT[]")
                .contains("PER008_ORPHAN_SNAPSHOT_COMMIT_FORBIDDEN")
                .contains("PER008_RECEIPT_IDENTITY_OR_INTENT_IMMUTABLE")
                .contains("PER008_OUTBOX_IDENTITY_OR_PAYLOAD_IMMUTABLE")
                .contains("trg_prf_feedback_assignments_submission_commit")
                .contains("trg_prf_feedback_responses_submission_commit")
                .contains("trg_prf_command_receipts_submission_commit")
                .contains("trg_prf_outbox_events_submission_commit")
                .contains("trg_prf_feedback_requests_snapshot_commit")
                .contains("trg_prf_feedback_release_snapshots_parent_commit")
                .contains("NOT role.rolsuper")
                .contains("NOT role.rolcreaterole")
                .contains("NOT role.rolcreatedb")
                .contains("NOT role.rolreplication")
                .contains("NOT role.rolbypassrls")
                .contains("PER008_RUNTIME_ROLE_RESOLUTION_FAILED")
                .contains("GRANT SELECT, INSERT, UPDATE ON TABLE")
                .contains("GRANT SELECT, INSERT ON TABLE")
                .doesNotContain("plaintext_content")
                .doesNotContain("narrative_text")
                .doesNotContain("response_payload JSONB")
                .doesNotContain("search_vector")
                .doesNotContain("GRANT ALL")
                .doesNotContain(" TO PUBLIC");

        assertThat(sql.toUpperCase(Locale.ROOT))
                .doesNotContain("CREATE ROLE")
                .doesNotContain("ALTER ROLE");
    }

    @Test
    void containsNoSeedBackfillOrHistoricalV8AndPreservesSealedMigrationBytes()
            throws IOException, NoSuchAlgorithmException {
        String sql = migration(V20);

        assertThat(matches(sql, "(?mi)^(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+hris_performance\\."))
                .isEmpty();
        assertThat(sql.toLowerCase(Locale.ROOT))
                .doesNotContain("dblink")
                .doesNotContain("http://")
                .doesNotContain("https://");
        assertThat(new ClassPathResource(
                MIGRATION_ROOT + "V8__per_base_tfr_per_008.sql").exists()).isFalse();

        for (HistoricalMigration expected : SEALED_HISTORY.values()) {
            byte[] bytes = migrationBytes(MIGRATION_ROOT + expected.fileName());
            assertThat(bytes).hasSize(expected.bytes());
            assertThat(sha256(bytes)).isEqualTo(expected.sha256());
        }
    }

    private static List<String> matches(String sql, String regex) {
        var matcher = Pattern.compile(regex).matcher(sql);
        var results = new java.util.ArrayList<String>();
        while (matcher.find()) {
            results.add(matcher.group(1));
        }
        return List.copyOf(results);
    }

    private static Set<String> union(List<String> first, List<String> second) {
        var result = new java.util.LinkedHashSet<String>();
        result.addAll(first);
        result.addAll(second);
        return Set.copyOf(result);
    }

    private static String migration(String path) throws IOException {
        return new String(migrationBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] migrationBytes(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, HistoricalMigration> sealedHistory() {
        Map<String, HistoricalMigration> history = new LinkedHashMap<>();
        history.put("V1", new HistoricalMigration(
                "V1__per_create_evaluation_cycle_foundation.sql", 13106,
                "2d2eea050731bbc580d7002adec8aacc64020ea2f8edd319955ac96d35630aba"));
        history.put("V2", new HistoricalMigration(
                "V2__per_create_population_freeze.sql", 16273,
                "f5dd1b9309bf53b1a35b5f7dcb42edaed5fb110f7c2aa3a330f57c3a41d09c3b"));
        history.put("V3", new HistoricalMigration(
                "V3__per_harden_review_operation_capture.sql", 4938,
                "1f9e2416cbbb8abd768d99dfc70cb0c2fc8766009d588c61a80df38dc02b6960"));
        history.put("V16", new HistoricalMigration(
                "V16__per_base_tfr_per_016.sql", 13387,
                "43ed98a593884082ec729c4443a333dfc364553333fabadcfc6e296850fb3884"));
        history.put("V17", new HistoricalMigration(
                "V17__per_modern_compensation_planning.sql", 35370,
                "593e54e076c5607e07a35eae1d069862257b9dce6fbb6a54dbe09198810ea15a"));
        history.put("V18", new HistoricalMigration(
                "V18__per_base_tfr_per_013.sql", 8647,
                "f543b7a77f75e624edce1cb19d13c9d3c5fece0c2bab640ddb7a5e472bad7375"));
        history.put("V19", new HistoricalMigration(
                "V19__per_base_tfr_per_004.sql", 13537,
                "b6ceb78d2446adabebd6a204b10552bd1cd3252df80f4172733e2df156550156"));
        return Map.copyOf(history);
    }

    private record HistoricalMigration(String fileName, int bytes, String sha256) {
    }
}
