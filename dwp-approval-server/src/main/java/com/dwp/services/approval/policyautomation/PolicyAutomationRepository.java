package com.dwp.services.approval.policyautomation;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;

@Repository
public class PolicyAutomationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;

    public PolicyAutomationRepository(
            NamedParameterJdbcTemplate jdbc,
            ApprovalDocumentCanonical canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    <T> T prior(Context context, String operation, UUID target, Object input, Class<T> type) {
        requireActiveTenant(context);
        String digest = canonical.fingerprint(input);
        MapSqlParameterSource parameters = base(context).addValue("operation", operation)
                .addValue("target", target).addValue("digest", digest);
        advisoryLock(context, operation);
        int inserted = jdbc.update("""
                INSERT INTO apr_policy_automation_commands(
                    tenant_id,resource_set_key,actor_user_id,operation,
                    idempotency_key,target_id,command_sha256)
                VALUES(:tenant,:scope,:actor,:operation,:key,:target,:digest)
                ON CONFLICT DO NOTHING
                """, parameters);
        Command command = jdbc.query("""
                SELECT target_id,command_sha256,status,result_type,result_payload::text
                  FROM apr_policy_automation_commands
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND actor_user_id=:actor AND operation=:operation
                   AND idempotency_key=:key FOR UPDATE
                """, parameters, result -> {
            if (!result.next()) throw PolicyAutomationRejected.unavailable(
                    "Policy-automation command receipt is unavailable.");
            return new Command(result.getObject(1, UUID.class), result.getString(2),
                    result.getString(3), result.getString(4), result.getString(5));
        });
        if (!java.util.Objects.equals(target, command.target())
                || !digest.equals(command.digest())) {
            throw PolicyAutomationRejected.conflict(
                    "The idempotency key is bound to another policy-automation command.");
        }
        if (inserted == 1) return null;
        if (!"SUCCEEDED".equals(command.status()) || command.payload() == null
                || !type.getName().equals(command.resultType())) {
            throw PolicyAutomationRejected.unavailable(
                    "The policy-automation command outcome is unknown.");
        }
        return canonical.read(command.payload(), type);
    }

    void complete(Context context, String operation, Object input, Object result) {
        int updated = jdbc.update("""
                UPDATE apr_policy_automation_commands
                   SET status='SUCCEEDED',result_type=:type,
                       result_payload=CAST(:result AS jsonb),completed_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND actor_user_id=:actor AND operation=:operation
                   AND idempotency_key=:key AND command_sha256=:digest
                   AND status='UNKNOWN'
                """, base(context).addValue("operation", operation)
                .addValue("digest", canonical.fingerprint(input))
                .addValue("type", result.getClass().getName())
                .addValue("result", canonical.json(result)));
        if (updated != 1) throw PolicyAutomationRejected.conflict(
                "Policy-automation command completion lost its exact fence.");
    }

    CalendarView saveCalendar(Context context, CalendarDraft input) {
        BusinessCalendarEngine.validate(input);
        MapSqlParameterSource parameters = base(context)
                .addValue("id", input.calendarId())
                .addValue("calendarKey", input.calendarKey().trim().toUpperCase())
                .addValue("name", input.displayName().trim())
                .addValue("zone", input.timeZone())
                .addValue("week", canonical.json(input.workWeek()))
                .addValue("lifecycle", input.lifecycle().name())
                .addValue("expected", input.expectedVersion());
        try {
            int changed;
            if (input.expectedVersion() == 0) {
                changed = jdbc.update("""
                        INSERT INTO apr_business_calendars(
                            tenant_id,resource_set_key,calendar_id,calendar_key,display_name,
                            time_zone,work_week,lifecycle_state,created_by,updated_by)
                        VALUES(:tenant,:scope,:id,:calendarKey,:name,:zone,
                            CAST(:week AS jsonb),:lifecycle,:actor,:actor)
                        """, parameters);
            } else {
                changed = jdbc.update("""
                        UPDATE apr_business_calendars
                           SET calendar_key=:calendarKey,display_name=:name,time_zone=:zone,
                               work_week=CAST(:week AS jsonb),lifecycle_state=:lifecycle,
                               version=version+1,updated_by=:actor,updated_at=clock_timestamp()
                         WHERE tenant_id=:tenant AND resource_set_key=:scope
                           AND calendar_id=:id AND version=:expected
                           AND lifecycle_state<>'RETIRED'
                        """, parameters);
            }
            if (changed != 1) throw PolicyAutomationRejected.conflict(
                    "Business calendar version changed or cannot be edited.");
            jdbc.update("""
                    DELETE FROM apr_business_calendar_holidays
                     WHERE tenant_id=:tenant AND resource_set_key=:scope AND calendar_id=:id
                    """, parameters);
            jdbc.update("""
                    DELETE FROM apr_business_calendar_exceptions
                     WHERE tenant_id=:tenant AND resource_set_key=:scope AND calendar_id=:id
                    """, parameters);
            for (Holiday holiday : input.holidays()) {
                jdbc.update("""
                        INSERT INTO apr_business_calendar_holidays(
                            tenant_id,resource_set_key,calendar_id,holiday_date,label)
                        VALUES(:tenant,:scope,:id,:date,:label)
                        """, new MapSqlParameterSource(parameters.getValues())
                        .addValue("date", holiday.date()).addValue("label", holiday.label().trim()));
            }
            for (CalendarException exception : input.exceptions()) {
                jdbc.update("""
                        INSERT INTO apr_business_calendar_exceptions(
                            tenant_id,resource_set_key,calendar_id,exception_date,closed,
                            opens_at,closes_at,reason)
                        VALUES(:tenant,:scope,:id,:date,:closed,:opens,:closes,:reason)
                        """, new MapSqlParameterSource(parameters.getValues())
                        .addValue("date", exception.date()).addValue("closed", exception.closed())
                        .addValue("opens", exception.opensAt()).addValue("closes", exception.closesAt())
                        .addValue("reason", exception.reason().trim()));
            }
        } catch (DuplicateKeyException exception) {
            throw PolicyAutomationRejected.conflict(
                    "Business calendar key or date already exists in this scope.");
        }
        return requireCalendar(context, input.calendarId(), true);
    }

    ChannelView saveChannel(Context context, ChannelDraft input) {
        validateChannel(input);
        MapSqlParameterSource parameters = base(context)
                .addValue("id", input.channelId())
                .addValue("channelKey", input.channelKey().trim().toUpperCase())
                .addValue("type", input.channelType().name())
                .addValue("lifecycle", input.lifecycle().name())
                .addValue("expected", input.expectedVersion());
        try {
            int changed;
            if (input.expectedVersion() == 0) {
                changed = jdbc.update("""
                        INSERT INTO apr_notification_channels(
                            tenant_id,resource_set_key,channel_id,channel_key,channel_type,
                            lifecycle_state,created_by,updated_by)
                        VALUES(:tenant,:scope,:id,:channelKey,:type,:lifecycle,:actor,:actor)
                        """, parameters);
            } else {
                changed = jdbc.update("""
                        UPDATE apr_notification_channels
                           SET channel_key=:channelKey,channel_type=:type,
                               lifecycle_state=:lifecycle,readiness_state='NOT_CONFIGURED',
                               source_revision=NULL,evidence_sha256=NULL,observed_at=NULL,
                               valid_until=NULL,verification_reference=NULL,
                               version=version+1,updated_by=:actor,
                               updated_at=clock_timestamp()
                         WHERE tenant_id=:tenant AND resource_set_key=:scope
                           AND channel_id=:id AND version=:expected
                           AND lifecycle_state<>'RETIRED'
                        """, parameters);
            }
            if (changed != 1) throw PolicyAutomationRejected.conflict(
                    "Notification channel version changed or cannot be edited.");
        } catch (DuplicateKeyException exception) {
            throw PolicyAutomationRejected.conflict(
                    "Notification channel key already exists in this scope.");
        }
        return requireChannel(context, input.channelId(), true);
    }

    ChannelView observeChannel(
            Context context,
            UUID channelId,
            ChannelObservation input,
            String verificationReference) {
        validateObservation(input);
        if (input.readiness() == Readiness.READY
                && !verifiedReference(verificationReference)
                || input.readiness() != Readiness.READY && verificationReference != null) {
            throw PolicyAutomationRejected.forbidden(
                    "Notification-channel readiness lacks trusted attestation provenance.");
        }
        MapSqlParameterSource parameters = base(context)
                .addValue("id", channelId).addValue("observation", input.observationId())
                .addValue("readiness", input.readiness().name())
                .addValue("revision", input.sourceRevision().trim())
                .addValue("evidence", input.evidenceSha256())
                .addValue("observed", Timestamp.from(input.observedAt()))
                .addValue("valid", Timestamp.from(input.validUntil()))
                .addValue("verification", verificationReference)
                .addValue("expected", input.expectedVersion());
        int updated = jdbc.update("""
                UPDATE apr_notification_channels
                   SET readiness_state=:readiness,source_revision=:revision,
                       evidence_sha256=:evidence,observed_at=:observed,valid_until=:valid,
                       verification_reference=:verification,
                       version=version+1,updated_by=:actor,updated_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND channel_id=:id AND version=:expected
                   AND lifecycle_state<>'RETIRED'
                """, parameters);
        if (updated != 1) throw PolicyAutomationRejected.conflict(
                "Notification channel changed before readiness was observed.");
        jdbc.update("""
                INSERT INTO apr_notification_channel_observations(
                    tenant_id,resource_set_key,channel_id,observation_id,channel_version,
                    readiness_state,source_revision,evidence_sha256,observed_at,valid_until,
                    verification_reference,recorded_by)
                VALUES(:tenant,:scope,:id,:observation,:expected + 1,:readiness,
                    :revision,:evidence,:observed,:valid,:verification,:actor)
                """, parameters);
        return requireChannel(context, channelId, true);
    }

    PolicyView savePolicyDraft(Context context, PolicyDraft input) {
        validatePolicy(input);
        requireCalendar(context, input.calendarId(), false);
        UUID revisionId = UUID.randomUUID();
        String definitionSha = canonical.fingerprint(Map.of(
                "calendarId", input.calendarId(),
                "reminders", input.reminders(),
                "escalations", input.escalations(),
                "effectiveFrom", input.effectiveFrom(),
                "effectiveTo", input.effectiveTo() == null ? "" : input.effectiveTo()));
        List<UUID> channels = new ArrayList<>();
        input.reminders().forEach(item -> channels.add(item.channelId()));
        input.escalations().forEach(item -> channels.add(item.channelId()));
        List<UUID> channelIds = new LinkedHashSet<>(channels).stream().toList();
        MapSqlParameterSource parameters = base(context)
                .addValue("id", input.policyId()).addValue("revision", revisionId)
                .addValue("policyKey", input.policyKey().trim().toUpperCase())
                .addValue("name", input.displayName().trim())
                .addValue("calendar", input.calendarId())
                .addValue("reminders", canonical.json(input.reminders()))
                .addValue("escalations", canonical.json(input.escalations()))
                .addValue("channels", canonical.json(channelIds))
                .addValue("effectiveFrom", Timestamp.from(input.effectiveFrom()))
                .addValue("effectiveTo", timestamp(input.effectiveTo()))
                .addValue("sha", definitionSha).addValue("expected", input.expectedVersion());
        try {
            long revisionNumber;
            if (input.expectedVersion() == 0) {
                revisionNumber = 1;
                int inserted = jdbc.update("""
                        INSERT INTO apr_policy_automation_heads(
                            tenant_id,resource_set_key,policy_id,policy_key,display_name,
                            lifecycle_state,version,draft_revision_id,created_by,updated_by)
                        VALUES(:tenant,:scope,:id,:policyKey,:name,'DRAFT',1,:revision,:actor,:actor)
                        """, parameters);
                if (inserted != 1) throw PolicyAutomationRejected.conflict(
                        "Policy-automation draft could not be created.");
            } else {
                Long latest = jdbc.queryForObject("""
                        SELECT COALESCE(max(revision_number),0)
                          FROM apr_policy_automation_revisions
                         WHERE tenant_id=:tenant AND resource_set_key=:scope AND policy_id=:id
                        """, parameters, Long.class);
                revisionNumber = (latest == null ? 0 : latest) + 1;
                int updated = jdbc.update("""
                        UPDATE apr_policy_automation_heads
                           SET policy_key=:policyKey,display_name=:name,draft_revision_id=:revision,
                               version=version+1,updated_by=:actor,updated_at=clock_timestamp()
                         WHERE tenant_id=:tenant AND resource_set_key=:scope
                           AND policy_id=:id AND version=:expected
                           AND lifecycle_state<>'RETIRED'
                           AND NOT EXISTS (
                               SELECT 1 FROM apr_policy_automation_freezes policy_freeze
                                WHERE policy_freeze.tenant_id=:tenant
                                  AND policy_freeze.resource_set_key=:scope
                                  AND policy_freeze.policy_id=:id AND policy_freeze.active)
                        """, parameters);
                if (updated != 1) throw PolicyAutomationRejected.conflict(
                        "Policy-automation version changed or cannot be edited.");
            }
            jdbc.update("""
                    INSERT INTO apr_policy_automation_revisions(
                        tenant_id,resource_set_key,policy_id,revision_id,revision_number,
                        calendar_id,reminders,escalations,channel_ids,effective_from,effective_to,
                        definition_sha256,maker_user_id,maker_person_public_id,
                        editor_user_id,editor_person_public_id)
                    VALUES(:tenant,:scope,:id,:revision,:revisionNumber,:calendar,
                        CAST(:reminders AS jsonb),CAST(:escalations AS jsonb),
                        CAST(:channels AS jsonb),:effectiveFrom,:effectiveTo,:sha,
                        :actor,:person,:actor,:person)
                    """, parameters.addValue("revisionNumber", revisionNumber)
                    .addValue("person", context.actorPersonPublicId()));
        } catch (DuplicateKeyException exception) {
            throw PolicyAutomationRejected.conflict(
                    "Policy-automation key already exists in this scope.");
        }
        return requirePolicy(context, input.policyId(), true);
    }

    PolicyView publishPolicy(
            Context context,
            UUID policyId,
            PublishCommand input,
            Instant now) {
        if (input == null || input.revisionId() == null || input.expectedVersion() < 1
                || !digest(input.reviewEvidenceSha256())) {
            throw PolicyAutomationRejected.invalid("Policy publication command is invalid.");
        }
        PolicyView policy = requirePolicy(context, policyId, true);
        if (policy.version() != input.expectedVersion()
                || !input.revisionId().equals(policy.draftRevisionId())) {
            throw PolicyAutomationRejected.conflict(
                    "Policy draft or object version changed before publication.");
        }
        if (context.actorPersonPublicId().equals(policy.makerPersonPublicId())
                || context.actorPersonPublicId().equals(policy.editorPersonPublicId())) {
            throw PolicyAutomationRejected.forbidden(
                    "Policy publication requires an independent checker.");
        }
        Integer approvedReviews = jdbc.queryForObject("""
                SELECT count(*) FROM apr_policy_automation_reviews review
                 WHERE review.tenant_id=:tenant AND review.resource_set_key=:scope
                   AND review.policy_id=:id AND review.revision_id=:revision
                   AND review.disposition='APPROVED'
                   AND review.review_evidence_sha256=:reviewSha
                   AND review.reviewed_policy_version=:expected - 1
                   AND review.checker_person_public_id<>:makerPerson
                   AND review.checker_person_public_id<>:editorPerson
                   AND NOT EXISTS (
                       SELECT 1 FROM apr_policy_automation_freezes policy_freeze
                        WHERE policy_freeze.tenant_id=:tenant
                          AND policy_freeze.resource_set_key=:scope
                          AND policy_freeze.policy_id=:id AND policy_freeze.active)
                """, base(context).addValue("id", policyId)
                .addValue("revision", input.revisionId())
                .addValue("reviewSha", input.reviewEvidenceSha256())
                .addValue("expected", input.expectedVersion())
                .addValue("makerPerson", policy.makerPersonPublicId())
                .addValue("editorPerson", policy.editorPersonPublicId()), Integer.class);
        if (approvedReviews == null || approvedReviews != 1) {
            throw PolicyAutomationRejected.forbidden(
                    "Policy publication requires one current independent approved review.");
        }
        CalendarView calendar = requireCalendar(context, policy.calendarId(), false);
        if (calendar.lifecycle() != Lifecycle.ACTIVE) {
            throw PolicyAutomationRejected.unavailable(
                    "Published policy requires an active business calendar.");
        }
        for (UUID channelId : policyChannels(policy)) {
            ChannelView channel = requireChannel(context, channelId, false);
            if (channel.lifecycle() != Lifecycle.ACTIVE
                    || channel.readiness() != Readiness.READY
                    || channel.observedAt() == null || channel.observedAt().isAfter(now)
                    || channel.validUntil() == null || !channel.validUntil().isAfter(now)
                    || !hasTrustedReadiness(context, channelId)) {
                throw PolicyAutomationRejected.unavailable(
                        "Published policy requires currently verified notification channels.");
            }
        }
        for (Escalation escalation : policy.escalations()) {
            Integer resolvers = jdbc.queryForObject("""
                    SELECT count(*) FROM apr_routing_resolvers
                     WHERE tenant_id=:tenant AND resource_set_key=:scope
                       AND resolver_id=:resolver AND lifecycle_state='ACTIVE'
                       AND source_state='HEALTHY' AND source_observed_at<=:now
                       AND source_valid_until>:now
                    """, base(context).addValue("resolver", escalation.resolverId())
                    .addValue("now", Timestamp.from(now)), Integer.class);
            if (resolvers == null || resolvers != 1) {
                throw PolicyAutomationRejected.unavailable(
                        "Escalation resolver is not currently verified.");
            }
        }
        UUID publicationId = UUID.randomUUID();
        MapSqlParameterSource parameters = base(context).addValue("id", policyId)
                .addValue("revision", input.revisionId())
                .addValue("expected", input.expectedVersion())
                .addValue("publication", publicationId)
                .addValue("makerPerson", policy.makerPersonPublicId())
                .addValue("editorPerson", policy.editorPersonPublicId())
                .addValue("checkerPerson", context.actorPersonPublicId())
                .addValue("reviewSha", input.reviewEvidenceSha256());
        jdbc.update("""
                INSERT INTO apr_policy_automation_publications(
                    publication_id,tenant_id,resource_set_key,policy_id,revision_id,
                    maker_person_public_id,editor_person_public_id,checker_user_id,
                    checker_person_public_id,review_evidence_sha256)
                VALUES(:publication,:tenant,:scope,:id,:revision,:makerPerson,:editorPerson,
                    :actor,:checkerPerson,:reviewSha)
                """, parameters);
        int updated = jdbc.update("""
                UPDATE apr_policy_automation_heads
                   SET lifecycle_state='ACTIVE',published_revision_id=:revision,
                       draft_revision_id=NULL,version=version+1,updated_by=:actor,
                       updated_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND policy_id=:id AND version=:expected AND draft_revision_id=:revision
                """, parameters);
        if (updated != 1) throw PolicyAutomationRejected.conflict(
                "Policy version changed during publication.");
        return requirePolicy(context, policyId, true);
    }

    CalendarView requireCalendar(Context context, UUID calendarId, boolean lock) {
        List<CalendarView> rows = jdbc.query("""
                SELECT calendar_id,calendar_key,display_name,time_zone,work_week::text,
                       lifecycle_state,version
                  FROM apr_business_calendars
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND calendar_id=:id
                """ + (lock ? " FOR SHARE" : ""), base(context).addValue("id", calendarId),
                (row, number) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, WorkHours> week = canonical.read(row.getString(5), Map.class);
                    return new CalendarView(row.getObject(1, UUID.class), row.getString(2),
                            row.getString(3), row.getString(4), convertWeek(week),
                            holidays(context, calendarId), exceptions(context, calendarId),
                            Lifecycle.valueOf(row.getString(6)), row.getLong(7));
                });
        if (rows.size() != 1) throw PolicyAutomationRejected.unavailable(
                "Business calendar is unavailable in this management scope.");
        return rows.getFirst();
    }

    List<CalendarView> calendars(Context context) {
        requireActiveTenant(context);
        return jdbc.query("""
                SELECT calendar_id FROM apr_business_calendars
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                 ORDER BY calendar_key,calendar_id
                """, base(context), (row, number) -> row.getObject(1, UUID.class)).stream()
                .map(id -> requireCalendar(context, id, false)).toList();
    }

    ChannelView requireChannel(Context context, UUID channelId, boolean lock) {
        List<ChannelView> rows = jdbc.query("""
                SELECT channel_id,channel_key,channel_type,lifecycle_state,readiness_state,
                       source_revision,evidence_sha256,observed_at,valid_until,version
                  FROM apr_notification_channels
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND channel_id=:id
                """ + (lock ? " FOR SHARE" : ""), base(context).addValue("id", channelId),
                (row, number) -> new ChannelView(row.getObject(1, UUID.class), row.getString(2),
                        ChannelType.valueOf(row.getString(3)), Lifecycle.valueOf(row.getString(4)),
                        Readiness.valueOf(row.getString(5)), row.getString(6), row.getString(7),
                        instant(row.getTimestamp(8)), instant(row.getTimestamp(9)), row.getLong(10)));
        if (rows.size() != 1) throw PolicyAutomationRejected.unavailable(
                "Notification channel is unavailable in this management scope.");
        return rows.getFirst();
    }

    List<ChannelView> channels(Context context) {
        requireActiveTenant(context);
        return jdbc.query("""
                SELECT channel_id FROM apr_notification_channels
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                 ORDER BY channel_key,channel_id
                """, base(context), (row, number) -> row.getObject(1, UUID.class)).stream()
                .map(id -> requireChannel(context, id, false)).toList();
    }

    PolicyView requirePolicy(Context context, UUID policyId, boolean lock) {
        List<PolicyView> rows = jdbc.query("""
                SELECT head.policy_id,head.policy_key,head.display_name,head.lifecycle_state,
                       head.version,head.draft_revision_id,head.published_revision_id,
                       revision.calendar_id,revision.reminders::text,revision.escalations::text,
                       revision.effective_from,revision.effective_to,revision.definition_sha256,
                       revision.maker_user_id,revision.maker_person_public_id,
                       revision.editor_user_id,revision.editor_person_public_id
                  FROM apr_policy_automation_heads head
                  JOIN apr_policy_automation_revisions revision
                    ON revision.tenant_id=head.tenant_id
                   AND revision.resource_set_key=head.resource_set_key
                   AND revision.policy_id=head.policy_id
                   AND revision.revision_id=COALESCE(
                       head.draft_revision_id,head.published_revision_id)
                 WHERE head.tenant_id=:tenant AND head.resource_set_key=:scope
                   AND head.policy_id=:id
                """ + (lock ? " FOR UPDATE OF head" : ""),
                base(context).addValue("id", policyId), (row, number) -> new PolicyView(
                        row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                        Lifecycle.valueOf(row.getString(4)), row.getLong(5),
                        row.getObject(6, UUID.class), row.getObject(7, UUID.class),
                        row.getObject(8, UUID.class), readReminders(row.getString(9)),
                        readEscalations(row.getString(10)), instant(row.getTimestamp(11)),
                        instant(row.getTimestamp(12)), row.getString(13), row.getLong(14),
                        row.getObject(15, UUID.class), row.getLong(16),
                        row.getObject(17, UUID.class)));
        if (rows.size() != 1) throw PolicyAutomationRejected.unavailable(
                "Policy-automation definition is unavailable in this management scope.");
        return rows.getFirst();
    }

    List<PolicyView> policies(Context context) {
        requireActiveTenant(context);
        return jdbc.query("""
                SELECT policy_id FROM apr_policy_automation_heads
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                 ORDER BY policy_key,policy_id
                """, base(context), (row, number) -> row.getObject(1, UUID.class)).stream()
                .map(id -> requirePolicy(context, id, false)).toList();
    }

    private List<Holiday> holidays(Context context, UUID calendarId) {
        return jdbc.query("""
                SELECT holiday_date,label FROM apr_business_calendar_holidays
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND calendar_id=:id
                 ORDER BY holiday_date
                """, base(context).addValue("id", calendarId),
                (row, number) -> new Holiday(row.getDate(1).toLocalDate(), row.getString(2)));
    }

    private List<CalendarException> exceptions(Context context, UUID calendarId) {
        return jdbc.query("""
                SELECT exception_date,closed,opens_at,closes_at,reason
                  FROM apr_business_calendar_exceptions
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND calendar_id=:id
                 ORDER BY exception_date
                """, base(context).addValue("id", calendarId),
                (row, number) -> new CalendarException(row.getDate(1).toLocalDate(),
                        row.getBoolean(2), row.getTime(3) == null ? null : row.getTime(3).toLocalTime(),
                        row.getTime(4) == null ? null : row.getTime(4).toLocalTime(), row.getString(5)));
    }

    private void validateChannel(ChannelDraft input) {
        if (input == null || input.channelId() == null || input.channelType() == null
                || input.lifecycle() == null || blank(input.channelKey())
                || !input.channelKey().trim().toUpperCase().matches("[A-Z][A-Z0-9_.-]{2,99}")
                || input.expectedVersion() < 0) {
            throw PolicyAutomationRejected.invalid("Notification channel definition is invalid.");
        }
    }

    private void validateObservation(ChannelObservation input) {
        if (input == null || input.observationId() == null || input.readiness() == null
                || input.readiness() == Readiness.NOT_CONFIGURED
                || blank(input.sourceRevision()) || !digest(input.evidenceSha256())
                || input.observedAt() == null || input.validUntil() == null
                || !input.validUntil().isAfter(input.observedAt()) || input.expectedVersion() < 1) {
            throw PolicyAutomationRejected.invalid("Notification readiness observation is invalid.");
        }
    }

    private void validatePolicy(PolicyDraft input) {
        if (input == null || input.policyId() == null || input.calendarId() == null
                || blank(input.policyKey())
                || !input.policyKey().trim().toUpperCase().matches("[A-Z][A-Z0-9_.-]{2,99}")
                || blank(input.displayName()) || input.displayName().trim().length() > 200
                || input.reminders() == null || input.reminders().size() > 20
                || input.escalations() == null || input.escalations().size() > 20
                || input.effectiveFrom() == null
                || input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom())
                || input.expectedVersion() < 0) {
            throw PolicyAutomationRejected.invalid("Policy-automation draft is invalid.");
        }
        if (input.reminders().stream().map(Reminder::reminderKey).distinct().count()
                != input.reminders().size()
                || input.escalations().stream().map(Escalation::escalationKey).distinct().count()
                != input.escalations().size()) {
            throw PolicyAutomationRejected.invalid("Automation rule keys must be unique.");
        }
        input.reminders().forEach(reminder -> {
            if (reminder == null || blank(reminder.reminderKey()) || reminder.channelId() == null
                    || blank(reminder.templateKey()) || reminder.businessMinutesBefore() < 1
                    || reminder.businessMinutesBefore() > 525_600
                    || reminder.maximumDeliveries() < 1 || reminder.maximumDeliveries() > 20) {
                throw PolicyAutomationRejected.invalid("Reminder rule is invalid.");
            }
        });
        int previous = 0;
        for (Escalation escalation : input.escalations()) {
            if (escalation == null || blank(escalation.escalationKey())
                    || escalation.resolverId() == null || escalation.channelId() == null
                    || !List.of("NOTIFY", "REASSIGN", "ESCALATE", "CANCEL").contains(escalation.action())
                    || escalation.businessMinutesAfter() <= previous
                    || escalation.businessMinutesAfter() > 525_600
                    || escalation.maximumExecutions() < 1 || escalation.maximumExecutions() > 20) {
                throw PolicyAutomationRejected.invalid(
                        "Escalation rules must be valid and ordered by elapsed business time.");
            }
            previous = escalation.businessMinutesAfter();
        }
    }

    private List<UUID> policyChannels(PolicyView policy) {
        LinkedHashSet<UUID> values = new LinkedHashSet<>();
        policy.reminders().forEach(item -> values.add(item.channelId()));
        policy.escalations().forEach(item -> values.add(item.channelId()));
        return List.copyOf(values);
    }

    private List<Reminder> readReminders(String value) {
        return List.of(canonical.read(value, Reminder[].class));
    }

    private List<Escalation> readEscalations(String value) {
        return List.of(canonical.read(value, Escalation[].class));
    }

    private Map<String, WorkHours> convertWeek(Map<String, WorkHours> raw) {
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) (Map<?, ?>) raw;
        java.util.LinkedHashMap<String, WorkHours> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key,
                canonical.read(canonical.json(value), WorkHours.class)));
        return Map.copyOf(result);
    }

    private void requireActiveTenant(Context context) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM apr_tenants
                 WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                """, base(context), Integer.class);
        if (count == null || count != 1) throw PolicyAutomationRejected.forbidden(
                "Approval tenant is not active.");
    }

    private void advisoryLock(Context context, String operation) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:value,0))",
                Map.of("value", context.tenantId() + "|" + context.resourceSetKey()
                        + "|" + context.actorUserId() + "|" + operation + "|"
                        + context.idempotencyKey()), Object.class);
    }

    private MapSqlParameterSource base(Context context) {
        return new MapSqlParameterSource().addValue("tenant", context.tenantId())
                .addValue("scope", context.resourceSetKey())
                .addValue("actor", context.actorUserId())
                .addValue("key", context.idempotencyKey());
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean hasTrustedReadiness(Context context, UUID channelId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM apr_notification_channels
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND channel_id=:id
                   AND readiness_state='READY'
                   AND verification_reference ~ '^verified:[0-9a-f]{64}$'
                """, base(context).addValue("id", channelId), Integer.class);
        return count != null && count == 1;
    }

    private static boolean verifiedReference(String value) {
        return value != null && value.matches("verified:[0-9a-f]{64}");
    }

    private record Command(
            UUID target, String digest, String status, String resultType, String payload) {
    }
}
