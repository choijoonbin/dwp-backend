package com.dwp.services.platform.hrisconfiguration.operations;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class HrisOperationalExceptionMigrationContractTest {

    private static final Path V331 = Path.of(
            "src/main/resources/db/migration/"
                    + "V331__create_hris_operational_exception_projection.sql");
    private static final Path V330 = Path.of(
            "src/main/resources/db/migration/"
                    + "V330__accept_controlled_export_navigation_topology_successor.sql");

    @Test
    void v331AddsExactlyOneProjectionAndReusesSharedDeliveryLedger()
            throws Exception {
        String sql = Files.readString(V331);
        Matcher tables = Pattern.compile(
                "(?im)^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                        + "([a-z0-9_]+)").matcher(sql);
        assertThat(tables.find()).isTrue();
        assertThat(tables.group(1))
                .isEqualTo("hris_operational_exception_projection");
        assertThat(tables.find()).isFalse();

        assertThat(sql).contains(
                "PRIMARY KEY\n        (tenant_id, event_source, exception_public_id)",
                "UNIQUE\n        (tenant_id, event_source, exception_public_id, source_revision)",
                "ENABLE ROW LEVEL SECURITY",
                "FORCE ROW LEVEL SECURITY",
                "current_setting('dwp.tenant_id', TRUE)",
                "BEFORE INSERT OR UPDATE OR DELETE",
                "IF TG_OP = 'DELETE' THEN",
                "evidence_ref IS NULL",
                "NEW.source_revision <> OLD.source_revision + 1",
                "NEW.occurrence_count <> OLD.occurrence_count + 1",
                "NEW.version <> OLD.version + 1");
        assertThat(sql).doesNotContain(
                "CREATE TABLE sys_domain_event_inbox",
                "CREATE TABLE sys_domain_event_offsets",
                "CREATE TABLE sys_domain_event_outbox",
                "command_receipt", "recovery_command",
                "INSERT INTO hris_operational_exception_projection");
    }

    @Test
    void v331CarriesOnlyTheExactTwentySealedColumns() throws Exception {
        String sql = Files.readString(V331);
        String tableBody = sql.substring(
                sql.indexOf("CREATE TABLE hris_operational_exception_projection (")
                        + "CREATE TABLE hris_operational_exception_projection (".length(),
                sql.indexOf("\n);", sql.indexOf(
                        "CREATE TABLE hris_operational_exception_projection (")));
        Set<String> expected = Set.of(
                "tenant_id", "exception_public_id", "event_source",
                "owner_service", "owner_module", "source_ref",
                "source_revision", "exception_type", "severity",
                "internal_state", "code", "summary_key", "owner_route_key",
                "evidence_refs", "correlation_id", "event_digest",
                "first_observed_at", "last_observed_at", "occurrence_count",
                "version");
        for (String column : expected) {
            assertThat(tableBody).containsPattern(
                    "(?m)^\\s{4}" + column + "\\s+");
        }
        long columnLines = tableBody.lines()
                .filter(line -> line.matches("^    [a-z][a-z0-9_]*\\s+.*"))
                .count();
        assertThat(columnLines).isEqualTo(expected.size());
    }

    @Test
    void immutableV330PredecessorBytesRemainExact() throws Exception {
        byte[] bytes = Files.readAllBytes(V330);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(digest).isEqualTo(
                "0ed682d631189184d26d4031c4770781c8acef8e7b5a2f571468595be5b76a26");
    }
}
