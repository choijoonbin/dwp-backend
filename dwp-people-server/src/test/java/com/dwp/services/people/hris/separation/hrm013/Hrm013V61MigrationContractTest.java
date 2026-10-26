package com.dwp.services.people.hris.separation.hrm013;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

class Hrm013V61MigrationContractTest {
    private static final String V61 = "db/migration/V61__hrm_base_tfr_hrm_013.sql";
    private static final String V60 = "db/migration/V60__hrm_base_tfr_hrm_012.sql";

    @Test
    void createsTheExactEffectiveSixTableSchemaWithoutRuntimeOrLegacyBridgeMutation()
            throws Exception {
        String sql = resource(V61);

        assertThat(matches(sql, "(?im)^CREATE TABLE ")).isEqualTo(6);
        assertThat(sql)
                .containsOnlyOnce("CREATE TABLE ppl_separation_cases")
                .containsOnlyOnce("CREATE TABLE ppl_separation_tasks")
                .containsOnlyOnce("CREATE TABLE ppl_hrm013_callback_proof_replays")
                .containsOnlyOnce("CREATE TABLE ppl_separation_waiver_request_outbox")
                .containsOnlyOnce("CREATE TABLE ppl_separation_callback_authorities")
                .containsOnlyOnce("CREATE TABLE ppl_separation_callbacks")
                .doesNotContainIgnoringCase("CREATE TABLE ppl_hrm013_service_grants")
                .doesNotContainIgnoringCase("ALTER TABLE ppl_hris_bridge_proof_replays")
                .doesNotContainIgnoringCase("CREATE TABLE ppl_command_receipts")
                .doesNotContainIgnoringCase("CREATE TABLE sys_people_audit_events")
                .doesNotContainIgnoringCase("CREATE TABLE sys_people_outbox_events")
                .doesNotContainIgnoringCase("INSERT INTO sys_service_tenants")
                .doesNotContainIgnoringCase("GRANT ")
                .doesNotContainIgnoringCase("TRUNCATE ");
    }

    @Test
    void replayOutboxAuthorityAndCallbackShapesMatchTheSealedSuccessor()
            throws Exception {
        String sql = resource(V61);

        assertThat(sql)
                .contains("UNIQUE (tenant_id, token_digest)")
                .contains("token_kind IN ('JTI','REQUEST_NONCE')")
                .contains("binding_id = 'SVC-PEP-HRM-008'")
                .contains("binding_id = 'SVC-PEP-HRM-009'")
                .contains("binding_id = 'SVC-PEP-HRM-010'")
                .contains("expires_at <= reserved_at + INTERVAL '35 seconds'")
                .contains("exact_request_body BYTEA NOT NULL")
                .contains("octet_length(exact_request_body) BETWEEN 1 AND 32768")
                .contains("attempt_count BETWEEN 0 AND 10")
                .contains("octet_length(exact_response_body) BETWEEN 1 AND 16384")
                .contains("response_body_sha256 IS NOT NULL")
                .contains("UNIQUE NULLS NOT DISTINCT")
                .contains("(tenant_id, supersedes_authority_public_id)")
                .contains("REFERENCES ppl_separation_callback_authorities(tenant_id, public_id)")
                .contains("decision_revision = 1")
                .contains("owner_revision = 1")
                .contains("step_up_evidence_audience = 'dwp-people-server'")
                .contains("step_up_evidence_audience IS NOT NULL")
                .contains("step_up_evidence_purpose IS NOT NULL")
                .contains("step_up_consumed_at <= occurred_at")
                .contains("HRIS_SEPARATION_TASK_WAIVER_DECISION")
                .contains("waiver_decision = 'REJECTED'")
                .doesNotContain("exact_callback_body")
                .doesNotContain("raw_request_body")
                .doesNotContain("authorization_header")
                .doesNotContain("certificate_material");
    }

    @Test
    void assignmentDeltaIsForwardOnlyAndExcludesLeaveReturn() throws Exception {
        String sql = resource(V61);

        assertThat(sql)
                .contains("ADD COLUMN separation_case_id BIGINT")
                .contains("ADD COLUMN supersedes_assignment_event_id BIGINT")
                .contains("ADD COLUMN reverses_assignment_event_id BIGINT")
                .contains("'HIRE','CONCURRENT_EMPLOYMENT','REHIRE','TERMINATION','CORRECTION'")
                .contains("event_type = 'TERMINATION'")
                .contains("event_type = 'REHIRE'")
                .contains("event_type = 'CORRECTION'")
                .contains("assignment event separation lineage is immutable")
                .doesNotContain("'LEAVE'")
                .doesNotContain("'RETURN'")
                .doesNotContainIgnoringCase("DROP TABLE ppl_assignment_events");
    }

    @Test
    void everyNewTableForcesTenantRlsAndEveryGuardIsLocked() throws Exception {
        String sql = resource(V61);

        assertThat(matches(sql, "(?m)^ALTER TABLE .* ENABLE ROW LEVEL SECURITY;$"))
                .isEqualTo(6);
        assertThat(matches(sql, "(?m)^ALTER TABLE .* FORCE ROW LEVEL SECURITY;$"))
                .isEqualTo(6);
        assertThat(matches(sql, "(?m)^CREATE POLICY .*_tenant_policy"))
                .isEqualTo(6);
        assertThat(matches(sql, "(?m)^SECURITY DEFINER$")).isEqualTo(7);
        assertThat(matches(sql,
                "(?m)^SET search_path = pg_catalog, public, pg_temp$"))
                .isEqualTo(7);
        assertThat(matches(sql,
                "(?m)^REVOKE ALL ON FUNCTION public\\..*"))
                .isEqualTo(7);
    }

    @Test
    void leaseIsUniqueAndV1ThroughV60RemainByteExact() throws Exception {
        assertThat(Collections.list(getClass().getClassLoader().getResources(V61)))
                .hasSize(1);
        assertThat(sha256(resourceBytes(V60)))
                .isEqualTo("bb94ad7cea87c59aaf60ac40ec234a39d69f4302741ab17def3beb4d4eea77dd");

        Resource[] discovered = new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql");
        List<Resource> historical = Arrays.stream(discovered)
                .filter(resource -> migrationVersion(resource) <= 60)
                .sorted(Comparator.comparingInt(this::migrationVersion))
                .toList();
        assertThat(historical).hasSize(60);
        assertThat(historical.stream().map(this::migrationVersion).toList())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 60).boxed().toList());

        StringBuilder manifest = new StringBuilder();
        for (Resource resource : historical) {
            manifest.append(sha256(resource.getInputStream().readAllBytes()))
                    .append("  ").append(resource.getFilename()).append('\n');
        }
        assertThat(sha256(manifest.toString().getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("43c19bf42dfac81d0b0411de70076cc3ed21a534827f6cf126d57e584cb9a403");
    }

    private int matches(String value, String regex) {
        int count = 0;
        var matcher = Pattern.compile(regex).matcher(value);
        while (matcher.find()) count++;
        return count;
    }

    private int migrationVersion(Resource resource) {
        String name = resource.getFilename();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    private String resource(String path) throws Exception {
        return new String(resourceBytes(path), StandardCharsets.UTF_8);
    }

    private byte[] resourceBytes(String path) throws Exception {
        return new ClassPathResource(path).getInputStream().readAllBytes();
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
