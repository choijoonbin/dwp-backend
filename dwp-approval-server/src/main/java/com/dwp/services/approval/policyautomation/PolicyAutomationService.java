package com.dwp.services.approval.policyautomation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;

@Service
public class PolicyAutomationService {
    private final PolicyAutomationRepository repository;
    private final PolicyChannelAttestationVerifier attestationVerifier;
    private final Clock clock;

    public PolicyAutomationService(
            PolicyAutomationRepository repository,
            PolicyChannelAttestationVerifier attestationVerifier,
            Clock clock) {
        this.repository = repository;
        this.attestationVerifier = attestationVerifier;
        this.clock = clock;
    }

    @Transactional
    public CalendarView saveCalendar(String idempotencyKey, CalendarDraft input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "SAVE_CALENDAR", input.calendarId(), input,
                CalendarView.class, () -> repository.saveCalendar(context, input));
    }

    @Transactional
    public ChannelView saveChannel(String idempotencyKey, ChannelDraft input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "SAVE_CHANNEL", input.channelId(), input,
                ChannelView.class, () -> repository.saveChannel(context, input));
    }

    @Transactional
    public ChannelView observeChannel(
            String idempotencyKey, UUID channelId, ChannelObservation input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "OBSERVE_CHANNEL", channelId, input,
                ChannelView.class, () -> {
                    String verificationReference = null;
                    if (input != null && input.readiness() == Readiness.READY) {
                        verificationReference = attestationVerifier.verify(
                                context, channelId, input);
                    }
                    return repository.observeChannel(
                            context, channelId, input, verificationReference);
                });
    }

    @Transactional
    public PolicyView savePolicyDraft(String idempotencyKey, PolicyDraft input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "SAVE_POLICY_DRAFT", input.policyId(), input,
                PolicyView.class, () -> repository.savePolicyDraft(context, input));
    }

    @Transactional
    public PolicyView publishPolicy(
            String idempotencyKey, UUID policyId, PublishCommand input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "PUBLISH_POLICY", policyId, input,
                PolicyView.class, () -> repository.publishPolicy(
                        context, policyId, input, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Instant addBusinessMinutes(UUID calendarId, Instant start, long minutes) {
        Context context = Context.current("read-" + UUID.randomUUID());
        return BusinessCalendarEngine.addBusinessMinutes(
                repository.requireCalendar(context, calendarId, false), start, minutes);
    }

    @Transactional(readOnly = true)
    public List<CalendarView> calendars() {
        return repository.calendars(readContext());
    }

    @Transactional(readOnly = true)
    public CalendarView calendar(UUID calendarId) {
        return repository.requireCalendar(readContext(), calendarId, false);
    }

    @Transactional(readOnly = true)
    public List<ChannelView> channels() {
        return repository.channels(readContext());
    }

    @Transactional(readOnly = true)
    public ChannelView channel(UUID channelId) {
        return repository.requireChannel(readContext(), channelId, false);
    }

    @Transactional(readOnly = true)
    public List<PolicyView> policies() {
        return repository.policies(readContext());
    }

    @Transactional(readOnly = true)
    public PolicyView policy(UUID policyId) {
        return repository.requirePolicy(readContext(), policyId, false);
    }

    private Context readContext() {
        return Context.current("read-" + UUID.randomUUID());
    }

    private <T> T idempotent(
            Context context,
            String operation,
            UUID target,
            Object input,
            Class<T> type,
            Supplier<T> command) {
        T prior = repository.prior(context, operation, target, input, type);
        if (prior != null) return prior;
        T result = command.get();
        repository.complete(context, operation, input, result);
        return result;
    }
}
