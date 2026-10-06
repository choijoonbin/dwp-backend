package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;

class MigrationControlMainTest {

    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V([0-9]+)__.*\\.sql$");
    private static final Pattern TEMP_TABLE = Pattern.compile(
            "(?i)\\bCREATE\\s+(?:TEMP|TEMPORARY)\\s+TABLE\\b");
    private static final Pattern CREATE_SCHEMA = Pattern.compile(
            "(?i)\\bCREATE\\s+SCHEMA\\b");
    private static final Pattern APPROVAL_RETENTION_AUTHORITY = Pattern.compile(
            "\\b(?:dwp_approval_retention_owner|dwp_approval_retention_executor|"
                    + "apr_retention_internal)\\b");

    @Test
    void canonicalFramingIncludesUtf8ByteLengthAndNullSentinel() throws Exception {
        MessageDigest digest = ControlValues.sha256();
        ControlValues.append(digest, "history-v1");
        ControlValues.append(digest, 1);
        ControlValues.append(digest, null);
        ControlValues.append(digest, "급여");

        assertEquals(
                "223870fbb218d8ca92301bc1923e0c602b9db35533062942fe5b80bef7ceb5d5",
                HexFormat.of().formatHex(digest.digest()));
    }

    @Test
    void identifierValidationRejectsSqlTokensAndNonCanonicalNames() {
        assertEquals(
                "dwp_notification_migration",
                ControlValues.identifier("dwp_notification_migration"));
        for (String value : new String[] {
                "DWP_NOTIFICATION_MIGRATION", "dwp-user", "public;DROP SCHEMA public", ""
        }) {
            assertThrows(
                    IllegalStateException.class,
                    () -> ControlValues.identifier(value));
        }
    }

    @Test
    void commandArgumentsAreRejectedBeforeCredentialsAreRead() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> MigrationControlMain.main(new String[] {"password"}));
        assertEquals(
                "Migration Control accepts no command arguments or credentials",
                failure.getMessage());
    }

    @Test
    void temporaryAuthorityInventoryMatchesEveryImmutableSource() throws Exception {
        Path workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(workspace.resolve("dwp-people-server"))) {
            workspace = workspace.getParent();
        }
        for (String service : Set.of(
                "auth", "platform", "people", "provider", "time", "payroll", "approval",
                "notification")) {
            Path migrations = workspace.resolve("dwp-" + service
                    + "-server/src/main/resources/db/migration");
            Set<String> actual = new TreeSet<>((left, right) ->
                    Integer.compare(Integer.parseInt(left), Integer.parseInt(right)));
            try (var files = Files.list(migrations)) {
                for (Path migration : files.toList()) {
                    Matcher version = VERSIONED_MIGRATION.matcher(
                            migration.getFileName().toString());
                    if (version.matches()
                            && TEMP_TABLE.matcher(Files.readString(migration)).find()) {
                        actual.add(version.group(1));
                    }
                }
            }
            assertEquals(
                    actual,
                    Set.copyOf(ControlPlan.forService(service).temporaryMigrationVersions()),
                    service);
        }
    }

    @Test
    void databaseCreateAuthorityInventoryPinsEveryImmutableSource() throws Exception {
        Path workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (!Files.isDirectory(workspace.resolve("dwp-approval-server"))) {
            workspace = workspace.getParent();
        }
        for (String service : Set.of(
                "auth", "platform", "people", "provider", "time", "payroll",
                "approval", "notification")) {
            Path migrations = workspace.resolve("dwp-" + service
                    + "-server/src/main/resources/db/migration");
            Set<String> actualVersions = new TreeSet<>((left, right) ->
                    Integer.compare(Integer.parseInt(left), Integer.parseInt(right)));
            try (var files = Files.list(migrations)) {
                for (Path migration : files.toList()) {
                    Matcher version = VERSIONED_MIGRATION.matcher(
                            migration.getFileName().toString());
                    if (version.matches()
                            && CREATE_SCHEMA.matcher(Files.readString(migration)).find()) {
                        actualVersions.add(version.group(1));
                    }
                }
            }
            ControlPlan plan = ControlPlan.forService(service);
            assertEquals(
                    actualVersions,
                    plan.databaseCreateMigrations().stream()
                            .map(DatabaseCreateMigration::version)
                            .collect(java.util.stream.Collectors.toSet()),
                    service);
            Set<String> managedRoleVersions = new TreeSet<>((left, right) ->
                    Integer.compare(Integer.parseInt(left), Integer.parseInt(right)));
            try (var files = Files.list(migrations)) {
                for (Path migration : files.toList()) {
                    Matcher version = VERSIONED_MIGRATION.matcher(
                            migration.getFileName().toString());
                    if (version.matches()
                            && APPROVAL_RETENTION_AUTHORITY.matcher(
                                    Files.readString(migration)).find()) {
                        managedRoleVersions.add(version.group(1));
                    }
                }
            }
            assertEquals(
                    managedRoleVersions,
                    plan.managedRoleMigrations().stream()
                            .map(DatabaseCreateMigration::version)
                            .collect(java.util.stream.Collectors.toSet()),
                    service);
            for (DatabaseCreateMigration capability
                    : plan.managedRoleMigrations()) {
                if ("approval".equals(service)) {
                    assertEquals(
                            "approval-retention-privileged-migrations-v1",
                            capability.planVersion());
                    assertEquals("public", capability.ownerCapabilitySchema());
                }
                Path source = migrations.resolve(capability.fileName());
                assertEquals(
                        capability.sourceSha256(),
                        HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(source))));
                CRC32 checksum = new CRC32();
                try (var reader = Files.newBufferedReader(
                        source, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        checksum.update(line.getBytes(StandardCharsets.UTF_8));
                    }
                }
                assertEquals(capability.checksum(), (int) checksum.getValue());
            }
        }
    }

    @Test
    void databaseCreateSchemaDeltaRejectsWrongOwnerAndAdditionalSchema() {
        DatabaseCreateMigration capability =
                DatabaseCreateMigration.APPROVAL_RETENTION_FOUNDATION;
        Map<String, String> before = Map.of("public", "approval_migration");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                DatabaseCreateMigrationControl.requireExactSchemaDelta(
                        capability,
                        before,
                        Map.of(
                                "public", "approval_migration",
                                "apr_retention_internal",
                                "dwp_approval_retention_owner")));
        assertThrows(IllegalStateException.class, () ->
                DatabaseCreateMigrationControl.requireExactSchemaDelta(
                        capability,
                        before,
                        Map.of(
                                "public", "approval_migration",
                                "apr_retention_internal", "wrong_owner")));
        assertThrows(IllegalStateException.class, () ->
                DatabaseCreateMigrationControl.requireExactSchemaDelta(
                        capability,
                        before,
                        Map.of(
                                "public", "approval_migration",
                                "apr_retention_internal",
                                "dwp_approval_retention_owner",
                                "unexpected_schema", "approval_migration")));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                DatabaseCreateMigrationControl.requireExactSchemaDelta(
                        DatabaseCreateMigration.APPROVAL_RETENTION_EXECUTION,
                        before,
                        before));
        assertThrows(IllegalStateException.class, () ->
                DatabaseCreateMigrationControl.requireExactSchemaDelta(
                        DatabaseCreateMigration.APPROVAL_RETENTION_EXECUTION,
                        before,
                        Map.of(
                                "public", "approval_migration",
                                "unexpected_schema", "approval_migration")));
    }

    @Test
    void receiptDigestChainsTheExactPreviousRun() throws Exception {
        StreamSeal previousSeal = new StreamSeal(
                "auth-main", 1, 1, "b".repeat(64), 1, "c".repeat(64), "");
        ControlEnvironment firstEnvironment = environment(Map.of(), "");
        ControlEnvironment restartedEnvironment = environment(
                Map.of("auth-main", previousSeal), "d".repeat(64));

        ControlRunReceipt first = ControlRunReceipt.create(
                "NATIVE_FRESH", firstEnvironment, "18.4", true, Set.of(previousSeal).stream().toList());
        ControlRunReceipt restarted = ControlRunReceipt.create(
                "NATIVE_FRESH", restartedEnvironment, "18.4", true,
                Set.of(previousSeal).stream().toList());

        assertEquals("", first.previousRunReceiptSha256());
        assertEquals("d".repeat(64), restarted.previousRunReceiptSha256());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                first.receiptSha256(), restarted.receiptSha256());
        org.junit.jupiter.api.Assertions.assertTrue(
                restarted.toJson().contains(
                        "\"previousRunReceiptSha256\":\"" + "d".repeat(64) + "\""));
    }

    @Test
    void previousSealsMustCoverTheExactPlanAndUseCanonicalChainValues() {
        StreamSeal primary = new StreamSeal(
                "people-main", 1, 1, "b".repeat(64), 1, "c".repeat(64), "");
        assertThrows(
                IllegalStateException.class,
                () -> peopleEnvironment(
                        ControlEnvironment.Mode.PEOPLE_FRESH,
                        Map.of(), "", Map.of("people-main", primary), "d".repeat(64)));
        assertThrows(
                IllegalStateException.class,
                () -> peopleEnvironment(
                        ControlEnvironment.Mode.ADOPT_OR_UPGRADE,
                        Map.of("people-main", "a".repeat(64)),
                        "dwp-migration-control-v2:" + "b".repeat(64),
                        Map.of(),
                        "d".repeat(64)));
        assertThrows(
                IllegalStateException.class,
                () -> peopleEnvironment(
                        ControlEnvironment.Mode.PEOPLE_FRESH,
                        Map.of(), "", Map.of(), "not-a-digest"));

        StreamSeal notificationSeal = new StreamSeal(
                "notification-main", 27, 27, "e".repeat(64), 136,
                "f".repeat(64), "");
        ControlEnvironment notificationRestart = new ControlEnvironment(
                ControlEnvironment.Mode.NOTIFICATION_FRESH,
                ControlPlan.forService("notification"),
                "jdbc:postgresql://localhost:5432/dwp_notification_test",
                "dwp_notification_test",
                "dwp_user",
                "bootstrap_password",
                "dwp_notification_migration",
                "migration_password",
                "dwp_notification_runtime",
                "runtime_password",
                "dwp-migration-control-v2:" + "a".repeat(64),
                Map.of(),
                "",
                Map.of("notification-main", notificationSeal),
                "d".repeat(64));
        assertEquals(
                notificationSeal,
                notificationRestart.previousNativeSeals().get("notification-main"));
    }

    @Test
    void peopleControlRequiresBothSchemasToBePreprovisioned() {
        ControlPlan people = ControlPlan.forService("people");

        assertEquals(2, people.streams().size());
        for (StreamPlan stream : people.streams()) {
            assertEquals(false, stream.createSchemas(), stream.streamKey());
        }
    }

    @Test
    void timeControlDeclaresOnlyRuntimeRowLockColumnUpdates() {
        assertEquals(
                Set.of(
                        "public.tim_target_population_projections:updated_at",
                        "public.tim_target_population_actor_grants:updated_at",
                        "public.tim_target_population_members:updated_at"),
                ControlPlan.forService("time").runtimeColumnUpdateGrants().stream()
                        .map(grant -> grant.schema() + "." + grant.table() + ":"
                                + String.join(",", grant.columns()))
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                List.of(),
                ControlPlan.forService("payroll").runtimeColumnUpdateGrants());
    }

    @Test
    void platformControlPinsDigestWrapperAndDeniesDirectAuditMutation() {
        ControlPlan platform = ControlPlan.forService("platform");

        assertEquals(List.of(), ControlPlan.forService("auth").runtimeRoutineAllowlist());
        assertEquals(Set.of(
                        "public.wp_policy_snapshot_sha256(jsonb)",
                        "public.wp_redact_facility_audit_evidence("
                                + "bigint,varchar,uuid)"),
                platform.runtimeRoutineAllowlist().stream()
                .map(routine -> routine.schema() + "." + routine.name()
                        + "(" + routine.argumentTypes() + ")")
                .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of(
                        "sys_api_history:UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER",
                        "sys_audit_events:UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER",
                        "wp_audit_events:UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER",
                        "sys_platform_audit_events:UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER",
                        "sys_audit_retention_execution_fence:"
                                + "SELECT, INSERT, UPDATE, DELETE, TRUNCATE, "
                                + "REFERENCES, TRIGGER"),
                platform.runtimeTableDenials().stream()
                        .map(denial -> denial.table() + ":" + denial.privilegeList())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of("sys_people_audit_events:UPDATE, DELETE, TRUNCATE, "
                        + "REFERENCES, TRIGGER"),
                ControlPlan.forService("people").runtimeTableDenials().stream()
                        .map(denial -> denial.table() + ":" + denial.privilegeList())
                        .collect(java.util.stream.Collectors.toSet()));
        ControlPlan approval = ControlPlan.forService("approval");
        assertEquals(
                List.of("public", "apr_retention_internal"),
                approval.protectedSchemas(approval.streams().getFirst()));
        assertEquals(
                Set.of(
                        "24:stage record wide approval retention:"
                                + "V24__stage_record_wide_approval_retention.sql:"
                                + "720574134:true:public:apr_retention_internal:"
                                + "dwp_approval_retention_owner",
                        "28:execute exact record retention with information and signature inventory:"
                                + "V28__execute_exact_record_retention_with_information_and_signature_inventory.sql:"
                                + "1291813914:false:public::dwp_approval_retention_owner",
                        "29:manage retention policy and verified foreign copy journals:"
                                + "V29__manage_retention_policy_and_verified_foreign_copy_journals.sql:"
                                + "-1073963779:false:public::dwp_approval_retention_owner",
                        "30:seal original system sla source witnesses:"
                                + "V30__seal_original_system_sla_source_witnesses.sql:"
                                + "298487887:false:public::dwp_approval_retention_owner",
                        "31:include original system sla witness in exact record retention:"
                                + "V31__include_original_system_sla_witness_in_exact_record_retention.sql:"
                                + "1966161731:false:public::dwp_approval_retention_owner",
                        "32:capture original retention command witnesses:"
                                + "V32__capture_original_retention_command_witnesses.sql:"
                                + "-612907371:false:public::dwp_approval_retention_owner",
                        "33:include original retention command witness in exact record retention:"
                                + "V33__include_original_retention_command_witness_in_exact_record_retention.sql:"
                                + "-1306035251:false:public::dwp_approval_retention_owner",
                        "38:bind retention aware trigger entrypoints to owner:"
                                + "V38__bind_retention_aware_trigger_entrypoints_to_owner.sql:"
                                + "-428315308:false:public::dwp_approval_retention_owner"),
                approval.managedRoleMigrations().stream()
                        .map(migration -> migration.version() + ":"
                                + migration.description() + ":"
                                + migration.fileName() + ":"
                                + migration.checksum() + ":"
                                + migration.databaseCreate() + ":"
                                + migration.ownerCapabilitySchema() + ":"
                                + migration.introducedSchema() + ":"
                                + migration.schemaOwner())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of(
                        "24:stage record wide approval retention:"
                                + "V24__stage_record_wide_approval_retention.sql:"
                                + "720574134:apr_retention_internal:"
                                + "dwp_approval_retention_owner"),
                approval.databaseCreateMigrations().stream()
                        .map(migration -> migration.version() + ":"
                                + migration.description() + ":"
                                + migration.fileName() + ":"
                                + migration.checksum() + ":"
                                + migration.introducedSchema() + ":"
                                + migration.schemaOwner())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of(
                        "dwp_approval_retention_owner:24:true:"
                                + "apr_retention_internal,public",
                        "dwp_approval_retention_executor:24:false:"),
                approval.managedDatabaseRoles().stream()
                        .map(role -> role.name() + ":" + role.introducedInVersion()
                                + ":" + role.migrationAuthority() + ":"
                                + role.allowedOwnershipSchemas().stream().sorted()
                                        .collect(java.util.stream.Collectors.joining(",")))
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of(
                        "dwp_approval_retention_owner",
                        "dwp_approval_retention_executor"),
                approval.managedRoleNames());
        assertEquals(
                Set.of("dwp_approval_retention_owner"),
                approval.managedObjectOwnerNames());
        assertEquals(
                Set.of(
                        "dwp_approval_retention_owner:SCHEMA:public:*:USAGE",
                        "dwp_approval_retention_executor:SCHEMA:public:*:USAGE",
                        "dwp_approval_retention_owner:ROUTINE:public:"
                                + "public.system_sla_witness_canonical_json("
                                + "value jsonb, depth integer):EXECUTE",
                        "dwp_approval_retention_owner:ROUTINE:public:"
                                + "public.approval_typed_form_canonical_json("
                                + "value jsonb):EXECUTE"),
                approval.auxiliaryRequiredAclPrivileges().stream()
                        .map(privilege -> privilege.grantee() + ":"
                                + privilege.objectClass() + ":"
                                + privilege.schema() + ":"
                                + privilege.objectIdentity() + ":"
                                + privilege.privilege())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of(
                        "dwp_approval_retention_owner:SCHEMA:public:*:USAGE",
                        "dwp_approval_retention_executor:SCHEMA:public:*:USAGE"),
                approval.auxiliaryRequiredSchemaUsagePrivileges().stream()
                        .map(privilege -> privilege.grantee() + ":"
                                + privilege.objectClass() + ":"
                                + privilege.schema() + ":"
                                + privilege.objectIdentity() + ":"
                                + privilege.privilege())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of(
                        "public.seed_approval_tenant(bigint)",
                        "public.seed_approval_product_templates(bigint)",
                        "public.seed_approval_form_catalog(bigint)",
                        "public.apr_commit_high_risk_idempotency(uuid,text,jsonb)"),
                approval.runtimeRoutineAllowlist().stream()
                        .map(routine -> routine.schema() + "." + routine.name()
                                + "(" + routine.argumentTypes() + ")")
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(
                Set.of(
                        "apr_step_up_replay_ledger:UPDATE, DELETE, TRUNCATE, "
                                + "REFERENCES, TRIGGER",
                        "apr_high_risk_idempotency_ledger:UPDATE, DELETE, TRUNCATE, "
                                + "REFERENCES, TRIGGER",
                        "apr_high_risk_idempotency_transition_fence:"
                                + "SELECT, INSERT, UPDATE, DELETE, TRUNCATE, "
                                + "REFERENCES, TRIGGER"),
                approval.runtimeTableDenials().stream()
                        .map(denial -> denial.table() + ":" + denial.privilegeList())
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void notificationPrivilegedMigrationsArePinnedToExactReviewedSources() {
        ControlEnvironment notification = new ControlEnvironment(
                ControlEnvironment.Mode.NOTIFICATION_FRESH,
                ControlPlan.forService("notification"),
                "jdbc:postgresql://localhost:5432/dwp_notification_test",
                "dwp_notification_test",
                "dwp_user",
                "bootstrap_password",
                "dwp_notification_migration",
                "migration_password",
                "dwp_notification_runtime",
                "runtime_password",
                "dwp-migration-control-v2:" + "a".repeat(64),
                Map.of(),
                "",
                Map.of(),
                "");

        for (NotificationPrivilegedMigration migration
                : NotificationPrivilegedMigration.values()) {
            migration.requireAttestedSource(notification);
            var privileged = FlywayControl.loadPrivilegedNotification(
                    notification, notification.plan().streams().getFirst(), migration);
            assertEquals(true, privileged.getConfiguration().isSkipDefaultCallbacks());
            assertEquals(0, privileged.getConfiguration().getCallbacks().length);
        }
        assertEquals(
                Set.of("2", "22"),
                Set.of(
                        NotificationPrivilegedMigration.ROLE_FOUNDATION.version(),
                        NotificationPrivilegedMigration.AUDIT_RELAY.version()));
    }

    private static ControlEnvironment environment(
            Map<String, StreamSeal> previous, String previousReceipt) {
        return new ControlEnvironment(
                ControlEnvironment.Mode.STRICT_FRESH,
                ControlPlan.forService("auth"),
                "jdbc:postgresql://localhost:5432/dwp_auth_test",
                "dwp_auth_test",
                "dwp_user",
                "bootstrap_password",
                "dwp_auth_migration",
                "migration_password",
                "dwp_auth_runtime",
                "runtime_password",
                "dwp-migration-control-v2:" + "a".repeat(64),
                Map.of(),
                "",
                previous,
                previousReceipt);
    }

    private static ControlEnvironment peopleEnvironment(
            ControlEnvironment.Mode mode,
            Map<String, String> adopted,
            String previousReference,
            Map<String, StreamSeal> nativeSeals,
            String previousReceipt) {
        return new ControlEnvironment(
                mode,
                ControlPlan.forService("people"),
                "jdbc:postgresql://localhost:5432/dwp_people_test",
                "dwp_people_test",
                "dwp_user",
                "bootstrap_password",
                "dwp_people_migration",
                "migration_password",
                "dwp_people_runtime",
                "runtime_password",
                "dwp-migration-control-v2:" + "a".repeat(64),
                adopted,
                previousReference,
                nativeSeals,
                previousReceipt);
    }
}
