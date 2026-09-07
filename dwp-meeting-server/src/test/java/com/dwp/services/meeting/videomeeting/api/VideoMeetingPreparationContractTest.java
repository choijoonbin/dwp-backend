package com.dwp.services.meeting.videomeeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMeetingPreparationContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void agendaAndResponseVersionsAreRequiredRatherThanImplicitZero() throws Exception {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var validator = validation.getValidator();
            var agenda = mapper.readValue("{\"items\":[]}", VideoMeetingPreparationDtos.ReplaceAgendaRequest.class);
            assertThat(validator.validate(agenda)).anyMatch(violation ->
                    violation.getPropertyPath().toString().equals("expectedAgendaVersion"));
            var response = mapper.readValue("{\"response\":\"ACCEPTED\"}",
                    VideoMeetingPreparationDtos.InvitationResponseRequest.class);
            assertThat(validator.validate(response)).extracting(violation -> violation.getPropertyPath().toString())
                    .contains("expectedInvitationRevision", "expectedVersion");
        }
        Schema<?> agenda = ModelConverters.getInstance().read(VideoMeetingPreparationDtos.ReplaceAgendaRequest.class)
                .get("ReplaceAgendaRequest");
        Schema<?> response = ModelConverters.getInstance().read(VideoMeetingPreparationDtos.InvitationResponseRequest.class)
                .get("InvitationResponseRequest");
        assertThat(agenda.getRequired()).contains("expectedAgendaVersion", "items");
        assertThat(response.getRequired()).contains("expectedInvitationRevision", "expectedVersion", "response");
    }

    @Test
    void existingCreationJsonRemainsCompatibleWithoutStructuredAgendaOrTemplateTrace() throws Exception {
        var request = mapper.readValue("""
                {"title":"Legacy meeting","accessScope":"INVITED","participantUserIds":[],"guestInvitees":[]}
                """, VideoMeetingDtos.InstantMeetingRequest.class);
        assertThat(request.agendaItems()).isNull();
        assertThat(request.sourceTemplateId()).isNull();
        assertThat(request.sourceTemplateVersion()).isNull();
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void agendaNestedValidationRejectsBlankTitlesAndInvalidOwnerOrDuration() {
        var item = new VideoMeetingPreparationDtos.AgendaItemInput(null, " ", null, -1L, 0);
        var request = new VideoMeetingPreparationDtos.ReplaceAgendaRequest(0L, List.of(item));
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(request)).extracting(violation ->
                    violation.getPropertyPath().toString()).contains("items[0].title", "items[0].ownerUserId", "items[0].plannedMinutes");
        }
    }

    @Test
    void rsvpCannotSubmitAdmissionOrConsentState() {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            for (String response : List.of("ADMITTED", "JOINED", "CONSENTED", "RECONFIRM_REQUIRED")) {
                assertThat(validation.getValidator().validate(
                        new VideoMeetingPreparationDtos.InvitationResponseRequest(1L, 0L, response)))
                        .anyMatch(violation -> violation.getPropertyPath().toString().equals("response"));
            }
        }
    }

    @Test
    void materialAccessRequiresAnExplicitCurrentRowVersion() throws Exception {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var request = mapper.readValue("{}",
                    VideoMeetingPreparationDtos.MaterialAccessRequest.class);
            assertThat(validation.getValidator().validate(request))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("expectedVersion");
        }
        Schema<?> access = ModelConverters.getInstance()
                .read(VideoMeetingPreparationDtos.MaterialAccessRequest.class)
                .get("MaterialAccessRequest");
        assertThat(access.getRequired()).contains("expectedVersion");
    }
}
