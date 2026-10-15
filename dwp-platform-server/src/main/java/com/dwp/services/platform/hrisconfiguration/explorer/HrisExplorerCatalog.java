package com.dwp.services.platform.hrisconfiguration.explorer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.Module;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

/** Canonical identity registry. Authorization is deliberately supplied at request time. */
public final class HrisExplorerCatalog {

    public static final int CANONICAL_COUNT = 98;
    public static final int BASE76_COUNT = 76;
    public static final int MODERN22_COUNT = 22;

    private static final Set<String> DWP_CONTROL_TARGETS = Set.of(
            "/admin/experience/branding?product=HCM",
            "/admin/identity/app-governance?product=HCM",
            "/admin/platform/catalog?product=HCM",
            "/admin/integrations/productivity?product=HCM",
            "/admin/governance/audit-overview?product=HCM",
            "/admin/platform/registry?namespace=hris-extension");

    private static final String ROWS = """
            BASE76|my-hr-home|SYS|MY_HR|/hr/home
            BASE76|my-hr-profile|HRM|MY_HR|/hr/me/profile
            BASE76|my-employment-documents|HRM|MY_HR|/hr/me/employment-documents
            BASE76|my-time|TIM|MY_HR|/hr/time
            BASE76|my-leave|TIM|MY_HR|/hr/me/leave
            BASE76|my-pay-tax|PAY|MY_HR|/hr/pay
            BASE76|my-goals-performance|PER|MY_HR|/hr/talent
            BASE76|my-hr-requests|HRM|MY_HR|/hr/me/requests
            BASE76|team-home|SYS|TEAM|/hr/team/home
            BASE76|team-members|HRM|TEAM|/hr/team/members
            BASE76|team-time|TIM|TEAM|/hr/team/time
            BASE76|team-leave|TIM|TEAM|/hr/team/leave
            BASE76|team-people-changes|HRM|TEAM|/hr/team/people-changes
            BASE76|team-performance|PER|TEAM|/hr/team/performance
            BASE76|team-delegation|SYS|TEAM|/hr/team/delegation
            BASE76|hr-operations-home|SYS|HR_OPERATIONS|/hr/operations/home
            BASE76|hr-people|HRM|HR_OPERATIONS|/hr/operations/people
            BASE76|hr-employment-assignments|HRM|HR_OPERATIONS|/hr/operations/people/employment-assignments
            BASE76|hr-appointments|HRM|HR_OPERATIONS|/hr/operations/people/appointments
            BASE76|hr-organization-positions|HRM|HR_OPERATIONS|/hr/operations/people/organization-positions
            BASE76|hr-contracts-compensation-basis|HRM|HR_OPERATIONS|/hr/operations/people/contracts-compensation-basis
            BASE76|hr-employee-change-requests|HRM|HR_OPERATIONS|/hr/operations/people/employee-change-requests
            BASE76|hr-certificates-documents|HRM|HR_OPERATIONS|/hr/operations/people/certificates-documents
            BASE76|hr-exit-return|HRM|HR_OPERATIONS|/hr/operations/people/exit-return
            BASE76|hr-data-quality|HRM|HR_OPERATIONS|/hr/operations/people/data-quality
            BASE76|time-command-center|TIM|TIME|/hr/operations/time/command-center
            BASE76|time-work-plans|TIM|TIME|/hr/operations/time/work-plans
            BASE76|time-clock-ingestion|TIM|TIME|/hr/operations/time/clock-ingestion
            BASE76|time-interpretation|TIM|TIME|/hr/operations/time/interpretation
            BASE76|time-exceptions|TIM|TIME|/hr/operations/time/exceptions
            BASE76|time-daily-close|TIM|TIME|/hr/operations/time/daily-close
            BASE76|time-monthly-close|TIM|TIME|/hr/operations/time/monthly-close
            BASE76|time-leave-operations|TIM|TIME|/hr/operations/time/leave-operations
            BASE76|time-accrual-expiration|TIM|TIME|/hr/operations/time/accrual-expiration
            BASE76|time-reports|TIM|TIME|/hr/operations/time/reports
            BASE76|pay-command-center|PAY|PAYROLL|/hr/operations/payroll/command-center
            BASE76|pay-population|PAY|PAYROLL|/hr/operations/payroll/population
            BASE76|pay-inputs|PAY|PAYROLL|/hr/operations/payroll/inputs
            BASE76|pay-calculation|PAY|PAYROLL|/hr/operations/payroll/calculation
            BASE76|pay-validation-reconciliation|PAY|PAYROLL|/hr/operations/payroll/validation-reconciliation
            BASE76|pay-retro-corrections|PAY|PAYROLL|/hr/operations/payroll/retro-corrections
            BASE76|pay-results|PAY|PAYROLL|/hr/operations/payroll/results
            BASE76|pay-payments|PAY|PAYROLL|/hr/operations/payroll/payments
            BASE76|pay-accounting|PAY|PAYROLL|/hr/operations/payroll/accounting
            BASE76|pay-statements|PAY|PAYROLL|/hr/operations/payroll/statements
            BASE76|pay-tax-social-insurance|PAY|PAYROLL|/hr/operations/payroll/tax-social-insurance
            BASE76|pay-retirement|PAY|PAYROLL|/hr/operations/payroll/retirement
            BASE76|pay-year-end-tax|PAY|PAYROLL|/hr/operations/payroll/year-end-tax
            BASE76|pay-reports|PAY|PAYROLL|/hr/operations/payroll/reports
            BASE76|performance-command-center|PER|PERFORMANCE|/hr/operations/performance/command-center
            BASE76|performance-goal-cycles|PER|PERFORMANCE|/hr/operations/performance/goal-cycles
            BASE76|performance-review-operations|PER|PERFORMANCE|/hr/operations/performance/review-operations
            BASE76|performance-forms-scales|PER|PERFORMANCE|/hr/operations/performance/forms-scales
            BASE76|performance-multirater-surveys|PER|PERFORMANCE|/hr/operations/performance/multirater-surveys
            BASE76|performance-conversations-feedback|PER|PERFORMANCE|/hr/operations/performance/conversations-feedback
            BASE76|performance-calibration|PER|PERFORMANCE|/hr/operations/performance/calibration
            BASE76|performance-results|PER|PERFORMANCE|/hr/operations/performance/results
            BASE76|settings-enterprise|SYS|SETTINGS|/hr/settings/enterprise
            BASE76|settings-reference|SYS|SETTINGS|/hr/settings/reference-data
            BASE76|settings-people-employment|HRM|SETTINGS|/hr/settings/people-employment
            BASE76|settings-time|TIM|SETTINGS|/hr/settings/time
            BASE76|settings-leave|TIM|SETTINGS|/hr/settings/leave
            BASE76|settings-payroll|PAY|SETTINGS|/hr/settings/payroll
            BASE76|settings-statutory|PAY|SETTINGS|/hr/settings/statutory
            BASE76|settings-performance|PER|SETTINGS|/hr/settings/performance
            BASE76|settings-workflow|SYS|SETTINGS|/hr/settings/workflow
            BASE76|settings-documents-communications|HRM|SETTINGS|/hr/settings/documents-communications
            BASE76|settings-integrations|SYS|SETTINGS|/hr/settings/integrations
            BASE76|settings-extensions|SYS|SETTINGS|/hr/settings/extensions
            BASE76|settings-operations|SYS|SETTINGS|/hr/settings/operations
            BASE76|dwp-experience|SYS|SETTINGS|/admin/experience/branding?product=HCM
            BASE76|dwp-identity|SYS|SETTINGS|/admin/identity/app-governance?product=HCM
            BASE76|dwp-platform|SYS|SETTINGS|/admin/platform/catalog?product=HCM
            BASE76|dwp-integrations|SYS|SETTINGS|/admin/integrations/productivity?product=HCM
            BASE76|dwp-governance|SYS|SETTINGS|/admin/governance/audit-overview?product=HCM
            BASE76|dwp-extensibility|SYS|SETTINGS|/admin/platform/registry?namespace=hris-extension
            MODERN22|hcm.personal.growth|PER|MY_HR|/hr/me/growth
            MODERN22|hcm.settings.skills|PER|SETTINGS|/hr/settings/talent/skills
            MODERN22|hcm.operations.recruiting|HRM|HR_OPERATIONS|/hr/operations/recruiting
            MODERN22|hcm.personal.services|HRM|MY_HR|/hr/me/services
            MODERN22|hcm.operations.onboarding|HRM|HR_OPERATIONS|/hr/operations/onboarding
            MODERN22|hcm.personal.learning|PER|MY_HR|/hr/me/learning
            MODERN22|hcm.operations.learning|PER|PERFORMANCE|/hr/operations/learning
            MODERN22|hcm.personal.opportunities|PER|MY_HR|/hr/me/opportunities
            MODERN22|hcm.operations.opportunities|PER|PERFORMANCE|/hr/operations/opportunities
            MODERN22|hcm.operations.succession|PER|PERFORMANCE|/hr/operations/talent/succession
            MODERN22|hcm.operations.workforce-planning|HRM|HR_OPERATIONS|/hr/operations/workforce-planning
            MODERN22|hcm.operations.compensation-planning|PER|PERFORMANCE|/hr/operations/performance/compensation-planning
            MODERN22|hcm.personal.benefits|HRM|MY_HR|/hr/me/benefits
            MODERN22|hcm.operations.benefits|HRM|HR_OPERATIONS|/hr/operations/employee-services/benefits
            MODERN22|hcm.operations.hr-services|HRM|HR_OPERATIONS|/hr/operations/hr-services
            MODERN22|hcm.personal.listening|SYS|MY_HR|/hr/me/listening
            MODERN22|hcm.settings.listening|SYS|SETTINGS|/hr/settings/experience/listening
            MODERN22|hcm.operations.workforce-management|TIM|TIME|/hr/operations/workforce-management
            MODERN22|hcm.operations.contingent-workforce|HRM|HR_OPERATIONS|/hr/operations/contingent-workforce
            MODERN22|hcm.operations.people-analytics|SYS|HR_OPERATIONS|/hr/operations/people-analytics
            MODERN22|hcm.personal.assistant|SYS|MY_HR|/hr/me/assistant
            MODERN22|hcm.settings.ai-governance|SYS|SETTINGS|/hr/settings/ai-governance
            """;

    private static final List<CatalogNode> NODES = parse();
    private static final Map<String, CatalogNode> BY_KEY = NODES.stream().collect(
            Collectors.toUnmodifiableMap(CatalogNode::routeKey, Function.identity()));

    private HrisExplorerCatalog() {
    }

    public static List<CatalogNode> nodes() {
        return NODES;
    }

    public static CatalogNode node(String routeKey) {
        return BY_KEY.get(routeKey);
    }

    public static Set<String> routeKeys() {
        return BY_KEY.keySet();
    }

    private static List<CatalogNode> parse() {
        List<CatalogNode> nodes = ROWS.lines().filter(line -> !line.isBlank()).map(line -> {
            String[] fields = line.strip().split("\\|", -1);
            if (fields.length != 5) throw new IllegalStateException("invalid Explorer catalog row");
            String sourceNodeKey = fields[1];
            String normalized = sourceNodeKey.replace('.', '-');
            String path = fields[4];
            TargetKind kind = DWP_CONTROL_TARGETS.contains(path)
                    ? TargetKind.DWP_CONTROL : TargetKind.HRIS_INTERNAL;
            if (kind == TargetKind.HRIS_INTERNAL && !path.matches("^/hr(?:/|$).*$")) {
                throw new IllegalStateException("invalid HRIS target: " + path);
            }
            return new CatalogNode(
                    fields[0], sourceNodeKey, "hris.explorer." + normalized,
                    "hris.explorer.routes." + normalized, Module.valueOf(fields[2]),
                    Workbench.valueOf(fields[3]), new ExplorerTarget(kind, path));
        }).toList();
        Map<String, Long> sourceCounts = nodes.stream().collect(
                Collectors.groupingBy(CatalogNode::sourceSet, LinkedHashMap::new,
                        Collectors.counting()));
        if (nodes.size() != CANONICAL_COUNT
                || sourceCounts.getOrDefault("BASE76", 0L) != BASE76_COUNT
                || sourceCounts.getOrDefault("MODERN22", 0L) != MODERN22_COUNT
                || nodes.stream().map(CatalogNode::routeKey).distinct().count() != CANONICAL_COUNT
                || nodes.stream().map(CatalogNode::labelKey).distinct().count() != CANONICAL_COUNT
                || nodes.stream().map(CatalogNode::target).map(ExplorerTarget::path)
                .filter(DWP_CONTROL_TARGETS::contains).count() != 6) {
            throw new IllegalStateException("Explorer canonical catalog integrity failure");
        }
        return List.copyOf(nodes);
    }

    public record CatalogNode(
            String sourceSet,
            String sourceNodeKey,
            String routeKey,
            String labelKey,
            Module module,
            Workbench workbench,
            ExplorerTarget target) {
    }
}
