package com.dwp.services.people.provisioning;

import com.dwp.services.people.integration.HrisModels;
import com.dwp.services.people.integration.WorkdayReferenceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PeopleTenantProvisioningCatalogParityTest {

    private static final List<String> ASSIGNMENT_REASON_MIGRATIONS = List.of(
            "db/migration/V26__govern_workforce_reference_codes.sql",
            "db/migration/V32__seed_standard_assignment_change_reasons.sql",
            "db/migration/V33__complete_hris_assignment_reason_contract.sql");
    private static final Pattern REASON = Pattern.compile(
            "\\('([^']+)',\\s*'([^']+)',\\s*'([^']+)',\\s*'(\\{[^']+\\})'"
                    + "(?:\\s*::jsonb)?,\\s*([0-9]+)\\)");

    @Test
    void runtimeTenantProvisioningMatchesTheCompleteMigrationCatalog() {
        List<PeopleTenantProvisioningService.AssignmentChangeReasonSeed> migrated =
                new ArrayList<>();
        ASSIGNMENT_REASON_MIGRATIONS.forEach(resource ->
                migrated.addAll(reasonsBlock(resource)));

        assertThat(PeopleTenantProvisioningService.STANDARD_ASSIGNMENT_CHANGE_REASONS)
                .containsExactlyElementsOf(migrated);
    }

    @Test
    void bundledSyntheticImportUsesOnlyProvisionedAssignmentReasons() {
        HrisModels.WorkforceBatch fixture = new WorkdayReferenceMapper(
                new ObjectMapper().findAndRegisterModules()).mapSyntheticFixture();
        Set<String> fixtureReasons = new LinkedHashSet<>();
        fixture.workers().stream()
                .flatMap(worker -> worker.assignments().stream())
                .map(HrisModels.Assignment::changeReasonCode)
                .forEach(fixtureReasons::add);
        Set<String> provisioned = PeopleTenantProvisioningService
                .STANDARD_ASSIGNMENT_CHANGE_REASONS.stream()
                .map(PeopleTenantProvisioningService.AssignmentChangeReasonSeed::reasonCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertThat(provisioned).containsAll(fixtureReasons);
    }

    @Test
    void identicalReasonCatalogReplayDoesNotChurnVersions() {
        String upsert = PeopleTenantProvisioningService.ASSIGNMENT_CHANGE_REASON_UPSERT_SQL;
        String normalized = upsert.replaceAll("\\s+", " ");

        assertThat(upsert).contains("ON CONFLICT (tenant_id, reason_code) DO UPDATE");
        assertThat(upsert).contains("version = ppl_assignment_change_reason_catalog.version + 1");
        assertThat(normalized).contains(
                "WHERE ppl_assignment_change_reason_catalog.display_name IS DISTINCT FROM EXCLUDED.display_name",
                "ppl_assignment_change_reason_catalog.description IS DISTINCT FROM EXCLUDED.description",
                "ppl_assignment_change_reason_catalog.label_i18n IS DISTINCT FROM EXCLUDED.label_i18n",
                "ppl_assignment_change_reason_catalog.sort_order IS DISTINCT FROM EXCLUDED.sort_order",
                "ppl_assignment_change_reason_catalog.predefined IS DISTINCT FROM EXCLUDED.predefined",
                "ppl_assignment_change_reason_catalog.lifecycle_state IS DISTINCT FROM EXCLUDED.lifecycle_state");
    }

    private List<PeopleTenantProvisioningService.AssignmentChangeReasonSeed> reasonsBlock(
            String resource) {
        String sql = resource(resource);
        int declaration = sql.indexOf("reasons(reason_code, display_name, description,");
        int values = sql.indexOf("VALUES", declaration);
        int end = sql.indexOf(")\nINSERT INTO ppl_assignment_change_reason_catalog", values);
        assertThat(declaration).as(resource + " reasons declaration").isGreaterThanOrEqualTo(0);
        assertThat(values).as(resource + " reasons values").isGreaterThan(declaration);
        assertThat(end).as(resource + " reasons end").isGreaterThan(values);
        Matcher matcher = REASON.matcher(sql.substring(values, end));
        List<PeopleTenantProvisioningService.AssignmentChangeReasonSeed> result =
                new ArrayList<>();
        while (matcher.find()) {
            result.add(new PeopleTenantProvisioningService.AssignmentChangeReasonSeed(
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(3),
                    matcher.group(4),
                    Integer.parseInt(matcher.group(5))));
        }
        assertThat(result).as(resource + " parsed reasons").isNotEmpty();
        return List.copyOf(result);
    }

    private String resource(String name) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing test resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read test resource: " + name, exception);
        }
    }
}
