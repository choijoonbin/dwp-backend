package com.dwp.migration.control;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.dwp.core.database.AuxiliaryRoleAclGuard.AllowedPrivilege;

record ControlPlan(
        String service,
        List<StreamPlan> streams,
        String runtimePlaceholder,
        List<RuntimeRoutine> runtimeRoutineAllowlist,
        List<RuntimeTableDenial> runtimeTableDenials,
        List<String> temporaryMigrationVersions) {

    /**
     * Static capability roles are cluster objects and cannot safely be created
     * by a NOCREATEROLE Flyway login. Keep their exact names, introduction
     * versions and owner surfaces in the service plan instead of hiding role
     * provisioning in a test or deployment script.
     */
    List<ManagedDatabaseRole> managedDatabaseRoles() {
        return switch (service) {
            case "approval" -> List.of(
                    new ManagedDatabaseRole(
                            "dwp_approval_retention_owner",
                            "24",
                            true,
                            List.of("public", "apr_retention_internal")),
                    new ManagedDatabaseRole(
                            "dwp_approval_retention_executor",
                            "24",
                            false,
                            List.of()));
            default -> List.of();
        };
    }

    /** Exact auxiliary role-name declaration, independent of its state verifier. */
    Set<String> managedRoleNames() {
        return managedDatabaseRoles().stream()
                .map(ManagedDatabaseRole::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    String projectionPublisherPrincipal() {
        return switch (service) {
            case "payroll" -> "dwp_payroll_projection_publisher";
            case "time" -> "dwp_time_projection_publisher";
            default -> "";
        };
    }

    String projectionPublisherPlaceholder() {
        return switch (service) {
            case "payroll" -> "payrollProjectionPublisherRole";
            case "time" -> "timeProjectionPublisherRole";
            default -> "";
        };
    }

    List<RequiredDatabaseExtension> requiredDatabaseExtensions() {
        return switch (service) {
            case "people" -> List.of(
                    new RequiredDatabaseExtension("btree_gist", "public", "1.7"),
                    new RequiredDatabaseExtension("pgcrypto", "public", "1.3"));
            case "payroll", "time" -> List.of(new RequiredDatabaseExtension(
                    "btree_gist", "public", "1.7"));
            default -> List.of();
        };
    }

    Set<String> auxiliaryAclPrincipalNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>(managedRoleNames());
        if (!projectionPublisherPrincipal().isEmpty()) {
            names.add(projectionPublisherPrincipal());
        }
        return Set.copyOf(names);
    }

    /** Exact auxiliary owners permitted to retain plan-declared protected objects. */
    Set<String> managedObjectOwnerNames() {
        return managedDatabaseRoles().stream()
                .filter(role -> !role.allowedOwnershipSchemas().isEmpty())
                .map(ManagedDatabaseRole::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Complete definition/ACL inventory sealed for a stream's receipt. */
    List<String> protectedSchemas(StreamPlan stream) {
        LinkedHashSet<String> schemas = new LinkedHashSet<>();
        schemas.add(stream.schema());
        for (ManagedDatabaseRole role : managedDatabaseRoles()) {
            schemas.addAll(role.allowedOwnershipSchemas());
        }
        return List.copyOf(schemas);
    }

    /** Immutable versions that create a plan-owned private schema. */
    List<DatabaseCreateMigration> databaseCreateMigrations() {
        return switch (service) {
            case "approval" -> List.of(
                    DatabaseCreateMigration.APPROVAL_RETENTION_FOUNDATION);
            default -> List.of();
        };
    }

    /** Immutable migrations that require the temporary retention-owner capability. */
    List<DatabaseCreateMigration> managedRoleMigrations() {
        return switch (service) {
            case "approval" -> List.of(DatabaseCreateMigration.values());
            default -> List.of();
        };
    }

    /** Role-specific ACL admission floor; exact ACL bytes are sealed in inventory. */
    Set<AllowedPrivilege> auxiliaryAclPrivileges() {
        if ("payroll".equals(service)) {
            return payrollPublisherPrivileges();
        }
        if ("time".equals(service)) {
            return timePublisherPrivileges();
        }
        if (!"approval".equals(service)) return Set.of();
        String owner = "dwp_approval_retention_owner";
        String executor = "dwp_approval_retention_executor";
        String internal = "apr_retention_internal";
        return Set.of(
                allowed(owner, "RELATION", "public", "SELECT"),
                allowed(owner, "RELATION", "public", "INSERT"),
                allowedObject(owner, "RELATION", "public",
                        "public.apr_requests", "UPDATE"),
                allowedObject(owner, "RELATION", "public",
                        "public.apr_tenants", "UPDATE"),
                allowedObject(owner, "RELATION", "public",
                        "public.apr_document_heads", "UPDATE"),
                allowedObject(owner, "RELATION", "public",
                        "public.apr_document_policy_heads", "UPDATE"),
                allowedObject(owner, "RELATION", "public",
                        "public.apr_attachment_policy_heads", "UPDATE"),
                allowedObject(owner, "RELATION", "public",
                        "public.apr_retention_dispatch_intents", "UPDATE"),
                allowedObject(owner, "COLUMN", "public",
                        "public.apr_quorum_information_commands.tenant_id", "UPDATE"),
                allowedObject(owner, "ROUTINE", "public",
                        "public.system_sla_witness_canonical_json(value jsonb, depth integer)",
                        "EXECUTE"),
                allowedObject(owner, "ROUTINE", "public",
                        "public.approval_typed_form_canonical_json(value jsonb)",
                        "EXECUTE"),
                allowed(owner, "RELATION", "public", "DELETE"),
                allowed(owner, "SCHEMA", "public", "USAGE"),
                allowed(executor, "SCHEMA", "public", "USAGE"),
                allowed(executor, "SCHEMA", internal, "USAGE"),
                allowed(executor, "ROUTINE", internal, "EXECUTE"),
                allowed(executor, "RELATION", "public", "SELECT"));
    }

    /** Minimum steady ACLs needed to execute/read the reviewed retention surface. */
    Set<AllowedPrivilege> auxiliaryRequiredAclPrivileges() {
        if ("payroll".equals(service) || "time".equals(service)) {
            return auxiliaryAclPrivileges();
        }
        if (!"approval".equals(service)) {
            return Set.of();
        }
        return Set.of(
                allowed(
                        "dwp_approval_retention_owner",
                        "SCHEMA",
                        "public",
                        "USAGE"),
                allowedObject(
                        "dwp_approval_retention_owner",
                        "ROUTINE",
                        "public",
                        "public.system_sla_witness_canonical_json(value jsonb, depth integer)",
                        "EXECUTE"),
                allowedObject(
                        "dwp_approval_retention_owner",
                        "ROUTINE",
                        "public",
                        "public.approval_typed_form_canonical_json(value jsonb)",
                        "EXECUTE"),
                allowed(
                        "dwp_approval_retention_executor",
                        "SCHEMA",
                        "public",
                        "USAGE"));
    }

    /** Schema reachability that Control itself normalizes after migrations close. */
    Set<AllowedPrivilege> auxiliaryRequiredSchemaUsagePrivileges() {
        return auxiliaryRequiredAclPrivileges().stream()
                .filter(privilege -> "SCHEMA".equals(privilege.objectClass())
                        && "USAGE".equals(privilege.privilege())
                        && "*".equals(privilege.objectIdentity()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    List<String> fencedReadOnlyPrincipals() {
        return switch (service) {
            case "auth" -> List.of("dwp_provider_metadata_auth");
            case "platform" -> List.of("dwp_provider_metadata_platform");
            case "people" -> List.of("dwp_provider_metadata_people");
            case "provider" -> List.of("dwp_provider_metadata_self");
            default -> List.of();
        };
    }

    /** Narrow write ACLs required only to let PostgreSQL take row locks for reads. */
    List<RuntimeColumnUpdateGrant> runtimeColumnUpdateGrants() {
        if (!"time".equals(service)) {
            return List.of();
        }
        return List.of(
                projectionRuntimeLockGrant("tim_target_population_projections"),
                projectionRuntimeLockGrant("tim_target_population_actor_grants"),
                projectionRuntimeLockGrant("tim_target_population_members"));
    }

    static ControlPlan forService(String service) {
        return switch (service) {
            case "auth" -> single(
                    service,
                    "auth-main",
                    true,
                    false,
                    null,
                    List.of(),
                    List.of(
                            "21", "23", "28", "29", "40", "42", "43", "45",
                            "48", "49", "52", "53", "55", "57", "61", "79",
                            "80", "82", "90", "99", "101"));
            case "platform" -> single(
                    service,
                    "platform-main",
                    true,
                    false,
                    null,
                    List.of(
                            new RuntimeRoutine(
                                    "public", "wp_policy_snapshot_sha256", "jsonb"),
                            new RuntimeRoutine(
                                    "public", "wp_redact_facility_audit_evidence",
                                    "bigint,varchar,uuid")),
                    List.of(
                            new RuntimeTableDenial(
                                    "public",
                                    "sys_api_history",
                                    appendOnlyDeniedPrivileges()),
                            appendOnlyDenied("sys_audit_events"),
                            appendOnlyDenied("wp_audit_events"),
                            appendOnlyDenied("sys_platform_audit_events"),
                            new RuntimeTableDenial(
                                    "public",
                                    "sys_audit_retention_execution_fence",
                                    List.of(
                                            "SELECT", "INSERT", "UPDATE", "DELETE",
                                            "TRUNCATE", "REFERENCES", "TRIGGER"))),
                    List.of(
                            "15", "55", "64", "74", "94", "124", "126", "127",
                            "165", "169", "190", "193", "194", "198", "202", "203",
                            "204", "205", "206", "207", "208", "209", "210", "213",
                            "214", "216", "217", "218", "219"));
            case "time" -> single(
                    service,
                    "time-main",
                    false,
                    false,
                    "timeRuntimeRole",
                    List.of(),
                    List.of(
                            projectionRuntimeReadOnly("tim_target_population_projections"),
                            projectionRuntimeReadOnly("tim_target_population_actor_grants"),
                            projectionRuntimeReadOnly("tim_target_population_members")),
                    List.of());
            case "payroll" -> single(
                    service,
                    "payroll-main",
                    false,
                    false,
                    "payrollRuntimeRole",
                    List.of(),
                    List.of(
                            projectionRuntimeReadOnly(
                                    "pay_legal_entity_scope_projections"),
                            projectionRuntimeReadOnly(
                                    "pay_legal_entity_scope_members")),
                    List.of());
            case "approval" -> single(
                    service,
                    "approval-main",
                    true,
                    false,
                    null,
                    List.of(
                            new RuntimeRoutine("public", "seed_approval_tenant", "bigint"),
                            new RuntimeRoutine(
                                    "public", "seed_approval_product_templates", "bigint"),
                            new RuntimeRoutine(
                                    "public", "seed_approval_form_catalog", "bigint"),
                            new RuntimeRoutine(
                                    "public", "apr_commit_high_risk_idempotency",
                                    "uuid,text,jsonb")),
                    List.of(
                            replayLedgerDenied("apr_step_up_replay_ledger"),
                            replayLedgerDenied("apr_high_risk_idempotency_ledger"),
                            new RuntimeTableDenial(
                                    "public",
                                    "apr_high_risk_idempotency_transition_fence",
                                    List.of(
                                            "SELECT", "INSERT", "UPDATE", "DELETE",
                                            "TRUNCATE", "REFERENCES", "TRIGGER"))),
                    List.of());
            case "notification" -> single(
                    service,
                    "notification-main",
                    true,
                    false,
                    "notificationRuntimeRole",
                    List.of());
            case "people" -> new ControlPlan(
                    service,
                    List.of(
                            new StreamPlan(
                                    "people-main",
                                    "public",
                                    "flyway_schema_history",
                                    "classpath:db/migration",
                                    true,
                                    false),
                            new StreamPlan(
                                    "people-performance",
                                    "hris_performance",
                                    "flyway_performance_schema_history",
                                    "classpath:db/performance-migration",
                                    false,
                                    false)),
                    null,
                    List.of(new RuntimeRoutine(
                            "public", "seed_hr_domain_foundation", "bigint")),
                    List.of(new RuntimeTableDenial(
                            "public",
                            "sys_people_audit_events",
                            appendOnlyDeniedPrivileges())),
                    List.of("7", "24", "30", "37", "45"));
            case "provider" -> single(
                    service,
                    "provider-main",
                    true,
                    false,
                    null,
                    List.of(new RuntimeRoutine(
                            "public", "prv_bind_provider_operation_lease", "uuid,uuid")),
                    List.of("30", "31", "52"));
            default -> throw new IllegalStateException(
                    "Unsupported migration Control service: " + service);
        };
    }

    private static ControlPlan single(
            String service,
            String streamKey,
            boolean baselineOnMigrate,
            boolean createSchemas,
            String runtimePlaceholder,
            List<RuntimeRoutine> runtimeRoutineAllowlist) {
        return single(
                service,
                streamKey,
                baselineOnMigrate,
                createSchemas,
                runtimePlaceholder,
                runtimeRoutineAllowlist,
                List.of(),
                List.of());
    }

    private static ControlPlan single(
            String service,
            String streamKey,
            boolean baselineOnMigrate,
            boolean createSchemas,
            String runtimePlaceholder,
            List<RuntimeRoutine> runtimeRoutineAllowlist,
            List<String> temporaryMigrationVersions) {
        return single(
                service,
                streamKey,
                baselineOnMigrate,
                createSchemas,
                runtimePlaceholder,
                runtimeRoutineAllowlist,
                List.of(),
                temporaryMigrationVersions);
    }

    private static ControlPlan single(
            String service,
            String streamKey,
            boolean baselineOnMigrate,
            boolean createSchemas,
            String runtimePlaceholder,
            List<RuntimeRoutine> runtimeRoutineAllowlist,
            List<RuntimeTableDenial> runtimeTableDenials,
            List<String> temporaryMigrationVersions) {
        return new ControlPlan(
                service,
                List.of(new StreamPlan(
                        streamKey,
                        "public",
                        "flyway_schema_history",
                        "classpath:db/migration",
                        baselineOnMigrate,
                        createSchemas)),
                runtimePlaceholder,
                List.copyOf(runtimeRoutineAllowlist),
                List.copyOf(runtimeTableDenials),
                List.copyOf(temporaryMigrationVersions));
    }

    private static RuntimeTableDenial appendOnlyDenied(String table) {
        return new RuntimeTableDenial(
                "public", table, appendOnlyDeniedPrivileges());
    }

    private static List<String> appendOnlyDeniedPrivileges() {
        return List.of("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");
    }

    private static RuntimeTableDenial replayLedgerDenied(String table) {
        return new RuntimeTableDenial(
                "public", table,
                List.of("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"));
    }

    private static RuntimeTableDenial projectionRuntimeReadOnly(String table) {
        return new RuntimeTableDenial(
                "public", table, List.of("INSERT", "UPDATE", "DELETE"));
    }

    private static RuntimeColumnUpdateGrant projectionRuntimeLockGrant(String table) {
        return new RuntimeColumnUpdateGrant("public", table, List.of("updated_at"));
    }

    private static Set<AllowedPrivilege> payrollPublisherPrivileges() {
        String role = "dwp_payroll_projection_publisher";
        return Set.of(
                allowed(role, "SCHEMA", "public", "USAGE"),
                allowedObject(role, "RELATION", "public",
                        "public.pay_legal_entity_scope_projections", "SELECT"),
                allowedObject(role, "RELATION", "public",
                        "public.pay_legal_entity_scope_projections", "INSERT"),
                allowedObject(role, "COLUMN", "public",
                        "public.pay_legal_entity_scope_projections.status", "UPDATE"),
                allowedObject(role, "COLUMN", "public",
                        "public.pay_legal_entity_scope_projections.valid_until", "UPDATE"),
                allowedObject(role, "RELATION", "public",
                        "public.pay_legal_entity_scope_members", "SELECT"),
                allowedObject(role, "RELATION", "public",
                        "public.pay_legal_entity_scope_members", "INSERT"));
    }

    private static Set<AllowedPrivilege> timePublisherPrivileges() {
        String role = "dwp_time_projection_publisher";
        java.util.LinkedHashSet<AllowedPrivilege> privileges = new java.util.LinkedHashSet<>();
        privileges.add(allowed(role, "SCHEMA", "public", "USAGE"));
        for (String table : List.of(
                "tim_target_population_projections",
                "tim_target_population_actor_grants",
                "tim_target_population_members")) {
            privileges.add(allowedObject(role, "RELATION", "public",
                    "public." + table, "SELECT"));
            privileges.add(allowedObject(role, "RELATION", "public",
                    "public." + table, "INSERT"));
        }
        for (String column : List.of(
                "tim_target_population_projections.projection_revision",
                "tim_target_population_projections.lifecycle_state",
                "tim_target_population_projections.effective_from",
                "tim_target_population_projections.effective_to",
                "tim_target_population_projections.source_digest",
                "tim_target_population_projections.updated_at",
                "tim_target_population_projections.updated_by",
                "tim_target_population_actor_grants.population_public_id",
                "tim_target_population_actor_grants.population_revision",
                "tim_target_population_actor_grants.grant_revision",
                "tim_target_population_actor_grants.lifecycle_state",
                "tim_target_population_actor_grants.valid_from",
                "tim_target_population_actor_grants.valid_to",
                "tim_target_population_actor_grants.source_digest",
                "tim_target_population_actor_grants.updated_at",
                "tim_target_population_actor_grants.updated_by",
                "tim_target_population_members.population_revision",
                "tim_target_population_members.people_assignment_revision",
                "tim_target_population_members.membership_revision",
                "tim_target_population_members.lifecycle_state",
                "tim_target_population_members.effective_from",
                "tim_target_population_members.effective_to",
                "tim_target_population_members.source_digest",
                "tim_target_population_members.updated_at",
                "tim_target_population_members.updated_by")) {
            privileges.add(allowedObject(role, "COLUMN", "public",
                    "public." + column, "UPDATE"));
        }
        return Set.copyOf(privileges);
    }

    private static AllowedPrivilege allowed(
            String grantee, String objectClass, String schema, String privilege) {
        return new AllowedPrivilege(grantee, objectClass, schema, privilege);
    }

    private static AllowedPrivilege allowedObject(
            String grantee,
            String objectClass,
            String schema,
            String objectIdentity,
            String privilege) {
        return new AllowedPrivilege(
                grantee, objectClass, schema, objectIdentity, privilege);
    }
}
