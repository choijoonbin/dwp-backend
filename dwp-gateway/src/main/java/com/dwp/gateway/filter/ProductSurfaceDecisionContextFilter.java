package com.dwp.gateway.filter;

import com.dwp.gateway.audit.GatewayDenialAuditSink;
import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextAggregationService;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.dwp.gateway.productsurface.Per004GatewayRoutes;
import com.dwp.gateway.productsurface.Per004DecisionContextForwarder;
import com.dwp.gateway.productsurface.Sys006GatewayRoutes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.dwp.gateway.filter.ProductSurfaceScopeSelection.ScopeSelection;
import static com.dwp.gateway.filter.ProductSurfaceScopeSelection.scopeSelection;
import static com.dwp.gateway.filter.ProductSurfaceScopeSelection.withoutScopeSelection;

/** Product-agnostic generated route PEP that emits only server-owned decision evidence. */
@Component
public final class ProductSurfaceDecisionContextFilter implements GlobalFilter, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ProductSurfaceDecisionContextFilter.class);

    public static final String ROUTE_HEADER = "X-DWP-Route-Contract-Key";
    public static final String CURRENT_REVISION_HEADER = "X-DWP-Current-Decision-Revision";
    public static final String CURRENT_REVALIDATE_AT_HEADER = "X-DWP-Current-Revalidate-At";
    public static final String EXPECTED_REVISION_HEADER = "X-DWP-Expected-Decision-Revision";
    public static final String RESPONSE_REVISION_HEADER = "X-DWP-Decision-Revision";
    public static final String CONTEXT_HEADER = "X-DWP-Context-Key";
    public static final String SCOPE_HEADER = "X-DWP-Context-Scope-Key";
    public static final String ACTIVE_ACCESS_MODE_HEADER = "X-DWP-Active-Access-Mode";
    public static final String SUBJECT_PRINCIPAL_HEADER =
            "X-DWP-Subject-Principal-Public-ID";
    public static final String FIELD_POLICY_REVISION_HEADER =
            "X-DWP-Field-Policy-Revision";
    public static final String FIELD_POLICY_KEY_HEADER = "X-DWP-Field-Policy-Key";
    public static final String AUTHORIZATION_REVISION_HEADER =
            "X-DWP-Authorization-Revision";
    public static final String POPULATION_KIND_HEADER = "X-DWP-Population-Kind";
    public static final String PURPOSE_HEADER = "X-Purpose-Code";
    public static final String EXPECTED_OBJECT_VERSION_HEADER =
            "X-DWP-Expected-Object-Version";
    private static final String IF_MATCH_HEADER = "If-Match";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final Pattern SYS006_IDEMPOTENCY_KEY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:-]{0,119}");
    private static final long SYS006_MAX_SAFE_OBJECT_REVISION = 9_007_199_254_740_991L;
    private static final String PAY_GROUP_PUBLIC_ID_HEADER = "X-DWP-Pay-Group-Public-Id";
    private static final Pattern COUNTRY_PACK_ACTIVATION_PATH = Pattern.compile(
            "^/api/payroll/v1/country-packs/"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12}/activate$");
    public static final String VERIFIED_LEGAL_ENTITY_HEADER =
            "X-DWP-Verified-Legal-Entity-Public-ID";
    public static final String LEGAL_ENTITY_OWNER_EVIDENCE_HEADER =
            "X-DWP-Legal-Entity-Owner-Evidence";
    public static final String MASKED_FIELD_GROUPS_HEADER =
            "X-DWP-Masked-Field-Groups";
    public static final String OMITTED_FIELD_GROUPS_HEADER =
            "X-DWP-Omitted-Field-Groups";
    public static final String SCOPE_QUERY_PARAMETER = ProductSurfaceScopeSelection.SCOPE_QUERY_PARAMETER;
    private static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    private static final Set<String> HOME_RUNTIME_ROUTE_KEYS = Set.of(
            "route.workplace.work.home-read-model.data",
            "route.workplace.work.home-shadow-receipt.action",
            "route.workplace.work.home-widget-action-execute.action");
    /**
     * Default-off tenant-feature routes authorized by the sealed HRIS activation decisions,
     * including the SYS-006 automation control plane. This closed set is deliberately code-owned:
     * rollout state, tenant identity, environment or operator configuration cannot bypass direct
     * Auth evaluation.
     */
    private static final Set<String> TENANT_FEATURE_GOVERNED_ROUTE_KEYS = Set.of(
            "route.hcm.operations.organization-positions.page",
            "route.hcm.operations.organization-graph.data",
            "route.hcm.configuration.settings-operations.page",
            "route.hcm.operations.exception.data",
            "route.hcm.operations.exception-detail.data",
            "route.hcm.operations.payroll-population.page",
            "route.hcm.operations.payroll-support.data",
            "route.hcm.operations.payroll-run.action",
            "route.hcm.operations.time-command-center.page",
            "route.hcm.operations.time-daily-close.page",
            "route.hcm.operations.time-monthly-close.page",
            "route.hcm.operations.time-close-validate.action",
            "route.hcm.operations.time-close.action",
            "route.hcm.operations.time-reopen.action",
            "route.hcm.operations.time-payroll-handoff-create.action",
            "route.hcm.operations.time-payroll-handoff.data",
            "route.hcm.personal.requests.page",
            "route.hcm.personal.requests.data",
            "route.hcm.operations.certificates-documents.page",
            "route.hcm.operations.certificates-documents.data",
            "route.hcm.operations.certificate-issue.action",
            "route.hcm.operations.contracts-compensation-basis.page",
            "route.hcm.operations.contracts-compensation-basis.data",
            "route.hcm.operations.employment-contract-issue.action",
            "route.hcm.configuration.statutory.page",
            "route.hcm.configuration.statutory.data",
            "route.hcm.operations.pay-tax-social-insurance.page",
            "route.hcm.operations.pay-tax-social-insurance.data",
            "route.hcm.operations.country-pack-activate.action",
            "route.hcm.operations.performance-forms-scales.page",
            "route.hcm.operations.performance-forms-scales.data",
            "route.hcm.configuration.settings-performance.page",
            "route.hcm.configuration.settings-performance.data",
            "route.hcm.configuration.automation-catalog.data",
            "route.hcm.configuration.automation-definition-create.action",
            "route.hcm.configuration.automation-run-queue.action",
            "route.hcm.configuration.automation-run-detail.data",
            "route.hcm.configuration.automation-run-retry.action",
            "route.hcm.configuration.automation-version-create.action",
            "route.hcm.configuration.automation-version-validate.action",
            "route.hcm.configuration.automation-version-submit.action",
            "route.hcm.configuration.automation-version-approve.action",
            "route.hcm.configuration.automation-version-activate.action",
            "route.hcm.configuration.automation-schedule-create.action",
            "route.hcm.configuration.automation-schedule-submit.action",
            "route.hcm.configuration.automation-schedule-approve.action",
            "route.hcm.configuration.automation-schedule-activate.action",
            "route.hcm.configuration.automation-schedule-suspend.action",
            "route.hcm.configuration.automation-run-cancel.action");
    private static final Set<String> HRM007_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.team.people-changes.page",
            "route.hcm.team.people-changes.data",
            "route.hcm.operations.people-appointments.page",
            "route.hcm.operations.people-appointments.data",
            "route.hcm.configuration.people-employment.page",
            "route.hcm.configuration.people-employment.data");
    private static final Set<String> HRM007_CONFIGURATION_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.configuration.people-employment.page",
            "route.hcm.configuration.people-employment.data");
    private static final Set<String> HRM009_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.personal.requests.page",
            "route.hcm.personal.requests.data",
            "route.hcm.operations.certificates-documents.page",
            "route.hcm.operations.certificates-documents.data");
    private static final Set<String> HRM009_PERSONAL_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.personal.requests.page",
            "route.hcm.personal.requests.data");
    private static final Set<String> HRM009_OPERATIONS_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.operations.certificates-documents.page",
            "route.hcm.operations.certificates-documents.data");
    private static final String HRM009_ACTION_ROUTE_KEY =
            "route.hcm.operations.certificate-issue.action";
    private static final Set<String> HRM009_OPERATIONS_POPULATION_KINDS = Set.of(
            "LEGAL_ENTITY", "NAMED_POPULATION", "ORG_TREE", "ORG_UNIT");
    private static final Set<String> HRM011_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.operations.contracts-compensation-basis.page",
            "route.hcm.operations.contracts-compensation-basis.data");
    private static final String HRM011_ACTION_ROUTE_KEY =
            "route.hcm.operations.employment-contract-issue.action";
    private static final Set<String> HRM011_POPULATION_KINDS = Set.of(
            "LEGAL_ENTITY", "NAMED_POPULATION", "ORG_TREE", "ORG_UNIT");
    private static final Set<String> PAY003_CONFIGURATION_ROUTE_KEYS = Set.of(
            "route.hcm.configuration.statutory.page",
            "route.hcm.configuration.statutory.data");
    private static final Set<String> PAY003_RESULT_ROUTE_KEYS = Set.of(
            "route.hcm.operations.pay-tax-social-insurance.page",
            "route.hcm.operations.pay-tax-social-insurance.data");
    private static final String PAY003_ACTION_ROUTE_KEY =
            "route.hcm.operations.country-pack-activate.action";
    private static final Set<String> TIM_WAVE2_ROUTE_KEYS = Set.of(
            "route.hcm.operations.time-interpretation.page",
            "route.hcm.operations.time-interpretation-run.action",
            "route.hcm.operations.time-interpretation-trace.data");
    private static final Set<String> PER_TIM_WAVE5_POPULATION_ROUTE_KEYS = Set.of(
            "route.hcm.performance.assigned-review.data",
            "route.hcm.performance.assigned-review-draft-update.action",
            "route.hcm.performance.assigned-review-submit.action",
            "route.hcm.operations.performance-multirater-surveys.page",
            "route.hcm.time.timecard.data",
            "route.hcm.time.timecard-correction.action",
            "route.hcm.time.timecard-submit.action",
            "route.hcm.time.timecard-decision.action",
            "route.hcm.time.command-receipt.data");
    private static final Set<String> HCM_AUDIT_READ_ROUTE_KEYS = Set.of(
            "route.hcm.audit.evidence.page",
            "route.hcm.audit.evidence-detail.data");
    private static final Set<String> V49_TARGET_POPULATION_ROUTE_KEYS = Set.of(
            "route.hcm.operations.organization-positions.page",
            "route.hcm.operations.organization-graph.data",
            "route.hcm.operations.payroll-population.page",
            "route.hcm.operations.payroll-support.data",
            "route.hcm.operations.payroll-run.action",
            "route.hcm.operations.exception.data",
            "route.hcm.operations.exception-detail.data",
            "route.hcm.operations.time-command-center.page",
            "route.hcm.operations.time-daily-close.page",
            "route.hcm.operations.time-monthly-close.page",
            "route.hcm.operations.time-close-validate.action",
            "route.hcm.operations.time-close.action",
            "route.hcm.operations.time-reopen.action",
            "route.hcm.operations.time-payroll-handoff-create.action",
            "route.hcm.operations.time-payroll-handoff.data");
    private static final Set<String> PAYROLL_OWNER_POPULATION_ROUTE_KEYS = Set.of(
            "route.hcm.operations.payroll-run-calculate.action",
            "route.hcm.operations.payroll-run.data",
            "route.hcm.operations.payroll-run-approve.action",
            "route.hcm.operations.payroll-run-finalize.action",
            "route.hcm.operations.payroll-command-receipt.data");
    private static final Set<String> CONTROLLED_TRANSFER_OWNER_ROUTE_KEYS = Set.of(
            "route.hcm.management.controlled-transfer.data",
            "route.hcm.management.controlled-transfer-create.action");
    private static final Set<String> NAVIGATION_V2_TENANT_ROUTE_KEYS = Set.of(
            "route.hcm.shell.navigation-aggregate.data",
            "route.hcm.shell.navigation-deep-link-decision.data");
    private static final Set<String> NAVIGATION_V2_LEGAL_ENTITY_ROUTE_KEYS = Set.of(
            "route.hcm.shell.navigation-aggregate-legal-entity.data",
            "route.hcm.shell.navigation-deep-link-decision-legal-entity.data");
    private static final Set<String> CONTROLLED_TRANSFER_POPULATION_KINDS = Set.of(
            "CONNECTOR_SET", "LEGAL_ENTITY");
    private static final Map<String, String> HRM007_FIELD_POLICY_BY_ROUTE = Map.of(
            "route.hcm.team.people-changes.page", "hrm007.iaq-005.fields.v1",
            "route.hcm.team.people-changes.data", "hrm007.iaq-005.fields.v1",
            "route.hcm.operations.people-appointments.page", "hrm007.iaq-007.fields.v1",
            "route.hcm.operations.people-appointments.data", "hrm007.iaq-007.fields.v1",
            "route.hcm.configuration.people-employment.page", "hrm007.iaq-013.fields.v1",
            "route.hcm.configuration.people-employment.data", "hrm007.iaq-013.fields.v1");
    private static final Map<String, String> HRM009_FIELD_POLICY_BY_ROUTE = Map.of(
            "route.hcm.personal.requests.page", "hrm009.iaq-003.fields.v1",
            "route.hcm.personal.requests.data", "hrm009.iaq-003.fields.v1",
            "route.hcm.operations.certificates-documents.page", "hrm009.iaq-010.fields.v1",
            "route.hcm.operations.certificates-documents.data", "hrm009.iaq-010.fields.v1");
    private static final Map<String, String> HRM011_FIELD_POLICY_BY_ROUTE = Map.of(
            "route.hcm.operations.contracts-compensation-basis.page", "HRM-AUTH-007",
            "route.hcm.operations.contracts-compensation-basis.data", "HRM-AUTH-007",
            HRM011_ACTION_ROUTE_KEY, "HRM-AUTH-015");
    private static final Map<String, String> PAY003_FIELD_POLICY_BY_ROUTE = Map.of(
            "route.hcm.configuration.statutory.page", "pay003.iaq-026.fields.v1",
            "route.hcm.configuration.statutory.data", "pay003.iaq-026.fields.v1",
            "route.hcm.operations.pay-tax-social-insurance.page", "pay003.iaq-048.fields.v1",
            "route.hcm.operations.pay-tax-social-insurance.data", "pay003.iaq-048.fields.v1");
    private static final Map<String, String> WAVE2_OWNER_PURPOSE_BY_ROUTE = Map.ofEntries(
            Map.entry("route.hcm.personal.employment-documents.page", "HR_LIFECYCLE"),
            Map.entry("route.hcm.operations.employment-assignments.page", "HR_LIFECYCLE"),
            Map.entry("route.hcm.operations.assignment-event-create.action", "HR_LIFECYCLE"),
            Map.entry("route.hcm.operations.assignment-event-validate.action", "HR_LIFECYCLE"),
            Map.entry("route.hcm.operations.assignment-event-submit.action", "HR_LIFECYCLE"),
            Map.entry("route.hcm.operations.assignment-event-publish.action", "HR_LIFECYCLE"),
            Map.entry("route.hcm.operations.assignment-event-detail.data", "HR_LIFECYCLE"),
            Map.entry("route.hcm.operations.assignment-command-receipt.data", "HR_LIFECYCLE"),
            Map.entry("route.hcm.team.people-changes.page", "HR_LIFECYCLE"),
            Map.entry("route.hcm.team.people-changes.data", "HR_LIFECYCLE"),
            Map.entry("route.hcm.operations.people-appointments.page", "HR_LIFECYCLE"),
            Map.entry("route.hcm.operations.people-appointments.data", "HR_LIFECYCLE"),
            Map.entry("route.hcm.configuration.people-employment.page", "HRIS_CONFIGURATION"),
            Map.entry("route.hcm.configuration.people-employment.data", "HRIS_CONFIGURATION"),
            Map.entry("route.hcm.operations.time-interpretation.page", "TIME_OPERATIONS"),
            Map.entry("route.hcm.operations.time-interpretation-run.action", "TIME_INTERPRETATION"),
            Map.entry("route.hcm.operations.time-interpretation-trace.data", "TIME_INTERPRETATION"),
            Map.entry("route.hcm.configuration.payroll.page", "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-element-version-create.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-formula-version-create.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-formula-validate.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-formula-simulate.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-formula-publish.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-foundation-configurations.data",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-foundation-configuration.data",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-foundation-versions.data",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-foundation-receipt.data",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-foundation-create.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-foundation-update.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-foundation-simulate.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-foundation-publish.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-foundation-reverse.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.payroll-foundation-reconcile.action",
                    "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.operations.payroll-run-calculate.action",
                    "PAYROLL_PROCESSING"),
            Map.entry("route.hcm.operations.payroll-run.data",
                    "PAYROLL_PROCESSING"),
            Map.entry("route.hcm.operations.payroll-run-approve.action",
                    "PAYROLL_PROCESSING"),
            Map.entry("route.hcm.operations.payroll-run-finalize.action",
                    "PAYROLL_PROCESSING"),
            Map.entry("route.hcm.operations.payroll-command-receipt.data",
                    "COMMAND_STATUS"),
            Map.entry("route.hcm.operations.compensation-planning.page",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-cycle-detail.data",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-proposals.data",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-proposal-detail.data",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-cycle-create.action",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-cycle-revise.action",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-budget-allocate.action",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-proposal-upsert.action",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-proposal-submit.action",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-plan-approve.action",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-snapshot-publish.action",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.operations.compensation-cycle-cancel.action",
                    "COMPENSATION_PLANNING"),
            Map.entry("route.hcm.configuration.navigation-policy-workspace.page",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-draft-create.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-draft-update.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-draft-validate.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-draft-submit.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-revision-approve.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-revision-publish.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-revision-restore.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-v2-workspace.data",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-v2-draft-create.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-v2-draft-update.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-v2-draft-validate.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-v2-draft-submit.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-v2-revision-approve.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-v2-revision-publish.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.configuration.navigation-policy-v2-revision-restore.action",
                    "HRIS_SYSTEM_ADMIN"),
            Map.entry("route.hcm.audit.evidence.page", "HRIS_AUDIT_EVIDENCE"),
            Map.entry("route.hcm.audit.evidence-detail.data", "HRIS_AUDIT_EVIDENCE"),
            Map.entry("route.hcm.performance.assigned-review.data", "PERFORMANCE_REVIEW"),
            Map.entry("route.hcm.performance.assigned-review-draft-update.action",
                    "PERFORMANCE_REVIEW"),
            Map.entry("route.hcm.performance.assigned-review-submit.action",
                    "PERFORMANCE_REVIEW"),
            Map.entry("route.hcm.operations.performance-multirater-surveys.page",
                    "PERFORMANCE_OPERATIONS"),
            Map.entry("route.hcm.time.timecard.data", "TIME_TIMECARD"),
            Map.entry("route.hcm.time.timecard-correction.action", "TIME_TIMECARD"),
            Map.entry("route.hcm.time.timecard-submit.action", "TIME_TIMECARD"),
            Map.entry("route.hcm.time.timecard-decision.action", "TIME_TIMECARD"),
            Map.entry("route.hcm.time.command-receipt.data", "COMMAND_STATUS"),
            Map.entry("route.hcm.management.controlled-transfer.data",
                    "HRIS_INTEGRATION_ADMIN"),
            Map.entry("route.hcm.management.controlled-transfer-create.action",
                    "HRIS_INTEGRATION_ADMIN"),
            Map.entry("route.hcm.operations.organization-positions.page",
                    "HR_OPERATIONS"),
            Map.entry("route.hcm.operations.organization-graph.data",
                    "HR_OPERATIONS"),
            Map.entry("route.hcm.operations.payroll-population.page",
                    "PAYROLL_OPERATIONS"),
            Map.entry("route.hcm.operations.payroll-support.data",
                    "PAYROLL_OPERATIONS"),
            Map.entry("route.hcm.operations.payroll-run.action",
                    "PAYROLL_PROCESSING"),
            Map.entry("route.hcm.configuration.settings-operations.page",
                    "HRIS_CONFIGURATION"),
            Map.entry("route.hcm.operations.exception.data",
                    "HRIS_OPERATIONS"),
            Map.entry("route.hcm.operations.exception-detail.data",
                    "HRIS_OPERATIONS"),
            Map.entry("route.hcm.operations.time-command-center.page",
                    "TIME_OPERATIONS"),
            Map.entry("route.hcm.operations.time-daily-close.page",
                    "TIME_CLOSE"),
            Map.entry("route.hcm.operations.time-monthly-close.page",
                    "TIME_CLOSE"),
            Map.entry("route.hcm.operations.time-close-validate.action",
                    "WORK_TIME_PERIOD_CLOSURE"),
            Map.entry("route.hcm.operations.time-close.action",
                    "WORK_TIME_PERIOD_CLOSURE"),
            Map.entry("route.hcm.operations.time-reopen.action",
                    "WORK_TIME_PERIOD_CLOSURE"),
            Map.entry("route.hcm.operations.time-payroll-handoff-create.action",
                    "CLOSED_TIME_PAYROLL_TRANSFER"),
            Map.entry("route.hcm.operations.time-payroll-handoff.data",
                    "CLOSED_TIME_PAYROLL_TRANSFER"),
            Map.entry("route.hcm.personal.requests.page", "EMPLOYEE_SELF_SERVICE"),
            Map.entry("route.hcm.personal.requests.data", "EMPLOYEE_SELF_SERVICE"),
            Map.entry("route.hcm.operations.certificates-documents.page", "HR_OPERATIONS"),
            Map.entry("route.hcm.operations.certificates-documents.data", "HR_OPERATIONS"),
            Map.entry("route.hcm.operations.contracts-compensation-basis.page", "HR_OPERATIONS"),
            Map.entry("route.hcm.operations.contracts-compensation-basis.data", "HR_OPERATIONS"),
            Map.entry(HRM011_ACTION_ROUTE_KEY, "HR_LIFECYCLE"),
            Map.entry("route.hcm.configuration.statutory.page", "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.configuration.statutory.data", "PAYROLL_CONFIGURATION"),
            Map.entry("route.hcm.operations.pay-tax-social-insurance.page", "PAYROLL_OPERATIONS"),
            Map.entry("route.hcm.operations.pay-tax-social-insurance.data", "PAYROLL_OPERATIONS"),
            Map.entry(PAY003_ACTION_ROUTE_KEY, "STATUTORY_CONFIGURATION"));
    private static final List<String> INTERNAL_HEADERS = List.of(
            ROUTE_HEADER, CURRENT_REVISION_HEADER, CURRENT_REVALIDATE_AT_HEADER,
            RESPONSE_REVISION_HEADER, CONTEXT_HEADER, SCOPE_HEADER, ACTIVE_ACCESS_MODE_HEADER,
            SUBJECT_PRINCIPAL_HEADER, FIELD_POLICY_REVISION_HEADER, FIELD_POLICY_KEY_HEADER,
            AUTHORIZATION_REVISION_HEADER, POPULATION_KIND_HEADER,
            PURPOSE_HEADER, VERIFIED_LEGAL_ENTITY_HEADER,
            LEGAL_ENTITY_OWNER_EVIDENCE_HEADER,
            VerifiedIdentityFilter.RESPONSIBILITIES_HEADER,
            VerifiedIdentityFilter.LEGAL_ENTITY_PUBLIC_ID_HEADER,
            PAY_GROUP_PUBLIC_ID_HEADER,
            VerifiedIdentityFilter.TIME_GROUP_PUBLIC_ID_HEADER,
            MASKED_FIELD_GROUPS_HEADER, OMITTED_FIELD_GROUPS_HEADER,
            "X-DWP-Current-Decision-Valid-Until");

    private final ProductSurfaceContextAggregationService aggregationService;
    private final GeneratedProductRouteCatalog routeCatalog;
    private final ObjectMapper objectMapper;
    private final GatewayDenialAuditSink denialAudit;
    private final Per004DecisionContextForwarder per004Forwarder;

    @Autowired
    public ProductSurfaceDecisionContextFilter(
            ProductSurfaceContextAggregationService aggregationService,
            GeneratedProductRouteCatalog routeCatalog,
            ObjectMapper objectMapper,
            GatewayDenialAuditSink denialAudit,
            Per004DecisionContextForwarder per004Forwarder) {
        this.aggregationService = aggregationService;
        this.routeCatalog = routeCatalog;
        this.objectMapper = objectMapper;
        this.denialAudit = denialAudit;
        this.per004Forwarder = per004Forwarder;
    }

    public ProductSurfaceDecisionContextFilter(
            ProductSurfaceContextAggregationService aggregationService,
            GeneratedProductRouteCatalog routeCatalog,
            ObjectMapper objectMapper,
            GatewayDenialAuditSink denialAudit) {
        this(aggregationService, routeCatalog, objectMapper, denialAudit,
                new Per004DecisionContextForwarder(objectMapper));
    }

    /** Test-only constructor retaining isolated PRODUCT authority fixtures. */
    public ProductSurfaceDecisionContextFilter(
            ProductSurfaceContextAggregationService aggregationService,
            GeneratedProductRouteCatalog routeCatalog,
            ObjectMapper objectMapper) {
        this(aggregationService, routeCatalog, objectMapper, GatewayDenialAuditSink.NOOP);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean per004 = Per004GatewayRoutes.isPath(exchange.getRequest().getURI().getPath())
                && exchange.getRequest().getMethod() != HttpMethod.OPTIONS;
        boolean sys006 = Sys006GatewayRoutes.claimsPath(
                exchange.getRequest().getURI().getPath())
                && exchange.getRequest().getMethod() != HttpMethod.OPTIONS;
        if (per004 && !per004Forwarder.validInbound(exchange)) {
            return error(exchange, HttpStatus.BAD_REQUEST, "INVALID_PER004_AUTHORITY_INPUT", null);
        }
        if (sys006 && !Sys006InboundAuthorityFilter.validInbound(exchange)) {
            return error(exchange, HttpStatus.BAD_REQUEST, "INVALID_SYS006_AUTHORITY_INPUT", null);
        }
        List<String> expectedValues = exchange.getRequest().getHeaders()
                .getOrEmpty(EXPECTED_REVISION_HEADER);
        List<String> callerPurposeValues = exchange.getRequest().getHeaders()
                .getOrEmpty(PURPOSE_HEADER);
        List<String> ifMatchValues = exchange.getRequest().getHeaders()
                .getOrEmpty(IF_MATCH_HEADER);
        List<String> idempotencyKeyValues = exchange.getRequest().getHeaders()
                .getOrEmpty(IDEMPOTENCY_KEY_HEADER);
        ServerHttpRequest sanitized = exchange.getRequest().mutate().headers(headers -> {
            INTERNAL_HEADERS.forEach(headers::remove);
            headers.remove(EXPECTED_REVISION_HEADER);
        }).build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitized).build();
        if (DeviceIdentityPlaneFilter.verified(exchange)) {
            return chain.filter(sanitizedExchange);
        }
        if (sanitized.getMethod() == HttpMethod.OPTIONS) return chain.filter(sanitizedExchange);

        ScopeSelection scopeSelection = scopeSelection(sanitized.getURI().getRawQuery());

        GeneratedProductRouteCatalog.AuthorityEndpoint authorityEndpoint =
                routeCatalog.authorityEndpoint(
                        sanitized.getMethod() == null ? null : sanitized.getMethod().name(),
                        sanitized.getURI().getPath());
        if (authorityEndpoint != null) {
            if (!scopeSelection.valid() || scopeSelection.present()) {
                return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_SCOPE_SELECTION", null);
            }
            if (!EXPECTED_REVISION_HEADER.equals(
                    authorityEndpoint.expectedDecisionRevisionHeader())) {
                return error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
            }
            String expected = expectedRevision(expectedValues);
            if (expected == null) {
                return error(sanitizedExchange, HttpStatus.CONFLICT,
                        "DECISION_REVISION_CONFLICT", null);
            }
            ServerHttpRequest canonical = sanitized.mutate().headers(
                    headers -> headers.set(EXPECTED_REVISION_HEADER, expected)).build();
            return chain.filter(sanitizedExchange.mutate().request(canonical).build());
        }

        GeneratedProductRouteCatalog.Match match = routeCatalog.match(
                sanitized.getMethod() == null ? null : sanitized.getMethod().name(),
                sanitized.getURI().getPath(), sanitized.getURI().getRawQuery());
        if (per004 && (match.uniqueRoute() == null
                || !Per004GatewayRoutes.ROUTE_KEYS.contains(match.uniqueRoute().routeContractKey())
                || !sanitized.getURI().getPath().equals(
                        Per004GatewayRoutes.policy(match.uniqueRoute().routeContractKey()).path()))) {
            return error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (sys006 && !Sys006GatewayRoutes.matches(
                match.uniqueRoute(),
                sanitized.getMethod() == null ? null : sanitized.getMethod().name(),
                sanitized.getURI().getPath())) {
            return error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (match.status() == GeneratedProductRouteCatalog.MatchStatus.UNGOVERNED
                || match.status()
                    == GeneratedProductRouteCatalog.MatchStatus.LEGACY_EXEMPT) {
            if (!scopeSelection.valid() || scopeSelection.present()) {
                return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_SCOPE_SELECTION", null);
            }
            return chain.filter(sanitizedExchange);
        }
        if (match.status() != GeneratedProductRouteCatalog.MatchStatus.GOVERNED
                || match.uniqueRoute() == null) {
            LOGGER.warn(
                    "Product surface route resolution failed before authority evaluation: status={}",
                    match.status());
            return error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (!scopeSelection.valid()) {
            return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                    "INVALID_SCOPE_SELECTION", null);
        }
        sanitized = withoutScopeSelection(sanitized, scopeSelection);
        sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
        GeneratedProductRouteCatalog.Route route = match.uniqueRoute();
        if (PAY003_CONFIGURATION_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY003_RESULT_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY003_ACTION_ROUTE_KEY.equals(route.routeContractKey())) {
            sanitized = sanitized.mutate().headers(
                    headers -> headers.remove(EXPECTED_OBJECT_VERSION_HEADER)).build();
            sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
        }
        if ((HRM009_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || HRM011_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || PAY003_ACTION_ROUTE_KEY.equals(route.routeContractKey()))
                && (!route.stateChanging() || !route.highRiskStepUp()
                || !route.commandBoundStepUp())) {
            return error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        if (HRM011_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                && !validOptionalCanonicalPurpose(callerPurposeValues, "HR_LIFECYCLE")) {
            return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                    "INVALID_PURPOSE", null);
        }
        if (Sys006GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())
                && !validSys006Purpose(callerPurposeValues, route.stateChanging())) {
            return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                    "INVALID_PURPOSE", null);
        }
        if (Sys006GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())) {
            if (!route.stateChanging()) {
                if (!idempotencyKeyValues.isEmpty() || !ifMatchValues.isEmpty()) {
                    return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                            "INVALID_SYS006_COMMAND_HEADERS", null);
                }
            } else {
                String idempotencyKey = safeSys006IdempotencyKey(idempotencyKeyValues);
                boolean requiresObjectRevision =
                        Sys006GatewayRoutes.requiresExpectedObjectRevision(
                                route.routeContractKey());
                String expectedObjectVersion = requiresObjectRevision
                        ? strongSys006IfMatchVersion(ifMatchValues) : null;
                if (idempotencyKey == null
                        || (requiresObjectRevision && expectedObjectVersion == null)
                        || (!requiresObjectRevision && !ifMatchValues.isEmpty())) {
                    return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                            "INVALID_SYS006_COMMAND_HEADERS", null);
                }
                sanitized = sanitized.mutate().headers(headers -> {
                    headers.set(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
                    if (expectedObjectVersion == null) {
                        headers.remove(IF_MATCH_HEADER);
                        headers.remove(EXPECTED_OBJECT_VERSION_HEADER);
                    } else {
                        headers.set(IF_MATCH_HEADER, '"' + expectedObjectVersion + '"');
                        headers.set(EXPECTED_OBJECT_VERSION_HEADER, expectedObjectVersion);
                    }
                }).build();
                sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
            }
        }
        if (PAY003_ACTION_ROUTE_KEY.equals(route.routeContractKey())) {
            String expectedObjectVersion = strongIfMatchVersion(ifMatchValues);
            if (!validOptionalCanonicalPurpose(
                    callerPurposeValues, "STATUTORY_CONFIGURATION")) {
                return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_PURPOSE", null);
            }
            if (scopeSelection.present()
                    || !canonicalCountryPackActivationPath(sanitized.getURI().getPath())) {
                return error(sanitizedExchange, HttpStatus.BAD_REQUEST,
                        "INVALID_COUNTRY_PACK_PRECONDITION", null);
            }
            if (expectedObjectVersion == null) {
                return error(sanitizedExchange, HttpStatus.CONFLICT,
                        "OBJECT_VERSION_CONFLICT", null);
            }
            String canonicalIfMatch = '"' + expectedObjectVersion + '"';
            sanitized = sanitized.mutate().headers(headers -> {
                headers.set(IF_MATCH_HEADER, canonicalIfMatch);
                headers.set(EXPECTED_OBJECT_VERSION_HEADER, expectedObjectVersion);
            }).build();
            sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
        }
        if (ownerPurpose(route.routeContractKey()) != null) {
            sanitized = sanitized.mutate().headers(headers -> headers.remove(PURPOSE_HEADER))
                    .build();
            sanitizedExchange = sanitizedExchange.mutate().request(sanitized).build();
        }
        String rollout = sanitized.getHeaders().getFirst(
                ProductSurfaceRolloutHeaderFilter.STATE_HEADER);
        if (rollout == null || !ROLLOUT_STATES.contains(rollout)) {
            LOGGER.warn(
                    "Trusted product surface rollout evidence is missing before authority evaluation for route {}",
                    route.routeContractKey());
            return error(sanitizedExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        // States 000 and 100 normally bypass the product PEP. Home Runtime shadow reads and the
        // exact tenant-feature closed set are deliberate exceptions: both require direct Auth
        // evidence before an owner can be reached.
        if (rollout.charAt(1) == '0'
                && !requiresHomeShadowAuthority(rollout, route)
                && !TENANT_FEATURE_GOVERNED_ROUTE_KEYS.contains(route.routeContractKey())
                && !HRM007_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())) {
            return chain.filter(sanitizedExchange);
        }

        Long actorId = positive(sanitized.getHeaders().getFirst(VerifiedIdentityFilter.USER_HEADER));
        Long tenantId = positive(sanitized.getHeaders().getFirst(VerifiedIdentityFilter.TENANT_HEADER));
        if (actorId == null || tenantId == null) {
            return error(sanitizedExchange, HttpStatus.UNAUTHORIZED,
                    "AUTHENTICATION_REQUIRED", null);
        }
        String expected = expectedRevision(expectedValues);
        if (!expectedValues.isEmpty() && expected == null) {
            return error(sanitizedExchange, HttpStatus.CONFLICT,
                    "DECISION_REVISION_CONFLICT", null);
        }
        ServerHttpRequest canonical = sanitized.mutate().headers(headers -> {
            if (expected != null) headers.set(EXPECTED_REVISION_HEADER, expected);
        }).build();
        ServerWebExchange canonicalExchange = sanitizedExchange.mutate().request(canonical).build();
        ProductSurfaceContextDtos.AccessMode verifiedMode = verifiedTenantAccessMode(
                canonicalExchange);
        if (verifiedMode == null) {
            return error(canonicalExchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
        }
        ProductSurfaceContextDtos.RequestContext requestContext = requestContext(canonical,
                tenantId, actorId, verifiedMode);
        ProductSurfaceContextDtos.ProductEvaluationRequest request =
                new ProductSurfaceContextDtos.ProductEvaluationRequest(
                        new ProductSurfaceContextDtos.Subject(
                                "PRODUCT", route.productKey(), route.surfaceKey()),
                        route.routeContractKey(), null, scopeSelection.value());
        return aggregationService.evaluateProductTrusted(requestContext, request)
                .flatMap(result -> forwardOrDeny(
                        canonicalExchange, canonical, chain, route, result, expected,
                        requestContext.activeAccessMode()))
                .onErrorResume(
                        com.dwp.gateway.productsurface.ProductSurfaceAuthorityUnavailableException.class,
                        failure -> {
                            LOGGER.warn(
                                    "Product surface authority resolution failed for route {}",
                                    route.routeContractKey(),
                                    failure);
                            return error(canonicalExchange, HttpStatus.SERVICE_UNAVAILABLE,
                                    "AUTHORITY_RESOLUTION_UNAVAILABLE", null);
                        });
    }

    private ProductSurfaceContextDtos.RequestContext requestContext(
            ServerHttpRequest request,
            long tenantId,
            long actorId,
            ProductSurfaceContextDtos.AccessMode verifiedTenantMode) {
        String supportSession = request.getHeaders().getFirst(
                SupportSessionContextFilter.SUPPORT_SESSION_HEADER);
        ProductSurfaceContextDtos.AccessMode mode = supportSession == null
                ? verifiedTenantMode
                : ProductSurfaceContextDtos.AccessMode.PROVIDER_SUPPORT;
        return new ProductSurfaceContextDtos.RequestContext(
                tenantId, actorId, mode, supportSession,
                request.getHeaders().getFirst(SupportSessionContextFilter.SUPPORT_REVISION_HEADER),
                splitScopes(request.getHeaders().getFirst(
                        SupportSessionContextFilter.SUPPORT_SCOPES_HEADER)),
                request.getHeaders().getFirst("X-Correlation-ID"),
                request.getHeaders().getFirst("traceparent"),
                request.getHeaders().getFirst("tracestate"),
                request.getHeaders().getFirst(VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER),
                splitScopes(request.getHeaders().getFirst(VerifiedIdentityFilter.ROLES_HEADER)),
                splitScopes(request.getHeaders().getFirst(
                        VerifiedIdentityFilter.PERMISSIONS_HEADER)));
    }

    private ProductSurfaceContextDtos.AccessMode verifiedTenantAccessMode(
            ServerWebExchange exchange) {
        Object value = exchange.getAttribute(
                VerifiedIdentityFilter.VERIFIED_ACTIVE_ACCESS_MODE_ATTRIBUTE);
        if (value == null) return ProductSurfaceContextDtos.AccessMode.NORMAL;
        if ("NORMAL".equals(value)) return ProductSurfaceContextDtos.AccessMode.NORMAL;
        if ("ELEVATED".equals(value)) return ProductSurfaceContextDtos.AccessMode.ELEVATED;
        return null;
    }

    private Mono<Void> forwardOrDeny(
            ServerWebExchange exchange,
            ServerHttpRequest request,
            GatewayFilterChain chain,
            GeneratedProductRouteCatalog.Route route,
            ProductSurfaceContextAggregationService.TrustedProductEvaluation trustedResult,
            String expected,
            ProductSurfaceContextDtos.AccessMode activeAccessMode) {
        ProductSurfaceContextDtos.ProductEvaluationData result = trustedResult.data();
        ProductSurfaceContextDtos.SourceRevisions trustedRevisions = trustedResult.revisions();
        if (trustedRevisions == null || blank(trustedRevisions.tenantControl())) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if (trustedResult.authRouteProductNotRegistered()
                && result.decision() == ProductSurfaceContextDtos.Decision.ROUTE_DENIED
                && "PRODUCT_NOT_REGISTERED".equals(result.reasonCode())) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        boolean allowed = result.decision() == ProductSurfaceContextDtos.Decision.ALLOWED;
        if (route.stateChanging() && expected == null
                && (allowed
                        || result.decision()
                                == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED)) {
            return error(exchange, HttpStatus.CONFLICT,
                    "DECISION_REVISION_CONFLICT", result.decisionRevision());
        }
        if (result.decision() == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED
                && HCM_AUDIT_READ_ROUTE_KEYS.contains(route.routeContractKey())) {
            // These two ELEVATED-only reads deliberately have no command binding. NORMAL may
            // learn that step-up is required, but it must never receive owner-forwarding
            // evidence or reach the Platform audit repository.  Do not expose the challenge
            // oracle until the complete trusted route/query, tenant, subject, scope, purpose,
            // and revision envelope has been established; Auth already proves the package,
            // permission, auditor role/duty, and static SoD intersection.
            ProductSurfaceContextDtos.SourceRevisions revisions = trustedResult.revisions();
            String subjectPrincipal = request.getHeaders().getFirst(
                    VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER);
            boolean completeChallenge = activeAccessMode
                    == ProductSurfaceContextDtos.AccessMode.NORMAL
                    && "HRIS_AUDIT_EVIDENCE".equals(
                            WAVE2_OWNER_PURPOSE_BY_ROUTE.get(route.routeContractKey()))
                    && !blank(result.decisionRevision())
                    && !blank(trustedResult.contextKey())
                    && trustedResult.scope() != null
                    && !blank(trustedResult.scope().key())
                    && "RESOURCE_SET".equals(trustedResult.scope().kind())
                    && !blank(subjectPrincipal)
                    && revisions != null
                    && numericRevision("field-policy", revisions.policy()) != null
                    && numericRevision("authorization", revisions.auth()) != null;
            if (!completeChallenge) {
                return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
            }
            return error(exchange, HttpStatus.FORBIDDEN,
                    "STEP_UP_REQUIRED", result.decisionRevision());
        }
        if (result.decision() == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED
                && route.genericSessionStepUp()) {
            ProductSurfaceContextDtos.SourceRevisions revisions = trustedResult.revisions();
            String subjectPrincipal = request.getHeaders().getFirst(
                    VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER);
            boolean completeChallenge = activeAccessMode
                    == ProductSurfaceContextDtos.AccessMode.NORMAL
                    && route.highRiskStepUp()
                    && !route.commandBoundStepUp()
                    && !blank(result.decisionRevision())
                    && !blank(trustedResult.contextKey())
                    && trustedResult.scope() != null
                    && !blank(trustedResult.scope().key())
                    && !blank(trustedResult.scope().kind())
                    && !blank(subjectPrincipal)
                    && revisions != null
                    && numericRevision("field-policy", revisions.policy()) != null
                    && numericRevision("authorization", revisions.auth()) != null;
            if (!completeChallenge) {
                return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
            }
            return error(exchange, HttpStatus.FORBIDDEN,
                    "STEP_UP_REQUIRED", result.decisionRevision());
        }
        boolean eligibleStepUp = result.decision()
                == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED
                && route.highRiskStepUp();
        if (!allowed && !eligibleStepUp) {
            if (result.decision() == ProductSurfaceContextDtos.Decision.STEP_UP_REQUIRED) {
                return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
            }
            HttpStatus status = result.decision()
                    == ProductSurfaceContextDtos.Decision.AUTHORITY_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.FORBIDDEN;
            if (status == HttpStatus.FORBIDDEN) {
                return deny(exchange, result.reasonCode(), result.decisionRevision(),
                        route.publicPath());
            }
            return error(exchange, status, result.reasonCode(), result.decisionRevision());
        }
        if (route.stateChanging() && !result.decisionRevision().equals(expected)) {
            return error(exchange, HttpStatus.CONFLICT,
                    "DECISION_REVISION_CONFLICT", result.decisionRevision());
        }
        String contextKey = trustedResult.contextKey();
        String scopeKey = trustedResult.scope() == null ? null : trustedResult.scope().key();
        if (blank(result.decisionRevision()) || blank(contextKey) || blank(scopeKey)) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        String purpose = ownerPurpose(route.routeContractKey());
        String subjectPrincipal = request.getHeaders().getFirst(
                VerifiedIdentityFilter.PERSON_PUBLIC_ID_HEADER);
        ProductSurfaceContextDtos.SourceRevisions revisions = trustedResult.revisions();
        String fieldPolicyRevision = revisions == null
                ? null : numericRevision("field-policy", revisions.policy());
        String hrm007FieldPolicy = HRM007_FIELD_POLICY_BY_ROUTE.get(route.routeContractKey());
        String fieldPolicyKey = hrm007FieldPolicy != null
                ? hrm007FieldPolicy
                : HRM009_FIELD_POLICY_BY_ROUTE.containsKey(route.routeContractKey())
                        ? HRM009_FIELD_POLICY_BY_ROUTE.get(route.routeContractKey())
                        : HRM011_FIELD_POLICY_BY_ROUTE.containsKey(route.routeContractKey())
                                ? HRM011_FIELD_POLICY_BY_ROUTE.get(route.routeContractKey())
                                : PAY003_FIELD_POLICY_BY_ROUTE.get(route.routeContractKey());
        String authorizationRevision = revisions == null
                ? null : numericRevision("authorization", revisions.auth());
        if (purpose != null && (blank(subjectPrincipal)
                || fieldPolicyRevision == null || authorizationRevision == null)) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        boolean requiresPopulationKind = TIM_WAVE2_ROUTE_KEYS.contains(route.routeContractKey())
                || PAYROLL_OWNER_POPULATION_ROUTE_KEYS.contains(route.routeContractKey())
                || PER_TIM_WAVE5_POPULATION_ROUTE_KEYS.contains(route.routeContractKey())
                || CONTROLLED_TRANSFER_OWNER_ROUTE_KEYS.contains(route.routeContractKey())
                || V49_TARGET_POPULATION_ROUTE_KEYS.contains(route.routeContractKey())
                || HRM007_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                || HRM009_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                || HRM009_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || HRM011_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                || HRM011_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || PAY003_CONFIGURATION_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY003_RESULT_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY003_ACTION_ROUTE_KEY.equals(route.routeContractKey())
                || NAVIGATION_V2_TENANT_ROUTE_KEYS.contains(route.routeContractKey())
                || NAVIGATION_V2_LEGAL_ENTITY_ROUTE_KEYS.contains(route.routeContractKey())
                || Sys006GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey());
        String trustedPopulationKind = requiresPopulationKind
                ? trustedPopulationKind(route, trustedResult.scope()) : null;
        if (requiresPopulationKind && blank(trustedPopulationKind)) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if (CONTROLLED_TRANSFER_OWNER_ROUTE_KEYS.contains(route.routeContractKey())
                && !CONTROLLED_TRANSFER_POPULATION_KINDS.contains(trustedPopulationKind)) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if ((NAVIGATION_V2_TENANT_ROUTE_KEYS.contains(route.routeContractKey())
                && !"SELF".equals(trustedPopulationKind))
                || (NAVIGATION_V2_LEGAL_ENTITY_ROUTE_KEYS.contains(route.routeContractKey())
                && !"TARGET_POPULATION".equals(trustedPopulationKind))) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        boolean hrm007ConfigurationEntry = HRM007_CONFIGURATION_ENTRY_QUERY_ROUTE_KEYS.contains(
                route.routeContractKey());
        if (hrm007ConfigurationEntry && !"RESOURCE_SET".equals(trustedPopulationKind)) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if ((HRM009_PERSONAL_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                && !"SELF".equals(trustedPopulationKind))
                || (HRM009_OPERATIONS_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                && !HRM009_OPERATIONS_POPULATION_KINDS.contains(trustedPopulationKind))) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if ((HRM011_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                || HRM011_ACTION_ROUTE_KEY.equals(route.routeContractKey()))
                && !HRM011_POPULATION_KINDS.contains(trustedPopulationKind)) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        boolean pay003Configuration = PAY003_CONFIGURATION_ROUTE_KEYS.contains(
                route.routeContractKey());
        if (pay003Configuration && !"RESOURCE_SET".equals(trustedPopulationKind)) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        if ((PAY003_RESULT_ROUTE_KEYS.contains(route.routeContractKey())
                || PAY003_ACTION_ROUTE_KEY.equals(route.routeContractKey()))
                && !"TARGET_POPULATION".equals(trustedPopulationKind)) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        String populationKind = hrm007ConfigurationEntry || pay003Configuration
                ? "CONFIG_SCOPE" : trustedPopulationKind;
        if (Sys006GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())
                && (result.revalidateAt() == null
                || !Sys006InboundAuthorityFilter.validInbound(exchange))) {
            return error(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHORITY_RESOLUTION_UNAVAILABLE", result.decisionRevision());
        }
        responseRevision(exchange, result.decisionRevision());
        ServerHttpRequest trusted = request.mutate().headers(headers -> {
            headers.set(ROUTE_HEADER, route.routeContractKey());
            headers.set(CURRENT_REVISION_HEADER, result.decisionRevision());
            headers.set(CONTEXT_HEADER, contextKey);
            headers.set(SCOPE_HEADER, scopeKey);
            headers.set(ACTIVE_ACCESS_MODE_HEADER, activeAccessMode.name());
            if (result.revalidateAt() != null) {
                headers.set(CURRENT_REVALIDATE_AT_HEADER, result.revalidateAt().toString());
            }
            if (purpose != null) {
                headers.set(PURPOSE_HEADER, purpose);
                headers.set(SUBJECT_PRINCIPAL_HEADER, subjectPrincipal);
                headers.set(FIELD_POLICY_REVISION_HEADER, fieldPolicyRevision);
                headers.set(AUTHORIZATION_REVISION_HEADER, authorizationRevision);
                if (fieldPolicyKey != null) {
                    headers.set(FIELD_POLICY_KEY_HEADER, fieldPolicyKey);
                }
            }
            if (populationKind != null) {
                headers.set(POPULATION_KIND_HEADER, populationKind);
            }
        }).build();
        ServerWebExchange trustedExchange = exchange.mutate().request(trusted).build();
        if (Per004GatewayRoutes.ROUTE_KEYS.contains(route.routeContractKey())) {
            return per004Forwarder.forward(trustedExchange, chain, route, trustedResult);
        }
        return chain.filter(trustedExchange);
    }

    private String trustedPopulationKind(
            GeneratedProductRouteCatalog.Route route,
            ProductSurfaceContextDtos.EffectiveScope scope) {
        if (scope == null) return null;
        String kind = scope.kind();
        boolean concreteOperationsPopulation =
                HRM009_OPERATIONS_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                        || HRM011_ENTRY_QUERY_ROUTE_KEYS.contains(route.routeContractKey())
                        || HRM011_ACTION_ROUTE_KEY.equals(route.routeContractKey());
        if (!concreteOperationsPopulation
                || !"TARGET_POPULATION".equals(kind)) {
            return kind;
        }
        // People eligibility materializes the generic Auth resolver as TARGET_POPULATION
        // while sealing the concrete owner boundary in its trusted displayName. Canonicalize
        // only the four HRM-009 owner kinds; every other display fails the closed-set check.
        return HRM011_POPULATION_KINDS.contains(scope.displayName())
                ? scope.displayName() : null;
    }

    private boolean validOptionalCanonicalPurpose(List<String> values, String expected) {
        return values.isEmpty()
                || (values.size() == 1 && expected.equals(values.getFirst()));
    }

    private boolean validSys006Purpose(List<String> values, boolean stateChanging) {
        return stateChanging
                ? values.equals(List.of(Sys006GatewayRoutes.PURPOSE))
                : validOptionalCanonicalPurpose(values, Sys006GatewayRoutes.PURPOSE);
    }

    private String ownerPurpose(String routeContractKey) {
        return Sys006GatewayRoutes.ROUTE_KEYS.contains(routeContractKey)
                ? Sys006GatewayRoutes.PURPOSE
                : WAVE2_OWNER_PURPOSE_BY_ROUTE.get(routeContractKey);
    }

    private boolean canonicalCountryPackActivationPath(String path) {
        return path != null && COUNTRY_PACK_ACTIVATION_PATH.matcher(path).matches();
    }

    private String strongIfMatchVersion(List<String> values) {
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || !value.matches("\"(?:0|[1-9][0-9]*)\"")) return null;
        String version = value.substring(1, value.length() - 1);
        try {
            Long.parseLong(version);
            return version;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private String safeSys006IdempotencyKey(List<String> values) {
        if (values.size() != 1) return null;
        String value = values.getFirst();
        return value != null && SYS006_IDEMPOTENCY_KEY.matcher(value).matches()
                ? value : null;
    }

    private String strongSys006IfMatchVersion(List<String> values) {
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || !value.matches("\"[1-9][0-9]{0,15}\"")) return null;
        String version = value.substring(1, value.length() - 1);
        try {
            long parsed = Long.parseLong(version);
            return parsed <= SYS006_MAX_SAFE_OBJECT_REVISION ? version : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private Mono<Void> deny(
            ServerWebExchange exchange,
            String code,
            String currentRevision,
            String routeTemplate) {
        return denialAudit.publish(exchange,
                        GatewayDenialAuditSink.Denial.productAuthority(
                                code, routeTemplate))
                .then(Mono.defer(() -> error(
                        exchange, HttpStatus.FORBIDDEN, code, currentRevision)))
                .onErrorResume(ignored -> error(
                        exchange,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "AUDIT_EVIDENCE_UNAVAILABLE",
                        currentRevision));
    }

    private String expectedRevision(List<String> values) {
        if (values.isEmpty()) return null;
        if (values.size() != 1) return null;
        String value = values.getFirst();
        if (value == null || value.isBlank() || value.length() > 200
                || value.indexOf(',') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) return null;
        return value.trim();
    }

    private boolean requiresHomeShadowAuthority(
            String rollout,
            GeneratedProductRouteCatalog.Route route) {
        return "100".equals(rollout)
                && HOME_RUNTIME_ROUTE_KEYS.contains(route.routeContractKey());
    }

    private List<String> splitScopes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .filter(scope -> !scope.isBlank())
                .distinct()
                .toList();
    }

    private Long positive(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String numericRevision(String domain, String sourceRevision) {
        if (blank(sourceRevision)) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (domain + ":" + sourceRevision).getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                value = (value << 8) | (digest[index] & 0xffL);
            }
            value &= Long.MAX_VALUE;
            return Long.toString(value == 0L ? 1L : value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void responseRevision(ServerWebExchange exchange, String revision) {
        if (blank(revision)) return;
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(RESPONSE_REVISION_HEADER, revision);
            return Mono.empty();
        });
    }

    private Mono<Void> error(
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String currentRevision) {
        try {
            responseRevision(exchange, currentRevision);
            String correlation = exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
            byte[] body = objectMapper.writeValueAsBytes(
                    ProductSurfaceContextDtos.ApiResponse.error(code, code, correlation));
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return exchange.getResponse().writeWith(Mono.just(
                    exchange.getResponse().bufferFactory().wrap(body)));
        } catch (Exception exception) {
            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return exchange.getResponse().setComplete();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public int getOrder() {
        return -92;
    }

}
