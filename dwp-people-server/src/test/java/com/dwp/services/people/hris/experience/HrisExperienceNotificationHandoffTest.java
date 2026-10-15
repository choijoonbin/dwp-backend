package com.dwp.services.people.hris.experience;

import com.dwp.contracts.hris.xcon.v1.Xcon022EmployeeChangeAppliedV1;
import com.dwp.platform.contracts.hris.generated.HrisNotificationIntentV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrisExperienceNotificationHandoffTest {

    private static final long TENANT = 77L;
    private static final long ACTOR = 19L;
    private static final long RECIPIENT = 23L;
    private static final UUID PERSON = UUID.fromString(
            "00000000-0000-4000-8000-000000000077");
    private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");

    private final HrisExperienceNotificationRepository repository =
            mock(HrisExperienceNotificationRepository.class);
    private final HrisExperienceRecipientAuthority recipients =
            mock(HrisExperienceRecipientAuthority.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void defaultOffDoesNotTouchDurableState() {
        var handoff = new HrisExperienceNotificationHandoff(
                false, repository, recipients, mapper);

        assertThat(handoff.onApplied(fact(), PERSON, ACTOR))
                .isEqualTo(HrisExperienceNotificationHandoff.Outcome.FEATURE_DISABLED);
        verify(repository, never()).exactOriginExists(anyLong(), any(), any());
    }

    @Test
    void absentAuthoritativeMappingCreatesOnlyAuditedOmission() {
        var handoff = enabled();
        arrangeOrigin();
        when(recipients.resolve(TENANT, PERSON, NOW)).thenReturn(Optional.empty());

        assertThat(handoff.onApplied(fact(), PERSON, ACTOR)).isEqualTo(
                HrisExperienceNotificationHandoff.Outcome.OMITTED_NO_ELIGIBLE_RECIPIENT);
        verify(repository).appendOmission(any(),
                org.mockito.ArgumentMatchers.eq("NO_AUTHORITATIVE_USER_MAPPING"));
        verify(repository, never()).appendReady(any(), any(), any());
    }

    @Test
    void sameRequestActorIsExcludedRatherThanObservedAsRecipient() {
        var handoff = enabled();
        arrangeOrigin();
        when(recipients.resolve(TENANT, PERSON, NOW)).thenReturn(Optional.of(
                new HrisExperienceRecipientAuthority.VerifiedRecipient(
                        TENANT, PERSON, ACTOR, "a".repeat(64), NOW.plusSeconds(60))));

        assertThat(handoff.onApplied(fact(), PERSON, ACTOR)).isEqualTo(
                HrisExperienceNotificationHandoff.Outcome.OMITTED_ACTOR_EXCLUDED);
        verify(repository).appendOmission(any(),
                org.mockito.ArgumentMatchers.eq("ACTOR_EXCLUDED"));
        verify(repository, never()).appendReady(any(), any(), any());
    }

    @Test
    void verifiedNonActorProducesPrivacyMinimizedTypedIntent() {
        var handoff = enabled();
        arrangeOrigin();
        when(recipients.resolve(TENANT, PERSON, NOW)).thenReturn(Optional.of(
                new HrisExperienceRecipientAuthority.VerifiedRecipient(
                        TENANT, PERSON, RECIPIENT, "a".repeat(64), NOW.plusSeconds(60))));

        assertThat(handoff.onApplied(fact(), PERSON, ACTOR)).isEqualTo(
                HrisExperienceNotificationHandoff.Outcome.READY_LOCAL_ONLY);

        ArgumentCaptor<HrisNotificationIntentV1> intent =
                ArgumentCaptor.forClass(HrisNotificationIntentV1.class);
        ArgumentCaptor<String> digest = ArgumentCaptor.forClass(String.class);
        verify(repository).appendReady(any(), intent.capture(), digest.capture());
        assertThat(intent.getValue().producerAppKey()).isEqualTo("hcm");
        assertThat(intent.getValue().typeKey()).isEqualTo("HCM.EMPLOYEE_CHANGE_APPLIED");
        assertThat(intent.getValue().recipientUserIds()).containsExactly(RECIPIENT);
        assertThat(intent.getValue().excludedUserIds()).containsExactly(ACTOR);
        assertThat(intent.getValue().actorRef()).isNull();
        assertThat(intent.getValue().templateKey())
                .isEqualTo("hcm.employee-change.applied");
        assertThat(digest.getValue()).matches("[0-9a-f]{64}");
    }

    private HrisExperienceNotificationHandoff enabled() {
        return new HrisExperienceNotificationHandoff(
                true, repository, recipients, mapper);
    }

    private void arrangeOrigin() {
        var fact = fact();
        when(repository.exactOriginExists(TENANT, fact.eventId(), fact.requestPublicId()))
                .thenReturn(true);
        when(repository.activeSubject(TENANT, PERSON)).thenReturn(Optional.of(
                new HrisExperienceNotificationRepository.Subject(PERSON, "ko-KR")));
    }

    private Xcon022EmployeeChangeAppliedV1 fact() {
        UUID request = UUID.fromString("00000000-0000-4000-8000-000000000081");
        return new Xcon022EmployeeChangeAppliedV1(
                request, 4L, 8L,
                UUID.fromString("00000000-0000-4000-8000-000000000082"),
                List.of("PERSON_NAME"),
                UUID.fromString("00000000-0000-4000-8000-000000000083"),
                LocalDate.of(2026, 10, 15),
                UUID.fromString("00000000-0000-4000-8000-000000000084"),
                NOW, "b".repeat(64), request, 1,
                UUID.fromString("00000000-0000-4000-8000-000000000085"),
                "PERSON", TENANT);
    }
}
