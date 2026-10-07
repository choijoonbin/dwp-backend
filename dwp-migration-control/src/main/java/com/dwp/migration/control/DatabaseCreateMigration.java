package com.dwp.migration.control;

/**
 * Source-pinned migration that needs a pre-provisioned managed-role capability.
 *
 * <p>The Flyway login remains NOCREATEROLE and NOSUPERUSER. Migration Control
 * opens only the capabilities declared here for this exact source, and closes
 * them before another migration can run.</p>
 */
enum DatabaseCreateMigration {
    APPROVAL_RETENTION_FOUNDATION(
            "approval",
            "approval-retention-privileged-migrations-v1",
            "24",
            "stage record wide approval retention",
            "V24__stage_record_wide_approval_retention.sql",
            720574134,
            "670c936735915ca2b739512b8ec1f512685192d408635694ae91c84e68674d6c",
            true,
            "public",
            "apr_retention_internal",
            "dwp_approval_retention_owner"),
    APPROVAL_RETENTION_EXECUTION(
            "approval",
            "approval-retention-privileged-migrations-v1",
            "28",
            "execute exact record retention with information and signature inventory",
            "V28__execute_exact_record_retention_with_information_and_signature_inventory.sql",
            1291813914,
            "9bee8045d5f466c067f48da3dd88c3d730ffb8647a2ef6ee08a5fb64fbba13a9",
            false,
            "public",
            "",
            "dwp_approval_retention_owner"),
    APPROVAL_RETENTION_DISPATCH(
            "approval",
            "approval-retention-privileged-migrations-v1",
            "29",
            "manage retention policy and verified foreign copy journals",
            "V29__manage_retention_policy_and_verified_foreign_copy_journals.sql",
            -1073963779,
            "63c4e398e933e6341301065207a50b95f71c69d08162e697bc97d72dcf012701",
            false,
            "public",
            "",
            "dwp_approval_retention_owner"),
    APPROVAL_RETENTION_SLA_WITNESS(
            "approval",
            "approval-retention-privileged-migrations-v1",
            "30",
            "seal original system sla source witnesses",
            "V30__seal_original_system_sla_source_witnesses.sql",
            298487887,
            "cb0350d0ccede13bf642ff2c21485448e4af62bbc200bb90584305ea84c215f7",
            false,
            "public",
            "",
            "dwp_approval_retention_owner"),
    APPROVAL_RETENTION_SLA_INVENTORY(
            "approval",
            "approval-retention-privileged-migrations-v1",
            "31",
            "include original system sla witness in exact record retention",
            "V31__include_original_system_sla_witness_in_exact_record_retention.sql",
            1966161731,
            "0a850f3c776e5d6c544b646de390ad3f3ecbf075480582333596c40f3d64b12b",
            false,
            "public",
            "",
            "dwp_approval_retention_owner"),
    APPROVAL_RETENTION_COMMAND_WITNESS(
            "approval",
            "approval-retention-privileged-migrations-v1",
            "32",
            "capture original retention command witnesses",
            "V32__capture_original_retention_command_witnesses.sql",
            -612907371,
            "fd6fa3729a87662288e2a338c99af770a789dd465a18fd78925bb0ab26f5ac04",
            false,
            "public",
            "",
            "dwp_approval_retention_owner"),
    APPROVAL_RETENTION_COMMAND_INVENTORY(
            "approval",
            "approval-retention-privileged-migrations-v1",
            "33",
            "include original retention command witness in exact record retention",
            "V33__include_original_retention_command_witness_in_exact_record_retention.sql",
            -1306035251,
            "e24605d28caf723ec61f010598ca5e0bfb108fd62dd27d581a512f118f734c2d",
            false,
            "public",
            "",
            "dwp_approval_retention_owner"),
    APPROVAL_RETENTION_TRIGGER_OWNERS(
            "approval",
            "approval-retention-privileged-migrations-v1",
            "38",
            "bind retention aware trigger entrypoints to owner",
            "V38__bind_retention_aware_trigger_entrypoints_to_owner.sql",
            -428315308,
            "9c676f86e53b885308e88766d91ec453122f0033f1077c46a33d8231084c58f9",
            false,
            "public",
            "",
            "dwp_approval_retention_owner");

    private final String service;
    private final String planVersion;
    private final String version;
    private final String description;
    private final String fileName;
    private final int checksum;
    private final String sha256;
    private final boolean databaseCreate;
    private final String ownerCapabilitySchema;
    private final String introducedSchema;
    private final String schemaOwner;

    DatabaseCreateMigration(
            String service,
            String planVersion,
            String version,
            String description,
            String fileName,
            int checksum,
            String sha256,
            boolean databaseCreate,
            String ownerCapabilitySchema,
            String introducedSchema,
            String schemaOwner) {
        this.service = service;
        this.planVersion = planVersion;
        this.version = version;
        this.description = description;
        this.fileName = fileName;
        this.checksum = checksum;
        this.sha256 = sha256;
        this.databaseCreate = databaseCreate;
        this.ownerCapabilitySchema = ownerCapabilitySchema;
        this.introducedSchema = introducedSchema;
        this.schemaOwner = schemaOwner;
        if (databaseCreate != !introducedSchema.isEmpty()) {
            throw new IllegalArgumentException(
                    "Database CREATE and introduced-schema declarations must match");
        }
    }

    String version() {
        return version;
    }

    String service() {
        return service;
    }

    String planVersion() {
        return planVersion;
    }

    String description() {
        return description;
    }

    String fileName() {
        return fileName;
    }

    int checksum() {
        return checksum;
    }

    String sourceSha256() {
        return sha256;
    }

    boolean databaseCreate() {
        return databaseCreate;
    }

    String ownerCapabilitySchema() {
        return ownerCapabilitySchema;
    }

    boolean introducesSchema() {
        return !introducedSchema.isEmpty();
    }

    String introducedSchema() {
        return introducedSchema;
    }

    String schemaOwner() {
        return schemaOwner;
    }

}
