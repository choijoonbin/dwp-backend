package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class HomeLaunchpadPolicy {

    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_GROUPS = 8;
    private static final int MAX_PLACEMENTS = 200;
    private static final Pattern GROUP_KEY = Pattern.compile("^[a-z][a-z0-9-]{1,39}$");
    private static final Pattern RESOURCE_KEY = Pattern.compile("^[A-Z][A-Z0-9_.-]{2,119}$");
    private static final Pattern LOCALE_KEY =
            Pattern.compile("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$");

    public HomeExperienceDtos.HomeLaunchpadConfiguration defaultConfiguration() {
        return new HomeExperienceDtos.HomeLaunchpadConfiguration(
                SCHEMA_VERSION,
                List.of(
                        group(
                                "work",
                                "업무 시작",
                                "Start work",
                                "우선순위와 AI 지원 실행",
                                "Priorities and AI-assisted action",
                                10),
                        group(
                                "connect",
                                "소통과 협업",
                                "Connect and collaborate",
                                "커뮤니케이션과 공동 작업",
                                "Communication and shared work",
                                20),
                        group(
                                "services",
                                "구성원과 서비스",
                                "People and services",
                                "임직원 지원 및 인물 정보",
                                "Employee support and people information",
                                30),
                        group(
                                "systems",
                                "시스템과 통제",
                                "Systems and control",
                                "지식, 업무 도구 및 거버넌스",
                                "Knowledge, business tools, and governance",
                                40)),
                List.of(
                        placement("APP.WORK", "work", 10),
                        placement("APP.ASK", "work", 20),
                        placement("APP.ACTIVITY", "work", 30),
                        placement("APP.APPROVALS", "work", 40),
                        placement("APP.COMMUNICATIONS", "connect", 10),
                        placement("APP.CALENDAR", "connect", 20),
                        placement("APP.MAIL", "connect", 30),
                        placement("APP.COLLABORATION", "connect", 40),
                        placement("APP.SPACES", "connect", 45),
                        placement("APP.EMPLOYEE_SERVICES", "services", 10),
                        placement("APP.HCM", "services", 20),
                        placement("APP.KNOWLEDGE", "systems", 10),
                        placement("APP.BUSINESS_ERP", "systems", 20),
                        placement("APP.LEGACY_OPERATIONS", "systems", 30),
                        placement("APP.ADMINISTRATION", "systems", 40)));
    }

    public HomeExperienceDtos.HomeLaunchpadConfiguration normalize(
            HomeExperienceDtos.HomeLaunchpadConfiguration requested) {
        if (requested == null || requested.schemaVersion() == null
                || requested.schemaVersion() != SCHEMA_VERSION) {
            throw invalid("Unsupported home launchpad schema version.");
        }
        if (requested.groups() == null
                || requested.groups().isEmpty()
                || requested.groups().size() > MAX_GROUPS) {
            throw invalid("Home launchpad must contain between 1 and 8 groups.");
        }

        Set<String> groupKeys = new LinkedHashSet<>();
        List<HomeExperienceDtos.HomeLaunchpadGroup> groups = new ArrayList<>();
        for (HomeExperienceDtos.HomeLaunchpadGroup group : requested.groups()) {
            if (group == null || group.groupKey() == null
                    || !GROUP_KEY.matcher(group.groupKey()).matches()
                    || !groupKeys.add(group.groupKey())) {
                throw invalid("Home launchpad group keys must be unique and URL-safe.");
            }
            Map<String, String> labels = localized(group.labels(), true, 80);
            Map<String, String> descriptions = localized(group.descriptions(), false, 200);
            int sortOrder = boundedOrder(group.sortOrder());
            groups.add(new HomeExperienceDtos.HomeLaunchpadGroup(
                    group.groupKey(), labels, descriptions, sortOrder,
                    !Boolean.FALSE.equals(group.enabled())));
        }
        groups.sort(Comparator
                .comparingInt((HomeExperienceDtos.HomeLaunchpadGroup value) -> value.sortOrder())
                .thenComparing(HomeExperienceDtos.HomeLaunchpadGroup::groupKey));
        if (groups.stream().noneMatch(value -> Boolean.TRUE.equals(value.enabled()))) {
            throw invalid("At least one home launchpad group must remain enabled.");
        }
        Set<String> enabledGroupKeys = new LinkedHashSet<>();
        groups.stream()
                .filter(value -> Boolean.TRUE.equals(value.enabled()))
                .map(HomeExperienceDtos.HomeLaunchpadGroup::groupKey)
                .forEach(enabledGroupKeys::add);

        List<HomeExperienceDtos.HomeAppPlacement> requestedPlacements =
                requested.placements() == null ? List.of() : requested.placements();
        if (requestedPlacements.size() > MAX_PLACEMENTS) {
            throw invalid("Home launchpad app placement limit exceeded.");
        }
        Set<String> resources = new LinkedHashSet<>();
        List<HomeExperienceDtos.HomeAppPlacement> placements = new ArrayList<>();
        for (HomeExperienceDtos.HomeAppPlacement placement : requestedPlacements) {
            if (placement == null || placement.resourceKey() == null) {
                throw invalid("Home launchpad app placement is incomplete.");
            }
            String resourceKey = canonicalResourceKey(
                    placement.resourceKey().trim().toUpperCase(Locale.ROOT));
            if (!RESOURCE_KEY.matcher(resourceKey).matches() || !resources.add(resourceKey)) {
                throw invalid("Home launchpad app resources must be unique and valid.");
            }
            if (!groupKeys.contains(placement.groupKey())) {
                throw invalid("Home launchpad app placement references an unknown group.");
            }
            if (!enabledGroupKeys.contains(placement.groupKey())) {
                throw invalid("Move apps out of a home launchpad group before disabling it.");
            }
            placements.add(new HomeExperienceDtos.HomeAppPlacement(
                    resourceKey, placement.groupKey(), boundedOrder(placement.sortOrder())));
        }
        Map<String, Integer> groupOrder = new LinkedHashMap<>();
        for (int index = 0; index < groups.size(); index++) {
            groupOrder.put(groups.get(index).groupKey(), index);
        }
        placements.sort(Comparator
                .comparingInt((HomeExperienceDtos.HomeAppPlacement value) ->
                        groupOrder.get(value.groupKey()))
                .thenComparingInt(HomeExperienceDtos.HomeAppPlacement::sortOrder)
                .thenComparing(HomeExperienceDtos.HomeAppPlacement::resourceKey));

        return new HomeExperienceDtos.HomeLaunchpadConfiguration(
                SCHEMA_VERSION, List.copyOf(groups), List.copyOf(placements));
    }

    private Map<String, String> localized(
            Map<String, String> values,
            boolean required,
            int maxLength) {
        if (values == null || values.isEmpty()) {
            if (required) throw invalid("Localized home launchpad labels are required.");
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((locale, value) -> {
            if (locale == null || !LOCALE_KEY.matcher(locale).matches()) {
                throw invalid("Home launchpad locale key is invalid.");
            }
            String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
            if (text.length() > maxLength || (required && text.isBlank())) {
                throw invalid("Localized home launchpad text is invalid.");
            }
            if (!text.isBlank()) normalized.put(locale.toLowerCase(Locale.ROOT), text);
        });
        if (required && (!normalized.containsKey("ko") || !normalized.containsKey("en"))) {
            throw invalid("Korean and English home launchpad labels are required.");
        }
        return Map.copyOf(normalized);
    }

    private int boundedOrder(Integer value) {
        if (value == null || value < 0 || value > 10_000) {
            throw invalid("Home launchpad sort order must be between 0 and 10000.");
        }
        return value;
    }

    private String canonicalResourceKey(String resourceKey) {
        return "APP.HRIS".equals(resourceKey) ? "APP.HCM" : resourceKey;
    }

    private HomeExperienceDtos.HomeLaunchpadGroup group(
            String key,
            String ko,
            String en,
            String descriptionKo,
            String descriptionEn,
            int sortOrder) {
        return new HomeExperienceDtos.HomeLaunchpadGroup(
                key,
                Map.of("ko", ko, "en", en),
                Map.of("ko", descriptionKo, "en", descriptionEn),
                sortOrder,
                true);
    }

    private HomeExperienceDtos.HomeAppPlacement placement(
            String resourceKey,
            String groupKey,
            int sortOrder) {
        return new HomeExperienceDtos.HomeAppPlacement(resourceKey, groupKey, sortOrder);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
