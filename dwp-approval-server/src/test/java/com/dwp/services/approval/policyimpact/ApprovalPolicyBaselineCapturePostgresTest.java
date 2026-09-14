package com.dwp.services.approval.policyimpact;

import static org.assertj.core.api.Assertions.*;
import java.sql.Timestamp;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApprovalPolicyBaselineCapturePostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "apr15-baseline-capture");
    PGSimpleDataSource source;
    JdbcTemplate jdbc;

    @BeforeEach void initialize() {
        source = new PGSimpleDataSource();
        source.setURL(PG.getJdbcUrl()); source.setUser(PG.getUsername()); source.setPassword(PG.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        migration("24").clean(); migration("24").migrate();
    }

    Flyway migration(String target) {
        return Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .cleanDisabled(false).target(target).load();
    }

    String state(String table) {
        return jdbc.queryForObject("SELECT md5(coalesce(string_agg(to_jsonb(x)::text,'' ORDER BY to_jsonb(x)::text),'')) FROM "
                + table + " x", String.class);
    }

    @Test void upgradeCapturesActualMissingBaselineWithoutClaimingHistoricalReview() {
        jdbc.queryForObject("SELECT seed_approval_tenant(42)", Object.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_policy_rule_versions WHERE tenant_id=42", Integer.class)).isZero();
        String policies = state("apr_policy_rules");
        Timestamp before = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        migration("25").migrate();
        assertThat(state("apr_policy_rules")).isEqualTo(policies);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM apr_policy_rule_versions version
                JOIN apr_policy_rules policy ON policy.tenant_id=version.tenant_id AND policy.policy_id=version.policy_id
                JOIN apr_policy_version_source_records source ON source.policy_version_id=version.policy_version_id
                  AND source.tenant_id=version.tenant_id AND source.policy_id=version.policy_id
                WHERE version.tenant_id=42 AND version.enforcement_mode=policy.enforcement_mode
                  AND version.severity=policy.severity AND version.lifecycle_state=policy.lifecycle_state
                  AND version.rule_payload=policy.rule_payload AND version.submitted_by IS NULL
                  AND version.submitted_at IS NULL AND version.published_by IS NULL
                  AND source.source_kind='LEGACY_CAPTURE_TIME' AND source.captured_at=version.published_at
                  AND source.captured_at>=?
                """, Integer.class, before)).isEqualTo(4);
    }

    @Test void futureSeedIsIdempotentAndDoesNotOverwriteAnExistingPublication() {
        migration("25").migrate(); jdbc.queryForObject("SELECT seed_approval_tenant(42)", Object.class);
        String versions = state("apr_policy_rule_versions"), provenance = state("apr_policy_version_source_records");
        jdbc.update("UPDATE apr_policy_rules SET rule_payload='{\"minimumLength\":20}'::jsonb WHERE tenant_id=42 AND policy_key='REQUIRE_REJECT_REASON'");
        jdbc.queryForObject("SELECT seed_approval_tenant(42)", Object.class);
        assertThat(state("apr_policy_rule_versions")).isEqualTo(versions);
        assertThat(state("apr_policy_version_source_records")).isEqualTo(provenance);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_policy_rule_versions WHERE tenant_id=42", Integer.class)).isEqualTo(4);
    }

    @Test void existingPublicationBytesAndMissingHistoricalMetadataRemainUnchanged() {
        jdbc.queryForObject("SELECT seed_approval_tenant(42)", Object.class);
        jdbc.update("""
                INSERT INTO apr_policy_rule_versions(policy_version_id,tenant_id,policy_id,version_number,
                  enforcement_mode,severity,lifecycle_state,rule_payload,change_reason,published_by,published_at,review_comment)
                SELECT gen_random_uuid(),tenant_id,policy_id,1,enforcement_mode,severity,lifecycle_state,rule_payload,
                  'Existing publication',99,now()-interval '1 year','Existing review' FROM apr_policy_rules WHERE tenant_id=42
                """);
        String versions = state("apr_policy_rule_versions"); migration("25").migrate();
        assertThat(state("apr_policy_rule_versions")).isEqualTo(versions);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_policy_version_source_records WHERE source_kind='UNRECORDED_HISTORICAL_METADATA' AND captured_at IS NULL", Integer.class)).isEqualTo(4);
    }

    @Test void recordedSourceCannotBeMutatedOrUsedToHealMissingVersionHistory() {
        migration("25").migrate(); jdbc.queryForObject("SELECT seed_approval_tenant(42)", Object.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE apr_policy_version_source_records SET source_kind='UNRECORDED_HISTORICAL_METADATA',captured_at=NULL WHERE tenant_id=42"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM apr_policy_version_source_records WHERE tenant_id=42"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM apr_policy_rule_versions WHERE tenant_id=42");
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT seed_approval_tenant(42)", Object.class))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_policy_rule_versions WHERE tenant_id=42", Integer.class)).isZero();
    }
}
