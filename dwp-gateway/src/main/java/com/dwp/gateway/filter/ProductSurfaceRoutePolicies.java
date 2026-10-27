package com.dwp.gateway.filter;

import static com.dwp.gateway.filter.ProductSurfaceDecisionHeaders.HRM012_ACTION_ROUTE;

import com.dwp.gateway.productsurface.GeneratedProductRouteCatalog;
import com.dwp.gateway.productsurface.ProductSurfaceContextDtos;
import com.dwp.gateway.productsurface.Sys006GatewayRoutes;
import com.dwp.gateway.productsurface.Sys008GatewayRoutes;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed, code-owned route policy tables used by PRODUCT decision enforcement. */
final class ProductSurfaceRoutePolicies {

    private ProductSurfaceRoutePolicies() {
    }

    static final Set<String> ROLLOUT_STATES = Set.of("000", "100", "110", "111");
    static final Set<String> HOME_RUNTIME_ROUTE_KEYS = Set.of(
            "route.workplace.work.home-read-model.data",
            "route.workplace.work.home-shadow-receipt.action",
            "route.workplace.work.home-widget-action-execute.action");
    /**
     * Default-off tenant-feature routes authorized by the sealed HRIS activation decisions,
     * including the SYS-006 automation control plane. This closed set is deliberately code-owned:
     * rollout state, tenant identity, environment or operator configuration cannot bypass direct
     * Auth evaluation.
     */
    static final Set<String> TENANT_FEATURE_GOVERNED_ROUTE_KEYS = Set.of(
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
            "route.hcm.operations.pay-retirement.page",
            "route.hcm.operations.pay-retirement.data",
            "route.hcm.operations.country-pack-activate.action",
            HRM012_ACTION_ROUTE,
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
            "route.hcm.configuration.automation-run-cancel.action",
            "route.hcm.team.performance.page",
            "route.hcm.team.performance.data",
            "route.hcm.personal.performance-feedback.page",
            "route.hcm.performance.feedback-request-options.data",
            "route.hcm.performance.feedback-request-create.action",
            "route.hcm.personal.feedback-assignments-mine.data",
            "route.hcm.personal.feedback-assignment-detail.data",
            "route.hcm.personal.feedback-response-submit.action",
            "route.hcm.performance.feedback-release.data",
            "route.hcm.configuration.settings-integrations.page",
            "route.hcm.configuration.dwp-integrations.data",
            "route.hcm.configuration.connector-catalog.data",
            "route.hcm.configuration.connector-definition-create.action",
            "route.hcm.configuration.connector-test.action",
            "route.hcm.configuration.connector-mapping-dry-run.action",
            "route.hcm.configuration.connector-execution-create.action",
            "route.hcm.configuration.connector-execution-detail.data",
            "route.hcm.configuration.connector-definition-validate.action",
            "route.hcm.configuration.connector-definition-submit.action",
            "route.hcm.configuration.connector-definition-approve.action",
            "route.hcm.configuration.connector-definition-activate.action",
            "route.hcm.configuration.connector-mapping-version-create.action",
            "route.hcm.configuration.connector-mapping-version-validate.action",
            "route.hcm.configuration.connector-mapping-version-submit.action",
            "route.hcm.configuration.connector-mapping-version-approve.action",
            "route.hcm.configuration.connector-mapping-version-activate.action",
            "route.hcm.configuration.connector-execution-reconcile.action",
            "route.hcm.configuration.connector-execution-retry.action",
            "route.hcm.configuration.connector-execution-cancel.action",
            "route.hcm.operations.exit-return.page",
            "route.hcm.operations.exit-return.data",
            "route.hcm.operations.separation-case.action");
    static final Set<String> HRM007_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.team.people-changes.page",
            "route.hcm.team.people-changes.data",
            "route.hcm.operations.people-appointments.page",
            "route.hcm.operations.people-appointments.data",
            "route.hcm.configuration.people-employment.page",
            "route.hcm.configuration.people-employment.data");
    static final Set<String> HRM007_CONFIGURATION_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.configuration.people-employment.page",
            "route.hcm.configuration.people-employment.data");
    static final Set<String> HRM009_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.personal.requests.page",
            "route.hcm.personal.requests.data",
            "route.hcm.operations.certificates-documents.page",
            "route.hcm.operations.certificates-documents.data");
    static final Set<String> HRM009_PERSONAL_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.personal.requests.page",
            "route.hcm.personal.requests.data");
    static final Set<String> HRM009_OPERATIONS_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.operations.certificates-documents.page",
            "route.hcm.operations.certificates-documents.data");
    static final String HRM009_ACTION_ROUTE_KEY =
            "route.hcm.operations.certificate-issue.action";
    static final Set<String> HRM009_OPERATIONS_POPULATION_KINDS = Set.of(
            "LEGAL_ENTITY", "NAMED_POPULATION", "ORG_TREE", "ORG_UNIT");
    static final Set<String> HRM011_ENTRY_QUERY_ROUTE_KEYS = Set.of(
            "route.hcm.operations.contracts-compensation-basis.page",
            "route.hcm.operations.contracts-compensation-basis.data");
    static final String HRM011_ACTION_ROUTE_KEY =
            "route.hcm.operations.employment-contract-issue.action";
    static final Set<String> HRM011_POPULATION_KINDS = Set.of(
            "LEGAL_ENTITY", "NAMED_POPULATION", "ORG_TREE", "ORG_UNIT");
    static final Set<String> PAY003_CONFIGURATION_ROUTE_KEYS = Set.of(
            "route.hcm.configuration.statutory.page",
            "route.hcm.configuration.statutory.data");
    static final Set<String> PAY003_RESULT_ROUTE_KEYS = Set.of(
            "route.hcm.operations.pay-tax-social-insurance.page",
            "route.hcm.operations.pay-tax-social-insurance.data");
    static final String PAY003_ACTION_ROUTE_KEY =
            "route.hcm.operations.country-pack-activate.action";
    static final Set<String> PAY004_RESULT_ROUTE_KEYS = Set.of(
            "route.hcm.operations.pay-retirement.page",
            "route.hcm.operations.pay-retirement.data");
    static final Set<String> PER008_ROUTE_KEYS = Set.of(
            "route.hcm.team.performance.page",
            "route.hcm.team.performance.data",
            "route.hcm.personal.performance-feedback.page",
            "route.hcm.performance.feedback-request-options.data",
            "route.hcm.performance.feedback-request-create.action",
            "route.hcm.personal.feedback-assignments-mine.data",
            "route.hcm.personal.feedback-assignment-detail.data",
            "route.hcm.personal.feedback-response-submit.action",
            "route.hcm.performance.feedback-release.data");
    static final Set<String> TIM_WAVE2_ROUTE_KEYS = Set.of(
            "route.hcm.operations.time-interpretation.page",
            "route.hcm.operations.time-interpretation-run.action",
            "route.hcm.operations.time-interpretation-trace.data");
    static final Set<String> PER_TIM_WAVE5_POPULATION_ROUTE_KEYS = Set.of(
            "route.hcm.performance.assigned-review.data",
            "route.hcm.performance.assigned-review-draft-update.action",
            "route.hcm.performance.assigned-review-submit.action",
            "route.hcm.operations.performance-multirater-surveys.page",
            "route.hcm.time.timecard.data",
            "route.hcm.time.timecard-correction.action",
            "route.hcm.time.timecard-submit.action",
            "route.hcm.time.timecard-decision.action",
            "route.hcm.time.command-receipt.data");
    static final Set<String> HCM_AUDIT_READ_ROUTE_KEYS = Set.of(
            "route.hcm.audit.evidence.page",
            "route.hcm.audit.evidence-detail.data");
    static final Set<String> V49_TARGET_POPULATION_ROUTE_KEYS = Set.of(
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
    static final Set<String> PAYROLL_OWNER_POPULATION_ROUTE_KEYS = Set.of(
            "route.hcm.operations.payroll-run-calculate.action",
            "route.hcm.operations.payroll-run.data",
            "route.hcm.operations.payroll-run-approve.action",
            "route.hcm.operations.payroll-run-finalize.action",
            "route.hcm.operations.payroll-command-receipt.data");
    static final Set<String> CONTROLLED_TRANSFER_OWNER_ROUTE_KEYS = Set.of(
            "route.hcm.management.controlled-transfer.data",
            "route.hcm.management.controlled-transfer-create.action");
    static final Set<String> NAVIGATION_V2_TENANT_ROUTE_KEYS = Set.of(
            "route.hcm.shell.navigation-aggregate.data",
            "route.hcm.shell.navigation-deep-link-decision.data");
    static final Set<String> NAVIGATION_V2_LEGAL_ENTITY_ROUTE_KEYS = Set.of(
            "route.hcm.shell.navigation-aggregate-legal-entity.data",
            "route.hcm.shell.navigation-deep-link-decision-legal-entity.data");
    static final Set<String> CONTROLLED_TRANSFER_POPULATION_KINDS = Set.of(
            "CONNECTOR_SET", "LEGAL_ENTITY");
    static final Map<String, String> HRM007_FIELD_POLICY_BY_ROUTE = Map.of(
            "route.hcm.team.people-changes.page", "hrm007.iaq-005.fields.v1",
            "route.hcm.team.people-changes.data", "hrm007.iaq-005.fields.v1",
            "route.hcm.operations.people-appointments.page", "hrm007.iaq-007.fields.v1",
            "route.hcm.operations.people-appointments.data", "hrm007.iaq-007.fields.v1",
            "route.hcm.configuration.people-employment.page", "hrm007.iaq-013.fields.v1",
            "route.hcm.configuration.people-employment.data", "hrm007.iaq-013.fields.v1");
    static final Map<String, String> HRM009_FIELD_POLICY_BY_ROUTE = Map.of(
            "route.hcm.personal.requests.page", "hrm009.iaq-003.fields.v1",
            "route.hcm.personal.requests.data", "hrm009.iaq-003.fields.v1",
            "route.hcm.operations.certificates-documents.page", "hrm009.iaq-010.fields.v1",
            "route.hcm.operations.certificates-documents.data", "hrm009.iaq-010.fields.v1");
    static final Map<String, String> HRM011_FIELD_POLICY_BY_ROUTE = Map.of(
            "route.hcm.operations.contracts-compensation-basis.page", "HRM-AUTH-007",
            "route.hcm.operations.contracts-compensation-basis.data", "HRM-AUTH-007",
            HRM011_ACTION_ROUTE_KEY, "HRM-AUTH-015");
    static final Map<String, String> PAY003_FIELD_POLICY_BY_ROUTE = Map.of(
            "route.hcm.configuration.statutory.page", "pay003.iaq-026.fields.v1",
            "route.hcm.configuration.statutory.data", "pay003.iaq-026.fields.v1",
            "route.hcm.operations.pay-tax-social-insurance.page", "pay003.iaq-048.fields.v1",
            "route.hcm.operations.pay-tax-social-insurance.data", "pay003.iaq-048.fields.v1");
    static final Map<String, String> PAY004_FIELD_POLICY_BY_ROUTE = Map.of(
            "route.hcm.operations.pay-retirement.page", "pay004.iaq-049.fields.v1",
            "route.hcm.operations.pay-retirement.data", "pay004.iaq-049.fields.v1");
    static final Map<String, String> PER008_FIELD_POLICY_BY_ROUTE = Map.ofEntries(
            Map.entry("route.hcm.team.performance.page", "per008.iaq-038.fields.v2"),
            Map.entry("route.hcm.team.performance.data", "per008.iaq-038.fields.v2"),
            Map.entry("route.hcm.personal.performance-feedback.page",
                    "per008.feedback-assigned-list.fields.v1"),
            Map.entry("route.hcm.performance.feedback-request-options.data",
                    "per008.feedback-request-options.fields.v1"),
            Map.entry("route.hcm.personal.feedback-assignments-mine.data",
                    "per008.feedback-assigned-list.fields.v1"),
            Map.entry("route.hcm.personal.feedback-assignment-detail.data",
                    "per008.feedback-assignment-detail.fields.v1"),
            Map.entry("route.hcm.performance.feedback-release.data",
                    "per008.feedback-release.fields.v1"));
    static final Map<String, String> WAVE2_OWNER_PURPOSE_BY_ROUTE = Map.ofEntries(
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
            Map.entry("route.hcm.operations.pay-retirement.page", "PAYROLL_OPERATIONS"),
            Map.entry("route.hcm.operations.pay-retirement.data", "PAYROLL_OPERATIONS"),
            Map.entry("route.hcm.team.performance.page", "TEAM_MANAGEMENT"),
            Map.entry("route.hcm.team.performance.data", "TEAM_MANAGEMENT"),
            Map.entry("route.hcm.personal.performance-feedback.page",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.performance.feedback-request-options.data",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.performance.feedback-request-create.action",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.personal.feedback-assignments-mine.data",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.personal.feedback-assignment-detail.data",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.personal.feedback-response-submit.action",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry("route.hcm.performance.feedback-release.data",
                    "PERFORMANCE_FEEDBACK"),
            Map.entry(PAY003_ACTION_ROUTE_KEY, "STATUTORY_CONFIGURATION"));
    static String ownerPurpose(String routeContractKey) {
        if (Sys006GatewayRoutes.ROUTE_KEYS.contains(routeContractKey)) {
            return Sys006GatewayRoutes.PURPOSE;
        }
        if (Sys008GatewayRoutes.ROUTE_KEYS.contains(routeContractKey)) {
            return Sys008GatewayRoutes.PURPOSE;
        }
        return WAVE2_OWNER_PURPOSE_BY_ROUTE.get(routeContractKey);
    }

    static String trustedPopulationKind(
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

    static boolean requiresHomeShadowAuthority(
            String rollout,
            GeneratedProductRouteCatalog.Route route) {
        return "100".equals(rollout)
                && HOME_RUNTIME_ROUTE_KEYS.contains(route.routeContractKey());
    }

}
