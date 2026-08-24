package com.dwp.services.approval.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Prevents an unsafe operational rollback after non-root scope activation. */
@Component
public final class ApprovalManagementScopeCompatibilityReadiness
        implements ApplicationRunner {

    static final String CAPABILITY = "approval-management-scope-v1";

    private final JdbcTemplate jdbc;
    private final String environment;
    private final boolean productAuthorizationV2Enabled;
    private final boolean nonRootWritesEnabled;
    private final boolean clusterFenceConfirmed;

    public ApprovalManagementScopeCompatibilityReadiness(
            JdbcTemplate jdbc,
            @Value("${dwp.environment:${DWP_ENVIRONMENT:local}}") String environment,
            @Value("${dwp.approval.product-authorization-v2-enabled:false}")
                    boolean productAuthorizationV2Enabled,
            @Value("${dwp.approval.management-scope-writes-enabled:false}")
                    boolean nonRootWritesEnabled,
            @Value("${dwp.approval.management-scope-cluster-fence-confirmed:false}")
                    boolean clusterFenceConfirmed) {
        this.jdbc = jdbc;
        this.environment = environment == null ? "local" : environment;
        this.productAuthorizationV2Enabled = productAuthorizationV2Enabled;
        this.nonRootWritesEnabled = nonRootWritesEnabled;
        this.clusterFenceConfirmed = clusterFenceConfirmed;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!production() && !nonRootWritesEnabled) return;
        Integer nonRootObjects = jdbc.queryForObject("""
                SELECT COALESCE(SUM(object_count), 0)::INTEGER
                  FROM (
                    SELECT COUNT(*) AS object_count FROM apr_workflow_definitions
                     WHERE management_resource_set_key <> 'RS_APPROVALS'
                    UNION ALL
                    SELECT COUNT(*) FROM apr_forms
                     WHERE management_resource_set_key <> 'RS_APPROVALS'
                    UNION ALL
                    SELECT COUNT(*) FROM apr_form_categories
                     WHERE management_resource_set_key <> 'RS_APPROVALS'
                    UNION ALL
                    SELECT COUNT(*) FROM apr_policy_rules
                     WHERE management_resource_set_key <> 'RS_APPROVALS'
                    UNION ALL
                    SELECT COUNT(*) FROM apr_signature_providers
                     WHERE management_resource_set_key <> 'RS_APPROVALS'
                    UNION ALL
                    SELECT COUNT(*) FROM apr_requests
                     WHERE management_resource_set_key <> 'RS_APPROVALS'
                    UNION ALL
                    SELECT COUNT(*) FROM apr_integration_outbox
                     WHERE management_resource_set_key <> 'RS_APPROVALS'
                  ) scoped_objects
                """, Integer.class);
        String requiredCapability = jdbc.queryForObject("""
                SELECT minimum_reader_capability
                  FROM apr_management_scope_schema_fence
                 WHERE fence_key = 'APPROVAL_MANAGEMENT_SCOPE_V1'
                """, String.class);
        Boolean activated = jdbc.queryForObject("""
                SELECT non_root_writes_activated_at IS NOT NULL
                  FROM apr_management_scope_schema_fence
                 WHERE fence_key = 'APPROVAL_MANAGEMENT_SCOPE_V1'
                """, Boolean.class);
        if (!CAPABILITY.equals(requiredCapability)) {
            throw new IllegalStateException(
                    "Approval management scope schema capability is incompatible.");
        }
        if (!nonRootWritesEnabled
                && (Boolean.TRUE.equals(activated)
                    || nonRootObjects != null && nonRootObjects > 0)) {
            throw new IllegalStateException(
                    "Non-root Approval data exists; root-only or old-binary rollback is unsafe.");
        }
        if (production() && nonRootWritesEnabled
                && (!productAuthorizationV2Enabled || !clusterFenceConfirmed)) {
            throw new IllegalStateException(
                    "Approval management scope activation requires governed authorization "
                            + "and an all-pod compatibility fence.");
        }
    }

    private boolean production() {
        String normalized = environment.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production");
    }
}
