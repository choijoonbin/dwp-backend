package com.dwp.services.platform.home.overview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.calendar.CalendarDtos;
import com.dwp.services.platform.calendar.CalendarService;
import com.dwp.services.platform.communication.CommunicationDtos;
import com.dwp.services.platform.communication.CommunicationService;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import com.dwp.services.platform.workspace.WorkspaceService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class HomeOverviewService {

    private static final Logger log = LoggerFactory.getLogger(HomeOverviewService.class);
    private static final String RULE_VERSION = "home-rules-2026.08";
    private static final Set<String> OPERATOR_ROLES = Set.of(
            "ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN", "PROVIDER_ADMIN");
    private static final Map<String, String> RECOMMENDATION_SOURCES = Map.of(
            "work-due-soon", "DWP_WORKSPACE",
            "calendar-conflicts", "DWP_CALENDAR",
            "calendar-responses", "DWP_CALENDAR",
            "required-communications", "DWP_COMMUNICATIONS",
            "focus-capacity", "DWP_CALENDAR");

    private final WorkspaceService workspaceService;
    private final CalendarService calendarService;
    private final CommunicationService communicationService;
    private final HomeRecommendationFeedbackRepository feedbackRepository;
    private final PlatformAuditService auditService;
    private final MeterRegistry meterRegistry;

    public HomeOverviewService(
            WorkspaceService workspaceService,
            CalendarService calendarService,
            CommunicationService communicationService,
            HomeRecommendationFeedbackRepository feedbackRepository,
            PlatformAuditService auditService,
            MeterRegistry meterRegistry) {
        this.workspaceService = workspaceService;
        this.calendarService = calendarService;
        this.communicationService = communicationService;
        this.feedbackRepository = feedbackRepository;
        this.auditService = auditService;
        this.meterRegistry = meterRegistry;
    }

    public HomeOverviewDtos.HomeOverviewResponse overview(
            Long tenantId,
            Long userId,
            UUID personPublicId,
            String permissions,
            String roles,
            String locale,
            String timeZone) {
        HomeOverviewDtos.AudienceContext audience = audience(roles);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            HomeOverviewDtos.Section<WorkspaceDtos.WorkQueue> work = section(
                    "DWP_WORKSPACE",
                    () -> workspaceService.workQueue(tenantId, userId, permissions, locale),
                    WorkspaceDtos.WorkQueue::generatedAt,
                    tenantId,
                    userId);
            HomeOverviewDtos.Section<CalendarDtos.HomeResponse> calendar = section(
                    "DWP_CALENDAR",
                    () -> calendarService.home(
                            tenantId, userId, personPublicId, timeZone, locale),
                    CalendarDtos.HomeResponse::generatedAt,
                    tenantId,
                    userId);
            HomeOverviewDtos.Section<CommunicationDtos.FeedResponse> communications = section(
                    "DWP_COMMUNICATIONS",
                    () -> communicationService.feed(
                            tenantId, userId, roles, locale, "for-you", null, null, 8),
                    CommunicationDtos.FeedResponse::generatedAt,
                    tenantId,
                    userId);
            HomeOverviewDtos.Section<WorkspaceDtos.ActivityFeed> activity = section(
                    "DWP_ACTIVITY",
                    () -> workspaceService.activity(tenantId, userId, permissions, locale),
                    WorkspaceDtos.ActivityFeed::generatedAt,
                    tenantId,
                    userId);
            HomeOverviewDtos.Section<List<HomeOverviewDtos.Recommendation>> recommendationSection =
                    recommendationSection(
                            tenantId, userId, work, calendar, communications, locale);
            List<HomeOverviewDtos.Recommendation> recommendations =
                    recommendationSection.data() == null
                            ? List.of()
                            : recommendationSection.data();

            return new HomeOverviewDtos.HomeOverviewResponse(
                    audience,
                    work,
                    calendar,
                    communications,
                    activity,
                    recommendations,
                    recommendationSection,
                    OffsetDateTime.now(ZoneOffset.UTC));
        } finally {
            sample.stop(Timer.builder("dwp.home.overview.duration")
                    .description("Time to compose the permission-scoped personal home overview")
                    .tag("audience", audience.profile())
                    .register(meterRegistry));
        }
    }

    public HomeOverviewDtos.RecommendationFeedbackResponse recordFeedback(
            Long tenantId,
            Long userId,
            String recommendationKey,
            String correlationId,
            HomeOverviewDtos.RecommendationFeedbackRequest request) {
        String source = RECOMMENDATION_SOURCES.get(recommendationKey);
        if (source == null) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Unknown home recommendation key");
        }
        feedbackRepository.save(
                tenantId,
                userId,
                recommendationKey,
                request.feedbackType(),
                source,
                RULE_VERSION);
        auditService.success(
                tenantId,
                userId,
                "home.recommendation.feedback.recorded",
                "HOME_RECOMMENDATION",
                recommendationKey,
                correlationId,
                Map.of(),
                Map.of(
                        "feedbackType", request.feedbackType(),
                        "source", source,
                        "ruleVersion", RULE_VERSION));
        meterRegistry.counter(
                "dwp.home.recommendation.feedback",
                "type", request.feedbackType()).increment();
        return new HomeOverviewDtos.RecommendationFeedbackResponse(
                recommendationKey,
                request.feedbackType(),
                RULE_VERSION,
                OffsetDateTime.now(ZoneOffset.UTC));
    }

    private <T> HomeOverviewDtos.Section<T> section(
            String source,
            Supplier<T> supplier,
            Function<T, OffsetDateTime> generatedAt,
            Long tenantId,
            Long userId) {
        try {
            T data = supplier.get();
            return new HomeOverviewDtos.Section<>(
                    HomeOverviewDtos.SectionStatus.AVAILABLE,
                    source,
                    generatedAt.apply(data),
                    data,
                    null);
        } catch (BaseException exception) {
            HomeOverviewDtos.SectionStatus status = exception.getErrorCode() == ErrorCode.FORBIDDEN
                    ? HomeOverviewDtos.SectionStatus.FORBIDDEN
                    : HomeOverviewDtos.SectionStatus.UNAVAILABLE;
            log.info("Home section {} degraded for tenant {} and user {}: {}",
                    source, tenantId, userId, exception.getErrorCode().getCode());
            meterRegistry.counter(
                    "dwp.home.section.degraded",
                    "source", source,
                    "status", status.name()).increment();
            return unavailable(source, status, exception.getErrorCode().getCode());
        } catch (RuntimeException exception) {
            log.warn("Home section {} failed for tenant {} and user {}",
                    source, tenantId, userId, exception);
            meterRegistry.counter(
                    "dwp.home.section.degraded",
                    "source", source,
                    "status", HomeOverviewDtos.SectionStatus.UNAVAILABLE.name()).increment();
            return unavailable(source, HomeOverviewDtos.SectionStatus.UNAVAILABLE, "E1000");
        }
    }

    private <T> HomeOverviewDtos.Section<T> unavailable(
            String source,
            HomeOverviewDtos.SectionStatus status,
            String reason) {
        return new HomeOverviewDtos.Section<>(status, source, OffsetDateTime.now(ZoneOffset.UTC), null, reason);
    }

    private HomeOverviewDtos.AudienceContext audience(String rolesHeader) {
        Set<String> roles = values(rolesHeader);
        if (roles.stream().anyMatch(OPERATOR_ROLES::contains)) {
            return new HomeOverviewDtos.AudienceContext(
                    "OPERATOR", RULE_VERSION, List.of("CONTROL_PLANE_RESPONSIBILITY"));
        }
        if (roles.contains("MANAGER") || roles.contains("PEOPLE_MANAGER")) {
            return new HomeOverviewDtos.AudienceContext(
                    "MANAGER", RULE_VERSION, List.of("PEOPLE_LEADERSHIP_RESPONSIBILITY"));
        }
        return new HomeOverviewDtos.AudienceContext(
                "MEMBER", RULE_VERSION, List.of("AUTHENTICATED_WORKFORCE_MEMBER"));
    }

    private HomeOverviewDtos.Section<List<HomeOverviewDtos.Recommendation>> recommendationSection(
            Long tenantId,
            Long userId,
            HomeOverviewDtos.Section<WorkspaceDtos.WorkQueue> work,
            HomeOverviewDtos.Section<CalendarDtos.HomeResponse> calendar,
            HomeOverviewDtos.Section<CommunicationDtos.FeedResponse> communications,
            String locale) {
        List<HomeOverviewDtos.Section<?>> sources = List.of(work, calendar, communications);
        boolean hasAvailableSource = sources.stream()
                .anyMatch(section -> section.status() == HomeOverviewDtos.SectionStatus.AVAILABLE
                        && section.data() != null);
        if (!hasAvailableSource) {
            boolean allForbidden = sources.stream()
                    .allMatch(section -> section.status() == HomeOverviewDtos.SectionStatus.FORBIDDEN);
            return unavailable(
                    "DWP_HOME_RECOMMENDATIONS",
                    allForbidden
                            ? HomeOverviewDtos.SectionStatus.FORBIDDEN
                            : HomeOverviewDtos.SectionStatus.UNAVAILABLE,
                    allForbidden ? "SOURCE_FORBIDDEN" : "SOURCE_UNAVAILABLE");
        }

        try {
            List<HomeOverviewDtos.Recommendation> data = recommendations(
                    tenantId, userId, work, calendar, communications, locale);
            boolean partiallyUnavailable = sources.stream()
                    .anyMatch(section -> section.status() == HomeOverviewDtos.SectionStatus.UNAVAILABLE);
            return new HomeOverviewDtos.Section<>(
                    HomeOverviewDtos.SectionStatus.AVAILABLE,
                    "DWP_HOME_RECOMMENDATIONS",
                    OffsetDateTime.now(ZoneOffset.UTC),
                    data,
                    partiallyUnavailable ? "PARTIAL_SOURCE_UNAVAILABLE" : null);
        } catch (RuntimeException exception) {
            log.warn("Home recommendations failed for tenant {} and user {}", tenantId, userId, exception);
            meterRegistry.counter(
                    "dwp.home.section.degraded",
                    "source", "DWP_HOME_RECOMMENDATIONS",
                    "status", HomeOverviewDtos.SectionStatus.UNAVAILABLE.name()).increment();
            return unavailable(
                    "DWP_HOME_RECOMMENDATIONS",
                    HomeOverviewDtos.SectionStatus.UNAVAILABLE,
                    "E1000");
        }
    }

    private List<HomeOverviewDtos.Recommendation> recommendations(
            Long tenantId,
            Long userId,
            HomeOverviewDtos.Section<WorkspaceDtos.WorkQueue> work,
            HomeOverviewDtos.Section<CalendarDtos.HomeResponse> calendar,
            HomeOverviewDtos.Section<CommunicationDtos.FeedResponse> communications,
            String locale) {
        boolean korean = locale == null || locale.toLowerCase(Locale.ROOT).startsWith("ko");
        List<HomeOverviewDtos.Recommendation> result = new ArrayList<>();
        if (work.data() != null && work.data().summary().dueSoon() > 0) {
            int count = Math.toIntExact(Math.min(Integer.MAX_VALUE, work.data().summary().dueSoon()));
            result.add(new HomeOverviewDtos.Recommendation(
                    "work-due-soon", "ACTION", "HIGH",
                    korean ? "마감이 가까운 업무를 먼저 확인하세요" : "Review work approaching its deadline",
                    korean ? "개인 업무 큐에서 마감 임박 항목이 확인되었습니다."
                            : "Your personal work queue contains time-sensitive items.",
                    "/work", "DWP_WORKSPACE", count, "HIGH"));
        }
        if (calendar.data() != null && calendar.data().metrics().conflictCount() > 0) {
            int count = calendar.data().metrics().conflictCount();
            result.add(new HomeOverviewDtos.Recommendation(
                    "calendar-conflicts", "SCHEDULE", "HIGH",
                    korean ? "겹친 일정을 조정하세요" : "Resolve schedule conflicts",
                    korean ? "이번 주 일정에 충돌이 감지되었습니다."
                            : "Conflicts were detected in this week's schedule.",
                    "/calendar/schedule", "DWP_CALENDAR", count, "HIGH"));
        } else if (calendar.data() != null && calendar.data().metrics().awaitingResponseCount() > 0) {
            int count = calendar.data().metrics().awaitingResponseCount();
            result.add(new HomeOverviewDtos.Recommendation(
                    "calendar-responses", "SCHEDULE", "MEDIUM",
                    korean ? "대기 중인 일정 초대에 응답하세요" : "Respond to pending invitations",
                    korean ? "응답이 필요한 일정 초대가 있습니다."
                            : "Calendar invitations are waiting for your response.",
                    "/calendar/schedule", "DWP_CALENDAR", count, "HIGH"));
        }
        if (communications.data() != null && communications.data().summary().required() > 0) {
            int count = Math.toIntExact(Math.min(
                    Integer.MAX_VALUE, communications.data().summary().required()));
            result.add(new HomeOverviewDtos.Recommendation(
                    "required-communications", "COMMUNICATION", "MEDIUM",
                    korean ? "필수 공지를 확인하세요" : "Review required communications",
                    korean ? "확인 또는 동의가 필요한 조직 공지가 있습니다."
                            : "Organization communications require acknowledgement.",
                    "/communications/required", "DWP_COMMUNICATIONS", count, "HIGH"));
        }
        if (result.isEmpty() && calendar.data() != null
                && calendar.data().metrics().focusMinutes()
                < calendar.data().metrics().focusTargetMinutes()) {
            int remaining = Math.max(0,
                    calendar.data().metrics().focusTargetMinutes()
                            - calendar.data().metrics().focusMinutes());
            result.add(new HomeOverviewDtos.Recommendation(
                    "focus-capacity", "FOCUS", "LOW",
                    korean ? "집중 시간을 확보할 여지가 있습니다" : "There is room for focused work",
                    korean ? "주간 집중 목표까지 남은 시간을 일정에 확보할 수 있습니다."
                            : "You can reserve time toward your weekly focus target.",
                    "/calendar/schedule", "DWP_CALENDAR", remaining, "MEDIUM"));
        }
        Set<String> suppressedKeys = suppressedKeys(tenantId, userId);
        return result.stream()
                .filter(recommendation -> !suppressedKeys.contains(recommendation.key()))
                .toList();
    }

    private Set<String> suppressedKeys(Long tenantId, Long userId) {
        try {
            return feedbackRepository.suppressedKeys(tenantId, userId);
        } catch (RuntimeException exception) {
            log.warn("Home recommendation feedback could not be loaded for tenant {} and user {}",
                    tenantId, userId, exception);
            meterRegistry.counter("dwp.home.recommendation.feedback.load.failure").increment();
            return Set.of();
        }
    }

    private Set<String> values(String header) {
        if (header == null || header.isBlank()) return Set.of();
        return Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
