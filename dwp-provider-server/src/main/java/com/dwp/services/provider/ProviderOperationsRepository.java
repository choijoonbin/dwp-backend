package com.dwp.services.provider;

import com.dwp.services.provider.operation.ProviderOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class ProviderOperationsRepository {

    private static final DateTimeFormatter INCIDENT_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;

    public ProviderOperationsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ProviderDtos.CommandCenter commandCenter(ProviderDtos.EstateOverview estate) {
        List<ProviderDtos.ServicePosture> services = servicePostures();
        List<ProviderDtos.CellPosture> cells = cellPostures();
        long incidents = count("""
                SELECT COUNT(*) FROM prv_service_incidents
                 WHERE lifecycle_state NOT IN ('RESOLVED', 'CLOSED')
                """);
        long expiring = count("""
                SELECT COUNT(*) FROM prv_organization_subscriptions
                 WHERE lifecycle_state IN ('TRIAL', 'ACTIVE')
                   AND ends_at BETWEEN CURRENT_TIMESTAMP AND CURRENT_TIMESTAMP + INTERVAL '90 days'
                """);
        List<ProviderDtos.ActionItem> actions = actionItems();
        boolean critical = services.stream().anyMatch(item -> item.failedInstances() > 0)
                || count("""
                        SELECT COUNT(*) FROM prv_service_incidents
                         WHERE severity = 'SEV1' AND lifecycle_state NOT IN ('RESOLVED', 'CLOSED')
                        """) > 0;
        boolean attention = !actions.isEmpty()
                || services.stream().anyMatch(item -> item.degradedInstances() > 0);
        String state = critical ? "CRITICAL" : attention ? "ATTENTION" : "HEALTHY";
        return new ProviderDtos.CommandCenter(
                Instant.now(), state, estate, incidents, expiring, actions, services, cells, recentActivity());
    }

    public ProviderDtos.ServiceHealthOverview serviceHealth() {
        List<ProviderDtos.ServicePosture> services = servicePostures();
        List<ProviderDtos.CellPosture> cells = cellPostures();
        List<ProviderDtos.ServiceIncidentSummary> incidents = incidents(100);
        long total = services.stream().mapToLong(ProviderDtos.ServicePosture::totalInstances).sum();
        long healthy = services.stream().mapToLong(ProviderDtos.ServicePosture::healthyInstances).sum();
        long pending = services.stream().mapToLong(ProviderDtos.ServicePosture::pendingInstances).sum();
        long degraded = services.stream().mapToLong(ProviderDtos.ServicePosture::degradedInstances).sum();
        long failed = services.stream().mapToLong(ProviderDtos.ServicePosture::failedInstances).sum();
        long impacted = services.stream().mapToLong(ProviderDtos.ServicePosture::impactedTenants).sum();
        boolean critical = failed > 0 || incidents.stream().anyMatch(item ->
                "SEV1".equals(item.severity()) && !isResolved(item.lifecycleState()));
        boolean attention = degraded > 0 || pending > 0 || incidents.stream().anyMatch(item ->
                !isResolved(item.lifecycleState()));
        return new ProviderDtos.ServiceHealthOverview(
                Instant.now(), critical ? "CRITICAL" : attention ? "ATTENTION" : "HEALTHY",
                total, healthy, pending, degraded, failed, impacted, services, cells, incidents);
    }

    public ProviderDtos.CommercialOverview commercialOverview() {
        long active = count("""
                SELECT COUNT(*) FROM prv_organization_subscriptions WHERE lifecycle_state = 'ACTIVE'
                """);
        long trials = count("""
                SELECT COUNT(*) FROM prv_organization_subscriptions WHERE lifecycle_state = 'TRIAL'
                """);
        long expiring = count("""
                SELECT COUNT(*) FROM prv_organization_subscriptions
                 WHERE lifecycle_state IN ('TRIAL', 'ACTIVE')
                   AND ends_at BETWEEN CURRENT_TIMESTAMP AND CURRENT_TIMESTAMP + INTERVAL '90 days'
                """);
        long uncontracted = count("""
                SELECT COUNT(*)
                  FROM prv_organizations organization
                 WHERE organization.lifecycle_state <> 'CLOSED'
                   AND NOT EXISTS (
                       SELECT 1 FROM prv_organization_subscriptions subscription
                        WHERE subscription.organization_id = organization.organization_id
                          AND subscription.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')
                   )
                """);
        return new ProviderDtos.CommercialOverview(
                Instant.now(), active, trials, expiring, uncontracted,
                servicePlanPortfolio(), subscriptionPortfolio(), entitlementAdoption());
    }

    public ProviderDtos.AuditInsights auditInsights() {
        return new ProviderDtos.AuditInsights(
                Instant.now(),
                count("SELECT COUNT(*) FROM prv_audit_events WHERE occurred_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'"),
                count("SELECT COUNT(*) FROM prv_audit_events WHERE outcome = 'FAILED' AND occurred_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'"),
                count("SELECT COUNT(*) FROM prv_audit_events WHERE outcome = 'DENIED' AND occurred_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'"),
                count("SELECT COUNT(*) FROM prv_audit_events WHERE event_category = 'PRIVILEGED_ACCESS' AND occurred_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'"),
                metrics("""
                        SELECT outcome AS key, COUNT(*) AS total
                          FROM prv_audit_events
                         WHERE occurred_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
                         GROUP BY outcome ORDER BY total DESC, key
                        """),
                metrics("""
                        SELECT event_category AS key, COUNT(*) AS total
                          FROM prv_audit_events
                         WHERE occurred_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
                         GROUP BY event_category ORDER BY total DESC, key
                        """));
    }

    public List<ProviderDtos.ServicePosture> servicePostures() {
        return jdbc.query("""
                SELECT service.service_key,
                       service.display_name,
                       service.criticality,
                       COUNT(instance.tenant_service_instance_id) AS total_instances,
                       COUNT(*) FILTER (WHERE instance.lifecycle_state = 'READY') AS healthy_instances,
                       COUNT(*) FILTER (WHERE instance.lifecycle_state = 'PROVISIONING') AS pending_instances,
                       COUNT(*) FILTER (WHERE instance.lifecycle_state = 'DEGRADED') AS degraded_instances,
                       COUNT(*) FILTER (WHERE instance.lifecycle_state = 'FAILED') AS failed_instances,
                       COUNT(DISTINCT instance.provider_tenant_id) FILTER (
                           WHERE instance.lifecycle_state IN ('DEGRADED', 'FAILED')) AS impacted_tenants,
                       MAX(instance.last_reconciled_at) AS last_reconciled_at
                  FROM prv_service_catalog service
                  LEFT JOIN prv_tenant_service_instances instance
                    ON instance.service_key = service.service_key
                   AND instance.lifecycle_state <> 'RETIRED'
                 WHERE service.lifecycle_state = 'ACTIVE'
                 GROUP BY service.service_key, service.display_name,
                          service.criticality, service.provisioning_order
                 ORDER BY service.provisioning_order
                """, this::servicePosture);
    }

    public List<ProviderDtos.CellPosture> cellPostures() {
        return jdbc.query("""
                SELECT cell.deployment_cell_id,
                       cell.cell_key,
                       cell.display_name,
                       cell.region_key,
                       cell.lifecycle_state,
                       cell.placement_capacity,
                       COUNT(DISTINCT instance.provider_tenant_id) AS tenant_count,
                       COUNT(instance.tenant_service_instance_id) AS service_instances,
                       COUNT(*) FILTER (WHERE instance.lifecycle_state = 'READY') AS healthy_instances,
                       ROUND(
                           COUNT(DISTINCT instance.provider_tenant_id)::numeric * 100
                           / cell.placement_capacity, 2) AS saturation_pct,
                       CASE
                           WHEN COUNT(*) FILTER (WHERE instance.lifecycle_state = 'FAILED') > 0 THEN 'CRITICAL'
                           WHEN COUNT(*) FILTER (WHERE instance.lifecycle_state = 'DEGRADED') > 0
                                OR COUNT(DISTINCT instance.provider_tenant_id)::numeric * 100
                                   / cell.placement_capacity >= cell.warning_threshold_pct THEN 'ATTENTION'
                           ELSE 'HEALTHY'
                       END AS health_state
                  FROM prv_deployment_cells cell
                  LEFT JOIN prv_tenant_service_instances instance
                    ON instance.deployment_cell_id = cell.deployment_cell_id
                   AND instance.lifecycle_state <> 'RETIRED'
                 WHERE cell.lifecycle_state <> 'RETIRED'
                 GROUP BY cell.deployment_cell_id
                 ORDER BY cell.region_key, cell.cell_key
                """, this::cellPosture);
    }

    public List<ProviderDtos.OperationApprovalSummary> operationApprovals(String state) {
        expireApprovals();
        String clause = state == null || state.isBlank() ? "" : " WHERE approval.lifecycle_state = ?";
        Object[] arguments = clause.isEmpty() ? new Object[0] : new Object[]{state};
        return jdbc.query("""
                SELECT approval.operation_approval_id,
                       approval.operation_id,
                       operation.provider_tenant_id,
                       tenant.display_name AS tenant_name,
                       operation.operation_type,
                       operation.risk_tier,
                       approval.gate_key,
                       approval.gate_order,
                       approval.lifecycle_state,
                       approval.required_role_code,
                       approval.separation_of_duties,
                       approval.requested_by,
                       requester.display_name AS requested_by_name,
                       approval.decided_by,
                       decider.display_name AS decided_by_name,
                       approval.request_reason,
                       approval.decision_reason,
                       approval.requested_at,
                       approval.decided_at,
                       approval.expires_at,
                       approval.version
                  FROM prv_operation_approvals approval
                  JOIN prv_operations operation ON operation.operation_id = approval.operation_id
                  LEFT JOIN prv_tenants tenant ON tenant.provider_tenant_id = operation.provider_tenant_id
                  JOIN prv_operators requester ON requester.provider_operator_id = approval.requested_by
                  LEFT JOIN prv_operators decider ON decider.provider_operator_id = approval.decided_by
                """ + clause + " ORDER BY approval.requested_at DESC LIMIT 200",
                (RowMapper<ProviderDtos.OperationApprovalSummary>) this::operationApproval, arguments);
    }

    public void ensureOperationApproval(ProviderOperation operation) {
        if (!"L3".equals(operation.getRiskTier())) return;
        jdbc.update("""
                INSERT INTO prv_operation_approvals (
                    operation_id, gate_key, lifecycle_state, required_role_code,
                    separation_of_duties, requested_by, request_reason)
                VALUES (?, 'RISK_REVIEW', 'PENDING', 'PROVIDER_ADMIN', TRUE, ?, ?)
                ON CONFLICT (operation_id, gate_key) DO NOTHING
                """, operation.getOperationId(), operation.getRequestedBy(), operation.getJustification());
    }

    public Optional<ApprovalRecord> approval(UUID approvalId) {
        expireApprovals();
        return jdbc.query("""
                SELECT operation_approval_id, operation_id, lifecycle_state,
                       required_role_code, separation_of_duties, requested_by,
                       expires_at, version
                  FROM prv_operation_approvals
                 WHERE operation_approval_id = ?
                """, (result, ignored) -> new ApprovalRecord(
                        result.getObject("operation_approval_id", UUID.class),
                        result.getObject("operation_id", UUID.class),
                        result.getString("lifecycle_state"),
                        result.getString("required_role_code"),
                        result.getBoolean("separation_of_duties"),
                        result.getLong("requested_by"),
                        instant(result, "expires_at"),
                        result.getLong("version")), approvalId).stream().findFirst();
    }

    public boolean operationApproved(UUID operationId) {
        expireApprovals();
        long required = count("SELECT COUNT(*) FROM prv_operation_approvals WHERE operation_id = ?", operationId);
        if (required == 0) return true;
        long approved = count("""
                SELECT COUNT(*) FROM prv_operation_approvals
                 WHERE operation_id = ? AND lifecycle_state = 'APPROVED'
                """, operationId);
        return approved == required;
    }

    public boolean decideApproval(
            UUID approvalId,
            String decision,
            String reason,
            Long operatorId,
            long version) {
        return jdbc.update("""
                UPDATE prv_operation_approvals
                   SET lifecycle_state = ?,
                       decision_reason = ?,
                       decided_by = ?,
                       decided_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE operation_approval_id = ?
                   AND lifecycle_state = 'PENDING'
                   AND expires_at > CURRENT_TIMESTAMP
                   AND version = ?
                """, decision, reason, operatorId, approvalId, version) == 1;
    }

    public List<ProviderDtos.ServiceIncidentSummary> incidents(int limit) {
        int safeLimit = Math.min(200, Math.max(1, limit));
        String sql = """
                SELECT incident.service_incident_id,
                       incident.incident_key,
                       incident.title,
                       incident.severity,
                       incident.lifecycle_state,
                       incident.impact_scope,
                       incident.service_key,
                       incident.region_key,
                       incident.deployment_cell_id,
                       incident.provider_tenant_id,
                       tenant.display_name AS tenant_name,
                       incident.customer_impact,
                       incident.public_summary,
                       owner.display_name AS owner_name,
                       incident.detected_at,
                       incident.started_at,
                       incident.resolved_at,
                       incident.version
                  FROM prv_service_incidents incident
                  LEFT JOIN prv_tenants tenant ON tenant.provider_tenant_id = incident.provider_tenant_id
                  LEFT JOIN prv_operators owner ON owner.provider_operator_id = incident.owner_operator_id
                 ORDER BY CASE incident.lifecycle_state
                              WHEN 'INVESTIGATING' THEN 0
                              WHEN 'IDENTIFIED' THEN 1
                              WHEN 'MONITORING' THEN 2
                              WHEN 'RESOLVED' THEN 3
                              ELSE 4
                          END,
                          CASE incident.severity
                              WHEN 'SEV1' THEN 0 WHEN 'SEV2' THEN 1
                              WHEN 'SEV3' THEN 2 ELSE 3
                          END,
                          incident.detected_at DESC
                 LIMIT %d
                """.formatted(safeLimit);
        return jdbc.query(sql, (RowMapper<ProviderDtos.ServiceIncidentSummary>) this::incident);
    }

    public Optional<IncidentRecord> incident(UUID incidentId) {
        return jdbc.query("""
                SELECT service_incident_id, lifecycle_state, version
                  FROM prv_service_incidents
                 WHERE service_incident_id = ?
                """, (result, ignored) -> new IncidentRecord(
                        result.getObject("service_incident_id", UUID.class),
                        result.getString("lifecycle_state"),
                        result.getLong("version")), incidentId).stream().findFirst();
    }

    public UUID createIncident(
            ProviderDtos.CreateIncidentRequest request,
            Long operatorId,
            String correlationId) {
        Long sequence = jdbc.queryForObject("SELECT nextval('prv_incident_number_seq')", Long.class);
        String incidentKey = "INC-" + INCIDENT_DATE.format(Instant.now()) + "-"
                + String.format(Locale.ROOT, "%05d", sequence == null ? 0 : sequence);
        UUID incidentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_service_incidents (
                    service_incident_id, incident_key, title, severity,
                    impact_scope, service_key, region_key, deployment_cell_id,
                    provider_tenant_id, customer_impact, public_summary,
                    owner_operator_id, correlation_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, incidentId, incidentKey, request.title().trim(), request.severity(),
                request.impactScope(), nullable(request.serviceKey()), nullable(request.regionKey()),
                request.deploymentCellId(), request.tenantId(), request.customerImpact().trim(),
                nullable(request.publicSummary()), operatorId, nullable(correlationId), operatorId, operatorId);
        addIncidentImpact(incidentId, request);
        addIncidentUpdate(incidentId, "INVESTIGATING", request.initialUpdate(), "INTERNAL", operatorId);
        return incidentId;
    }

    public boolean updateIncident(
            UUID incidentId,
            String state,
            String message,
            String visibility,
            Long operatorId,
            long version) {
        int changed = jdbc.update("""
                UPDATE prv_service_incidents
                   SET lifecycle_state = ?,
                       resolved_at = CASE
                           WHEN ? IN ('RESOLVED', 'CLOSED') THEN COALESCE(resolved_at, CURRENT_TIMESTAMP)
                           ELSE NULL
                       END,
                       closed_at = CASE WHEN ? = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE service_incident_id = ?
                   AND lifecycle_state <> 'CLOSED'
                   AND version = ?
                """, state, state, state, operatorId, incidentId, version);
        if (changed == 1) addIncidentUpdate(incidentId, state, message, visibility, operatorId);
        return changed == 1;
    }

    public SupportPolicy supportPolicy(Set<String> scopes) {
        if (scopes.isEmpty()) return new SupportPolicy("L1", false, 0);
        String placeholders = String.join(",", scopes.stream().map(ignored -> "?").toList());
        Object[] arguments = scopes.toArray();
        return jdbc.query("""
                SELECT CASE MAX(CASE risk_tier WHEN 'L3' THEN 3 WHEN 'L2' THEN 2 ELSE 1 END)
                           WHEN 3 THEN 'L3' WHEN 2 THEN 'L2' ELSE 'L1'
                       END AS risk_tier,
                       BOOL_OR(requires_customer_approval) AS requires_customer_approval,
                       COUNT(*) AS matched_scopes
                  FROM prv_support_scope_catalog
                 WHERE lifecycle_state = 'ACTIVE'
                   AND scope_code IN (""" + placeholders + ")",
                (result, ignored) -> new SupportPolicy(
                        result.getString("risk_tier"),
                        result.getBoolean("requires_customer_approval"),
                        result.getInt("matched_scopes")), arguments)
                .stream().findFirst().orElse(new SupportPolicy("L1", false, 0));
    }

    public List<ProviderDtos.SupportScopeSummary> supportScopes() {
        return jdbc.query("""
                SELECT scope_code, display_name, risk_tier,
                       requires_customer_approval, lifecycle_state
                  FROM prv_support_scope_catalog
                 WHERE lifecycle_state = 'ACTIVE'
                 ORDER BY CASE risk_tier WHEN 'L1' THEN 1 WHEN 'L2' THEN 2 ELSE 3 END,
                          scope_code
                """, (result, ignored) -> new ProviderDtos.SupportScopeSummary(
                result.getString("scope_code"), result.getString("display_name"),
                result.getString("risk_tier"), result.getBoolean("requires_customer_approval"),
                result.getString("lifecycle_state")));
    }

    private List<ProviderDtos.ActionItem> actionItems() {
        List<ProviderDtos.ActionItem> items = new ArrayList<>();
        items.addAll(jdbc.query("""
                SELECT 'operation:' || operation.operation_id AS item_id,
                       'CHANGE' AS category,
                       CASE WHEN operation.lifecycle_state = 'FAILED' THEN 'CRITICAL' ELSE 'HIGH' END AS severity,
                       operation.operation_type AS title,
                       COALESCE(operation.failure_message, operation.justification) AS detail,
                       operation.provider_tenant_id,
                       operation.operation_id::text AS target_id,
                       operation.created_at,
                       '/provider/operations' AS route
                  FROM prv_operations operation
                 WHERE operation.lifecycle_state IN ('PREVIEWED', 'PARTIAL', 'FAILED')
                 ORDER BY operation.created_at DESC
                 LIMIT 12
                """, this::actionItem));
        items.addAll(jdbc.query("""
                SELECT 'service:' || instance.tenant_service_instance_id AS item_id,
                       'SERVICE_HEALTH' AS category,
                       CASE WHEN instance.lifecycle_state = 'FAILED' THEN 'CRITICAL' ELSE 'HIGH' END AS severity,
                       service.display_name AS title,
                       tenant.display_name || ' / ' || instance.lifecycle_state AS detail,
                       tenant.provider_tenant_id,
                       instance.tenant_service_instance_id::text AS target_id,
                       instance.updated_at AS created_at,
                       '/provider/health' AS route
                  FROM prv_tenant_service_instances instance
                  JOIN prv_service_catalog service ON service.service_key = instance.service_key
                  JOIN prv_tenants tenant ON tenant.provider_tenant_id = instance.provider_tenant_id
                 WHERE instance.lifecycle_state IN ('DEGRADED', 'FAILED')
                 ORDER BY instance.updated_at DESC
                 LIMIT 12
                """, this::actionItem));
        items.addAll(jdbc.query("""
                SELECT 'domain:' || domain.tenant_domain_id AS item_id,
                       'IDENTITY' AS category,
                       CASE WHEN domain.verification_state = 'FAILED' THEN 'HIGH' ELSE 'MEDIUM' END AS severity,
                       domain.domain_name AS title,
                       domain.verification_state AS detail,
                       tenant.provider_tenant_id,
                       domain.tenant_domain_id::text AS target_id,
                       domain.created_at,
                       '/provider/tenants/' || tenant.provider_tenant_id AS route
                  FROM prv_tenant_domains domain
                  JOIN prv_tenants tenant ON tenant.provider_tenant_id = domain.provider_tenant_id
                 WHERE domain.verification_state = 'FAILED'
                    OR (domain.verification_state = 'PENDING'
                        AND domain.created_at < CURRENT_TIMESTAMP - INTERVAL '24 hours')
                 ORDER BY domain.created_at
                 LIMIT 12
                """, this::actionItem));
        items.addAll(jdbc.query("""
                SELECT 'subscription:' || subscription.organization_subscription_id AS item_id,
                       'COMMERCIAL' AS category,
                       CASE WHEN subscription.ends_at < CURRENT_TIMESTAMP + INTERVAL '30 days'
                            THEN 'HIGH' ELSE 'MEDIUM' END AS severity,
                       organization.display_name AS title,
                       plan.display_name || ' renewal' AS detail,
                       NULL::uuid AS provider_tenant_id,
                       subscription.organization_subscription_id::text AS target_id,
                       subscription.ends_at AS created_at,
                       '/provider/commercial' AS route
                  FROM prv_organization_subscriptions subscription
                  JOIN prv_organizations organization ON organization.organization_id = subscription.organization_id
                  JOIN prv_service_plans plan ON plan.service_plan_id = subscription.service_plan_id
                 WHERE subscription.lifecycle_state IN ('TRIAL', 'ACTIVE')
                   AND subscription.ends_at BETWEEN CURRENT_TIMESTAMP AND CURRENT_TIMESTAMP + INTERVAL '90 days'
                 ORDER BY subscription.ends_at
                 LIMIT 12
                """, this::actionItem));
        return items.stream()
                .sorted(Comparator
                        .comparingInt((ProviderDtos.ActionItem item) -> severityOrder(item.severity()))
                        .thenComparing(ProviderDtos.ActionItem::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(12)
                .toList();
    }

    private List<ProviderDtos.RecentActivity> recentActivity() {
        return jdbc.query("""
                SELECT audit.audit_event_id,
                       audit.action,
                       audit.event_category,
                       audit.outcome,
                       operator.display_name AS operator_name,
                       tenant.tenant_key,
                       audit.target_type,
                       audit.target_id,
                       audit.occurred_at
                  FROM prv_audit_events audit
                  LEFT JOIN prv_operators operator ON operator.provider_operator_id = audit.provider_operator_id
                  LEFT JOIN prv_tenants tenant ON tenant.provider_tenant_id = audit.provider_tenant_id
                 ORDER BY audit.occurred_at DESC
                 LIMIT 10
                """, (result, ignored) -> new ProviderDtos.RecentActivity(
                        result.getObject("audit_event_id", UUID.class),
                        result.getString("action"),
                        result.getString("event_category"),
                        result.getString("outcome"),
                        result.getString("operator_name"),
                        result.getString("tenant_key"),
                        result.getString("target_type"),
                        result.getString("target_id"),
                        instant(result, "occurred_at")));
    }

    private List<ProviderDtos.ServicePlanPortfolio> servicePlanPortfolio() {
        return jdbc.query("""
                SELECT plan.plan_key,
                       plan.plan_version,
                       plan.display_name,
                       plan.service_tier,
                       plan.lifecycle_state,
                       COUNT(DISTINCT subscription.organization_id) FILTER (
                           WHERE subscription.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')) AS organizations,
                       COUNT(DISTINCT tenant.provider_tenant_id) FILTER (
                           WHERE subscription.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')) AS tenants
                  FROM prv_service_plans plan
                  LEFT JOIN prv_organization_subscriptions subscription
                    ON subscription.service_plan_id = plan.service_plan_id
                  LEFT JOIN prv_tenants tenant ON tenant.organization_id = subscription.organization_id
                 GROUP BY plan.service_plan_id
                 ORDER BY plan.service_tier, plan.plan_version DESC
                """, (result, ignored) -> new ProviderDtos.ServicePlanPortfolio(
                        result.getString("plan_key"), result.getInt("plan_version"),
                        result.getString("display_name"), result.getString("service_tier"),
                        result.getString("lifecycle_state"), result.getLong("organizations"),
                        result.getLong("tenants")));
    }

    private List<ProviderDtos.SubscriptionPortfolio> subscriptionPortfolio() {
        return jdbc.query("""
                SELECT subscription.organization_subscription_id,
                       organization.organization_id,
                       organization.organization_key,
                       organization.display_name AS organization_name,
                       plan.plan_key,
                       plan.display_name AS plan_name,
                       plan.service_tier,
                       subscription.lifecycle_state,
                       subscription.starts_at,
                       subscription.ends_at,
                       subscription.contract_reference,
                       COUNT(DISTINCT tenant.provider_tenant_id) AS tenants,
                       COUNT(DISTINCT entitlement.tenant_entitlement_id) FILTER (
                           WHERE entitlement.lifecycle_state = 'ACTIVE') AS active_entitlements
                  FROM prv_organization_subscriptions subscription
                  JOIN prv_organizations organization ON organization.organization_id = subscription.organization_id
                  JOIN prv_service_plans plan ON plan.service_plan_id = subscription.service_plan_id
                  LEFT JOIN prv_tenants tenant ON tenant.organization_id = organization.organization_id
                  LEFT JOIN prv_tenant_entitlements entitlement
                    ON entitlement.provider_tenant_id = tenant.provider_tenant_id
                 GROUP BY subscription.organization_subscription_id,
                          organization.organization_id, plan.service_plan_id
                 ORDER BY CASE subscription.lifecycle_state
                              WHEN 'TRIAL' THEN 0 WHEN 'ACTIVE' THEN 1
                              WHEN 'SUSPENDED' THEN 2 ELSE 3
                          END,
                          organization.display_name
                """, (result, ignored) -> new ProviderDtos.SubscriptionPortfolio(
                        result.getObject("organization_subscription_id", UUID.class),
                        result.getObject("organization_id", UUID.class),
                        result.getString("organization_key"),
                        result.getString("organization_name"),
                        result.getString("plan_key"),
                        result.getString("plan_name"),
                        result.getString("service_tier"),
                        result.getString("lifecycle_state"),
                        instant(result, "starts_at"),
                        instant(result, "ends_at"),
                        result.getString("contract_reference"),
                        result.getLong("tenants"),
                        result.getLong("active_entitlements")));
    }

    private List<ProviderDtos.EntitlementAdoption> entitlementAdoption() {
        return jdbc.query("""
                SELECT catalog.entitlement_id,
                       catalog.entitlement_key,
                       catalog.name,
                       catalog.entitlement_type,
                       COUNT(DISTINCT assignment.provider_tenant_id) FILTER (
                           WHERE assignment.lifecycle_state = 'ACTIVE') AS assigned_tenants,
                       (SELECT COUNT(*) FROM prv_tenants WHERE lifecycle_state <> 'RETIRED') AS eligible_tenants
                  FROM prv_entitlement_catalog catalog
                  LEFT JOIN prv_tenant_entitlements assignment
                    ON assignment.entitlement_id = catalog.entitlement_id
                 WHERE catalog.lifecycle_state = 'ACTIVE'
                 GROUP BY catalog.entitlement_id
                 ORDER BY assigned_tenants DESC, catalog.entitlement_key
                """, (result, ignored) -> new ProviderDtos.EntitlementAdoption(
                        result.getLong("entitlement_id"), result.getString("entitlement_key"),
                        result.getString("name"), result.getString("entitlement_type"),
                        result.getLong("assigned_tenants"), result.getLong("eligible_tenants")));
    }

    private ProviderDtos.ServicePosture servicePosture(ResultSet result, int ignored) throws SQLException {
        return new ProviderDtos.ServicePosture(
                result.getString("service_key"), result.getString("display_name"),
                result.getString("criticality"), result.getLong("total_instances"),
                result.getLong("healthy_instances"), result.getLong("pending_instances"),
                result.getLong("degraded_instances"), result.getLong("failed_instances"),
                result.getLong("impacted_tenants"), instant(result, "last_reconciled_at"));
    }

    private ProviderDtos.CellPosture cellPosture(ResultSet result, int ignored) throws SQLException {
        return new ProviderDtos.CellPosture(
                result.getObject("deployment_cell_id", UUID.class), result.getString("cell_key"),
                result.getString("display_name"), result.getString("region_key"),
                result.getString("lifecycle_state"), result.getInt("placement_capacity"),
                result.getLong("tenant_count"), result.getLong("service_instances"),
                result.getLong("healthy_instances"), result.getDouble("saturation_pct"),
                result.getString("health_state"));
    }

    private ProviderDtos.ActionItem actionItem(ResultSet result, int ignored) throws SQLException {
        return new ProviderDtos.ActionItem(
                result.getString("item_id"), result.getString("category"),
                result.getString("severity"), result.getString("title"),
                result.getString("detail"), result.getObject("provider_tenant_id", UUID.class),
                result.getString("target_id"), instant(result, "created_at"), result.getString("route"));
    }

    private ProviderDtos.OperationApprovalSummary operationApproval(ResultSet result, int ignored)
            throws SQLException {
        return new ProviderDtos.OperationApprovalSummary(
                result.getObject("operation_approval_id", UUID.class),
                result.getObject("operation_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("tenant_name"), result.getString("operation_type"),
                result.getString("risk_tier"), result.getString("gate_key"),
                result.getInt("gate_order"), result.getString("lifecycle_state"),
                result.getString("required_role_code"), result.getBoolean("separation_of_duties"),
                result.getLong("requested_by"), result.getString("requested_by_name"),
                nullableLong(result, "decided_by"), result.getString("decided_by_name"),
                result.getString("request_reason"), result.getString("decision_reason"),
                instant(result, "requested_at"), instant(result, "decided_at"),
                instant(result, "expires_at"), result.getLong("version"));
    }

    private ProviderDtos.ServiceIncidentSummary incident(ResultSet result, int ignored) throws SQLException {
        UUID incidentId = result.getObject("service_incident_id", UUID.class);
        return new ProviderDtos.ServiceIncidentSummary(
                incidentId, result.getString("incident_key"), result.getString("title"),
                result.getString("severity"), result.getString("lifecycle_state"),
                result.getString("impact_scope"), result.getString("service_key"),
                result.getString("region_key"), result.getObject("deployment_cell_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class), result.getString("tenant_name"),
                result.getString("customer_impact"), result.getString("public_summary"),
                result.getString("owner_name"), instant(result, "detected_at"),
                instant(result, "started_at"), instant(result, "resolved_at"),
                result.getLong("version"), incidentUpdates(incidentId));
    }

    private List<ProviderDtos.IncidentUpdateSummary> incidentUpdates(UUID incidentId) {
        return jdbc.query("""
                SELECT incident_update.service_incident_update_id,
                       incident_update.lifecycle_state,
                       incident_update.message,
                       incident_update.visibility,
                       operator.display_name AS operator_name,
                       incident_update.created_at
                  FROM prv_service_incident_updates incident_update
                  LEFT JOIN prv_operators operator
                    ON operator.provider_operator_id = incident_update.created_by
                 WHERE incident_update.service_incident_id = ?
                 ORDER BY incident_update.created_at DESC
                """, (result, ignored) -> new ProviderDtos.IncidentUpdateSummary(
                        result.getObject("service_incident_update_id", UUID.class),
                        result.getString("lifecycle_state"), result.getString("message"),
                        result.getString("visibility"), result.getString("operator_name"),
                        instant(result, "created_at")), incidentId);
    }

    private void addIncidentUpdate(
            UUID incidentId,
            String state,
            String message,
            String visibility,
            Long operatorId) {
        jdbc.update("""
                INSERT INTO prv_service_incident_updates (
                    service_incident_id, lifecycle_state, message, visibility, created_by)
                VALUES (?, ?, ?, ?, ?)
                """, incidentId, state, message.trim(), visibility, operatorId);
    }

    private void addIncidentImpact(UUID incidentId, ProviderDtos.CreateIncidentRequest request) {
        if ("GLOBAL".equals(request.impactScope())) return;
        jdbc.update("""
                INSERT INTO prv_service_incident_impacts (
                    service_incident_id, target_type, service_key, region_key,
                    deployment_cell_id, provider_tenant_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, incidentId, request.impactScope(), nullable(request.serviceKey()),
                nullable(request.regionKey()), request.deploymentCellId(), request.tenantId());
    }

    private void expireApprovals() {
        jdbc.update("""
                UPDATE prv_operation_approvals
                   SET lifecycle_state = 'EXPIRED', version = version + 1
                 WHERE lifecycle_state = 'PENDING' AND expires_at <= CURRENT_TIMESTAMP
                """);
    }

    private List<ProviderDtos.Metric> metrics(String sql) {
        return jdbc.query(sql, (result, ignored) ->
                new ProviderDtos.Metric(result.getString("key"), result.getLong("total")));
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isResolved(String state) {
        return "RESOLVED".equals(state) || "CLOSED".equals(state);
    }

    private int severityOrder(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            default -> 3;
        };
    }

    public record ApprovalRecord(
            UUID approvalId,
            UUID operationId,
            String lifecycleState,
            String requiredRoleCode,
            boolean separationOfDuties,
            Long requestedBy,
            Instant expiresAt,
            long version) {
    }

    public record IncidentRecord(UUID incidentId, String lifecycleState, long version) {
    }

    public record SupportPolicy(
            String riskTier,
            boolean requiresCustomerApproval,
            int matchedScopes) {
    }
}
