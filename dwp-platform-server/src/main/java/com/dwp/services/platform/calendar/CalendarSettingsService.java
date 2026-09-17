package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSettingsDtos.*;

@Service
public class CalendarSettingsService {

    private static final int DEFAULT_WORKING_DAYS_MASK = 31;
    private static final Set<SettingKey> POLICY_BACKED = Set.of(
            SettingKey.WORKING_DAY_START,
            SettingKey.WORKING_DAY_END,
            SettingKey.WEEK_START,
            SettingKey.DEFAULT_EVENT_MINUTES,
            SettingKey.DEFAULT_BUFFER_MINUTES);

    private final CalendarSettingsRepository repository;
    private final PlatformAuditService audit;
    private final Clock clock;

    @Autowired
    public CalendarSettingsService(
            CalendarSettingsRepository repository,
            PlatformAuditService audit) {
        this(repository, audit, Clock.systemUTC());
    }

    CalendarSettingsService(
            CalendarSettingsRepository repository,
            PlatformAuditService audit,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Settings settings(CalendarSettingsAccess.Actor actor) {
        requireActor(actor);
        CalendarSettingsRepository.PolicyRow policy = repository.policy(actor.tenantId())
                .orElse(null);
        CalendarSettingsRepository.SettingsRow row = repository.settings(actor)
                .orElseGet(() -> defaultRow(policy));
        return settings(row, policy);
    }

    @Transactional
    public Settings updateSettings(
            CalendarSettingsAccess.Actor actor,
            String correlationId,
            UpdateSettingsRequest request) {
        requireActor(actor);
        CalendarSettingsRepository.SettingsWrite write = validate(request);
        CalendarSettingsRepository.PolicyRow policy = repository.policy(actor.tenantId())
                .orElse(null);
        CalendarSettingsRepository.SettingsRow current = repository.settings(actor)
                .orElse(null);
        long expected = request.version();
        if ((current == null && expected != 0L)
                || (current != null && current.version() != expected)) {
            throw versionConflict();
        }
        Settings before = settings(current == null ? defaultRow(policy) : current, policy);
        CalendarSettingsRepository.SettingsRow saved = repository.saveSettings(
                        actor, write, expected)
                .orElseThrow(CalendarSettingsService::versionConflict);
        Settings after = settings(saved, policy);
        audit.success(
                actor.tenantId(), actor.userId(), "calendar.settings.updated",
                "CALENDAR_SETTINGS", actor.personPublicId().toString(), correlationId,
                before, after);
        return after;
    }

    @Transactional
    public Settings resetSettings(
            CalendarSettingsAccess.Actor actor,
            String correlationId,
            ResetSettingsRequest request) {
        requireActor(actor);
        if (request == null || request.version() == null || request.version() < 0) {
            throw invalid("The current Calendar settings version is required.");
        }
        CalendarSettingsRepository.PolicyRow policy = repository.policy(actor.tenantId())
                .orElse(null);
        CalendarSettingsRepository.SettingsWrite baseline = baseline(policy);
        repository.lockSettings(actor, baseline);
        CalendarSettingsRepository.SettingsRow current = repository.settings(actor)
                .orElseThrow(CalendarSettingsAccess::denied);
        if (current.version() != request.version()) throw versionConflict();
        Settings before = settings(current, policy);
        CalendarSettingsRepository.SettingsRow saved = repository.saveSettings(
                        actor, baseline, current.version())
                .orElseThrow(CalendarSettingsService::versionConflict);
        Settings after = settings(saved, policy);
        audit.success(
                actor.tenantId(), actor.userId(), "calendar.settings.reset",
                "CALENDAR_SETTINGS", actor.personPublicId().toString(), correlationId,
                before, after);
        return after;
    }

    @Transactional(readOnly = true)
    public List<Delegation> delegations(CalendarSettingsAccess.Actor actor) {
        requireActor(actor);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return repository.delegations(actor).stream()
                .map(row -> delegation(row, now))
                .toList();
    }

    @Transactional
    public Delegation createDelegation(
            CalendarSettingsAccess.Actor actor,
            String correlationId,
            CreateDelegationRequest request) {
        requireActor(actor);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<DelegationScope> scopes = validateDelegation(actor, request, now);
        repository.lockSettings(actor, baseline(
                repository.policy(actor.tenantId()).orElse(null)));
        if (repository.hasOverlappingDelegation(
                actor, request.delegatePersonPublicId(),
                request.validFrom(), request.validUntil(), now)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An active Calendar delegation already overlaps this validity period.");
        }
        UUID delegationId = UUID.randomUUID();
        CalendarSettingsRepository.DelegationRow saved = repository.createDelegation(
                actor,
                delegationId,
                request.delegatePersonPublicId(),
                scopes.contains(DelegationScope.RESPOND),
                scopes.contains(DelegationScope.EDIT_SCHEDULE),
                scopes.contains(DelegationScope.CREATE),
                request.validFrom(),
                request.validUntil());
        Delegation result = delegation(saved, now);
        audit.success(
                actor.tenantId(), actor.userId(), "calendar.delegation.created",
                "CALENDAR_DELEGATION", delegationId.toString(), correlationId,
                null, result);
        return result;
    }

    @Transactional
    public Delegation revokeDelegation(
            CalendarSettingsAccess.Actor actor,
            UUID delegationId,
            String correlationId,
            RevokeDelegationRequest request) {
        requireActor(actor);
        if (delegationId == null || request == null
                || request.version() == null || request.version() < 0) {
            throw invalid("The Calendar delegation and current version are required.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        repository.lockSettings(actor, baseline(
                repository.policy(actor.tenantId()).orElse(null)));
        CalendarSettingsRepository.DelegationRow current = repository
                .delegation(actor, delegationId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Delegation before = delegation(current, now);
        if (before.status() == DelegationStatus.REVOKED) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The Calendar delegation is already revoked.");
        }
        if (before.status() == DelegationStatus.EXPIRED) {
            throw new BaseException(ErrorCode.INVALID_STATE, "An expired Calendar delegation cannot be revoked.");
        }
        if (current.version() != request.version()
                || !repository.revokeDelegation(
                actor, delegationId, request.version(), now)) {
            throw versionConflict();
        }
        CalendarSettingsRepository.DelegationRow saved = repository
                .delegation(actor, delegationId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Delegation after = delegation(saved, now);
        audit.success(
                actor.tenantId(), actor.userId(), "calendar.delegation.revoked",
                "CALENDAR_DELEGATION", delegationId.toString(), correlationId,
                before, after);
        return after;
    }

    private void requireActor(CalendarSettingsAccess.Actor actor) {
        if (actor == null || actor.tenantId() <= 0 || actor.userId() <= 0
                || actor.personPublicId() == null || !repository.matchesIdentity(actor)) {
            throw CalendarSettingsAccess.denied();
        }
    }

    private CalendarSettingsRepository.SettingsWrite validate(UpdateSettingsRequest request) {
        if (request == null || request.workingDays() == null || request.workingDays().isEmpty()
                || request.workingDays().stream().anyMatch(java.util.Objects::isNull)
                || request.workingDayStart() == null || request.workingDayEnd() == null
                || request.timeZone() == null || request.weekStart() == null
                || request.speedyMeetingMode() == null || request.defaultVisibility() == null
                || request.version() == null) {
            throw invalid("All Calendar setting values are required.");
        }
        EnumSet<DayOfWeek> days = EnumSet.copyOf(request.workingDays());
        if (days.size() != request.workingDays().size()) {
            throw invalid("Calendar working days must be unique.");
        }
        if (!canonicalMinute(request.workingDayStart())
                || !canonicalMinute(request.workingDayEnd())
                || !request.workingDayEnd().isAfter(request.workingDayStart())) {
            throw invalid("Calendar working hours must be a valid minute-aligned period.");
        }
        String timeZone = request.timeZone().trim();
        if (!timeZone.equals(request.timeZone())
                || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
            throw invalid("The Calendar time zone must be a canonical IANA zone.");
        }
        if (request.defaultEventMinutes() < 5 || request.defaultEventMinutes() > 1440
                || request.defaultBufferMinutes() < 0 || request.defaultBufferMinutes() > 120
                || request.defaultReminderMinutes() < 0
                || request.defaultReminderMinutes() > 10080
                || request.version() < 0) {
            throw invalid("A Calendar scheduling default is outside its supported range.");
        }
        return new CalendarSettingsRepository.SettingsWrite(
                workingDaysMask(days),
                request.workingDayStart(),
                request.workingDayEnd(),
                timeZone,
                request.weekStart().getValue(),
                request.defaultEventMinutes(),
                request.speedyMeetingMode().name(),
                request.defaultBufferMinutes(),
                request.defaultVisibility().name(),
                request.defaultReminderMinutes(),
                SettingSource.USER.name());
    }

    private List<DelegationScope> validateDelegation(
            CalendarSettingsAccess.Actor actor,
            CreateDelegationRequest request,
            OffsetDateTime now) {
        if (request == null || request.delegatePersonPublicId() == null
                || request.scopes() == null || request.scopes().isEmpty()
                || request.scopes().stream().anyMatch(java.util.Objects::isNull)
                || request.validFrom() == null || request.validUntil() == null) {
            throw invalid("Delegate, scope, and validity are required.");
        }
        if (actor.personPublicId().equals(request.delegatePersonPublicId())) {
            throw invalid("A Calendar owner cannot delegate to themselves.");
        }
        if (!repository.personExists(actor.tenantId(), request.delegatePersonPublicId())) {
            throw new BaseException(ErrorCode.NOT_FOUND, "The Calendar delegate was not found in this tenant.");
        }
        EnumSet<DelegationScope> scopes = EnumSet.copyOf(request.scopes());
        if (scopes.size() != request.scopes().size()) {
            throw invalid("Calendar delegation scopes must be unique.");
        }
        if (!request.validUntil().isAfter(request.validFrom())
                || !request.validUntil().isAfter(now)) {
            throw invalid("The Calendar delegation validity must end in the future after it starts.");
        }
        return scopes.stream().toList();
    }

    private Settings settings(
            CalendarSettingsRepository.SettingsRow row,
            CalendarSettingsRepository.PolicyRow policy) {
        row = effectiveRow(row, policy);
        boolean policyPresent = policy != null;
        boolean userOverride = SettingSource.USER.name().equals(row.settingsOrigin());
        return new Settings(
                workingDays(row.workingDaysMask()),
                row.workingDayStart(),
                row.workingDayEnd(),
                row.timeZone(),
                DayOfWeek.of(row.weekStart()),
                row.defaultEventMinutes(),
                SpeedyMeetingMode.valueOf(row.speedyMeetingMode()),
                row.defaultBufferMinutes(),
                DefaultVisibility.valueOf(row.defaultVisibility()),
                row.defaultReminderMinutes(),
                governance(userOverride, policyPresent),
                row.version(),
                row.updatedAt());
    }

    private CalendarSettingsRepository.SettingsRow effectiveRow(
            CalendarSettingsRepository.SettingsRow row,
            CalendarSettingsRepository.PolicyRow policy) {
        if (SettingSource.USER.name().equals(row.settingsOrigin())) return row;
        CalendarSettingsRepository.SettingsWrite inherited = baseline(policy);
        return new CalendarSettingsRepository.SettingsRow(
                inherited.workingDaysMask(), inherited.workingDayStart(), inherited.workingDayEnd(),
                inherited.timeZone(), inherited.weekStart(), inherited.defaultEventMinutes(),
                inherited.speedyMeetingMode(), inherited.defaultBufferMinutes(),
                inherited.defaultVisibility(), inherited.defaultReminderMinutes(),
                row.settingsOrigin(), row.version(), row.updatedAt());
    }

    private List<SettingGovernance> governance(
            boolean userOverride,
            boolean policyPresent) {
        List<SettingGovernance> result = new ArrayList<>();
        for (SettingKey key : SettingKey.values()) {
            boolean managed = policyPresent && POLICY_BACKED.contains(key);
            SettingSource source = userOverride
                    ? SettingSource.USER
                    : managed ? SettingSource.TENANT_POLICY : SettingSource.SYSTEM_DEFAULT;
            result.add(new SettingGovernance(
                    key, source, managed, !userOverride, false));
        }
        return List.copyOf(result);
    }

    private Delegation delegation(
            CalendarSettingsRepository.DelegationRow row,
            OffsetDateTime now) {
        List<DelegationScope> scopes = new ArrayList<>();
        if (row.canRespond()) scopes.add(DelegationScope.RESPOND);
        if (row.canEditSchedule()) scopes.add(DelegationScope.EDIT_SCHEDULE);
        if (row.canCreate()) scopes.add(DelegationScope.CREATE);
        DelegationStatus status;
        if ("REVOKED".equals(row.status())) status = DelegationStatus.REVOKED;
        else if (!row.validUntil().isAfter(now)) status = DelegationStatus.EXPIRED;
        else if (row.validFrom().isAfter(now)) status = DelegationStatus.SCHEDULED;
        else status = DelegationStatus.ACTIVE;
        return new Delegation(
                row.delegationId(), row.ownerPersonPublicId(), row.delegatePersonPublicId(),
                scopes, row.validFrom(), row.validUntil(), status, row.version(),
                row.createdAt(), row.updatedAt());
    }

    private CalendarSettingsRepository.SettingsWrite baseline(
            CalendarSettingsRepository.PolicyRow policy) {
        return new CalendarSettingsRepository.SettingsWrite(
                DEFAULT_WORKING_DAYS_MASK,
                policy == null ? LocalTime.of(9, 0) : policy.workingDayStart(),
                policy == null ? LocalTime.of(18, 0) : policy.workingDayEnd(),
                "Asia/Seoul",
                policy == null ? DayOfWeek.MONDAY.getValue() : policy.weekStart(),
                policy == null ? 30 : policy.defaultEventMinutes(),
                SpeedyMeetingMode.FIVE_TEN.name(),
                policy == null ? 5 : policy.defaultBufferMinutes(),
                DefaultVisibility.FREE_BUSY.name(),
                10,
                SettingSource.TENANT_POLICY.name());
    }

    private CalendarSettingsRepository.SettingsRow defaultRow(
            CalendarSettingsRepository.PolicyRow policy) {
        CalendarSettingsRepository.SettingsWrite value = baseline(policy);
        return new CalendarSettingsRepository.SettingsRow(
                value.workingDaysMask(), value.workingDayStart(), value.workingDayEnd(),
                value.timeZone(), value.weekStart(), value.defaultEventMinutes(),
                value.speedyMeetingMode(), value.defaultBufferMinutes(),
                value.defaultVisibility(), value.defaultReminderMinutes(),
                value.settingsOrigin(), 0, null);
    }

    private static List<DayOfWeek> workingDays(int mask) {
        List<DayOfWeek> result = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            if ((mask & (1 << (day.getValue() - 1))) != 0) result.add(day);
        }
        return List.copyOf(result);
    }

    private static int workingDaysMask(Set<DayOfWeek> days) {
        int mask = 0;
        for (DayOfWeek day : days) mask |= 1 << (day.getValue() - 1);
        return mask;
    }

    private static boolean canonicalMinute(LocalTime value) {
        return value.getSecond() == 0 && value.getNano() == 0;
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "Calendar settings changed. Refresh and try again.");
    }
}
