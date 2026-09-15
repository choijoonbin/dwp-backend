package com.dwp.services.platform.home;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Canonical metadata and placement contract for the approved Home launchpad. */
public final class ApprovedHomeApplicationCatalog {
    public static final String VIEW_PERMISSION = "VIEW";

    private static final List<Application> APPLICATIONS = List.of(
            app("dwp-work", "APP.WORK", "work", 10, "업무", "Work",
                    "/work", "work", null, "core.workspace", "DWP Platform", "PRODUCTIVITY", "NATIVE", "HEALTHY"),
            app("dwp-ask", "APP.ASK", "work", 20, "DWAI·ON", "DWAI·ON",
                    "/dwaion", "ask", null, "ai.agent-runtime", "DWP AI Platform", "KNOWLEDGE", "NATIVE", "MANAGED"),
            app("dwp-activity", "APP.ACTIVITY", "work", 30, "활동", "Activity",
                    "/activity", "activity", null, "core.workspace", "DWP Platform", "PRODUCTIVITY", "NATIVE", "HEALTHY"),
            app("dwp-approvals", "APP.APPROVALS", "work", 40, "전자결재", "Approvals",
                    "/approvals/home", "approvals", "approvals", "core.approvals", "DWP Decision Hub", "BUSINESS", "NATIVE", "HEALTHY"),
            app("dwp-notifications", "APP.NOTIFICATIONS", "work", 50, "알림", "Notifications",
                    "/notifications/home", "notifications", "notifications", "core.workspace", "DWP Platform", "PRODUCTIVITY", "NATIVE", "HEALTHY"),

            app("dwp-communications", "APP.COMMUNICATIONS", "connect", 10, "소식", "Newsroom",
                    "/communications", "communications", "communications", "core.workspace", "DWP Communications", "PRODUCTIVITY", "NATIVE", "HEALTHY"),
            app("dwp-calendar", "APP.CALENDAR", "connect", 20, "캘린더", "Calendar",
                    "/calendar/home", "calendar", null, "core.workspace", "DWP Workplace", "PRODUCTIVITY", "NATIVE", "HEALTHY"),
            app("ref-app-mail", "APP.MAIL", "connect", 30, "메일", "Mail",
                    "/mail/home", "mail", null, "core.workspace", "DWP Workplace", "PRODUCTIVITY", "NATIVE", "HEALTHY"),
            app("dwp-spaces", "APP.SPACES", "connect", 40, "Space", "Spaces",
                    "/spaces/home", "spaces", "space", "core.spaces", "DWP Collaboration Platform", "PRODUCTIVITY", "NATIVE", "HEALTHY"),
            app("dwp-rooms", "APP.WORKPLACE", "connect", 50, "근무 공간", "Workplace",
                    "/workplace/home", "rooms", null, "core.workspace", "DWP Workplace", "PRODUCTIVITY", "NATIVE", "HEALTHY"),
            app("dwp-messaging", "APP.MESSAGING", "connect", 60, "메신저", "Messaging",
                    "/messages/home", "messaging", "messaging", "core.workspace", "DWP Collaboration Platform", "PRODUCTIVITY", "NATIVE", "HEALTHY"),
            app("dwp-meetings", "APP.MEETINGS", "connect", 70, "화상회의", "Meetings",
                    "/meetings/home", "meetings", null, "core.workspace", "DWP Meeting Platform", "PRODUCTIVITY", "NATIVE", "HEALTHY"),

            app("ref-app-service", "APP.EMPLOYEE_SERVICES", "services", 10, "서비스", "Services",
                    "/services", "services", null, "core.workspace", "Shared Services", "SERVICE", "NATIVE", "HEALTHY"),
            app("ref-app-people", "APP.HCM", "services", 20, "인사", "HR",
                    "/hr", "hcm", "hcm", "core.people", "DWP HCM", "PEOPLE", "NATIVE", "HEALTHY"),

            app("ref-app-knowledge", "APP.KNOWLEDGE", "systems", 10, "지식", "Knowledge",
                    "/apps?app=ref-app-knowledge", "knowledge", null, "core.workspace", "Knowledge Office", "KNOWLEDGE", "DEEP_LINK", "CONFIGURATION_REQUIRED"),
            app("ref-app-erp", "APP.BUSINESS_ERP", "systems", 20, "ERP", "Business ERP",
                    "/apps?app=ref-app-erp", "erp", null, "core.workspace", "Finance Platform", "BUSINESS", "DEEP_LINK", "CONFIGURATION_REQUIRED"),
            app("ref-app-legacy", "APP.LEGACY_OPERATIONS", "systems", 30, "레거시", "Legacy operations",
                    "/apps?app=ref-app-legacy", "legacy", null, "core.workspace", "Enterprise Systems", "LEGACY", "DEEP_LINK", "CONFIGURATION_REQUIRED"),
            app("dwp-admin", "APP.ADMINISTRATION", "systems", 40, "관리", "Administration",
                    "/admin", "admin", null, "core.workspace", "DWP Platform", "BUSINESS", "NATIVE", "HEALTHY"));

    private static final Map<String, String> RESOURCE_ALIASES = Map.of(
            "APP.MAIL_CALENDAR", "APP.MAIL",
            "APP.COLLABORATION", "APP.MESSAGING",
            "APP.ROOMS", "APP.WORKPLACE",
            "APP.HRIS", "APP.HCM");
    private static final Map<String, Application> BY_RESOURCE = index();

    private ApprovedHomeApplicationCatalog() {
    }

    public static List<Application> applications() {
        return APPLICATIONS;
    }

    public static String canonicalResourceKey(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return RESOURCE_ALIASES.getOrDefault(normalized, normalized);
    }

    public static Optional<Application> findByResourceKey(String value) {
        return Optional.ofNullable(BY_RESOURCE.get(canonicalResourceKey(value)));
    }

    private static Map<String, Application> index() {
        Map<String, Application> result = new LinkedHashMap<>();
        APPLICATIONS.forEach(application -> result.put(application.resourceKey(), application));
        return Map.copyOf(result);
    }

    private static Application app(
            String appKey, String resourceKey, String groupKey, int sortOrder,
            String nameKo, String nameEn, String launchTarget, String iconKey,
            String badgeSourceKey, String requiredEntitlement, String owner,
            String category, String launchMode, String healthState) {
        return new Application(
                appKey, resourceKey, groupKey, sortOrder, nameKo, nameEn,
                launchTarget, iconKey, VIEW_PERMISSION, badgeSourceKey,
                requiredEntitlement, owner, category, launchMode, healthState);
    }

    public record Application(
            String appKey,
            String resourceKey,
            String groupKey,
            int sortOrder,
            String nameKo,
            String nameEn,
            String launchTarget,
            String iconKey,
            String requiredPermissionCode,
            String badgeSourceKey,
            String requiredEntitlement,
            String owner,
            String category,
            String launchMode,
            String healthState) {
    }
}
