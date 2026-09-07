package com.dwp.services.meeting.videomeeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingScheduleDraftContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void partialDraftAllowsIncompleteMeetingFieldsButBoundsPersistedContent() throws Exception {
        var request = mapper.readValue("""
                {
                  "expectedVersion": null,
                  "title": "",
                  "agenda": "",
                  "participantUserIds": [],
                  "agendaItems": [],
                  "recurrence": {"frequency":"NONE","interval":1,"occurrenceCount":4},
                  "lastStep": "DETAILS"
                }
                """, MeetingScheduleDraftDtos.SaveScheduleDraftRequest.class);
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(request)).isEmpty();
            var invalid = mapper.readValue("""
                    {
                      "title": "%s",
                      "recurrence": {"frequency":"DAILY","interval":1,"occurrenceCount":4}
                    }
                    """.formatted("x".repeat(241)),
                    MeetingScheduleDraftDtos.SaveScheduleDraftRequest.class);
            assertThat(validation.getValidator().validate(invalid))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("title", "recurrence.frequency");
        }
    }

    @Test
    void commitAndDiscardRequireExplicitCasVersions() throws Exception {
        try (var validation = Validation.buildDefaultValidatorFactory()) {
            var commit = mapper.readValue("{}",
                    MeetingScheduleDraftDtos.CommitScheduleDraftRequest.class);
            var discard = mapper.readValue("{}",
                    MeetingScheduleDraftDtos.DraftVersionRequest.class);
            assertThat(validation.getValidator().validate(commit))
                    .extracting(value -> value.getPropertyPath().toString())
                    .containsExactly("expectedVersion");
            assertThat(validation.getValidator().validate(discard))
                    .extracting(value -> value.getPropertyPath().toString())
                    .containsExactly("expectedVersion");
        }
        var schema = ModelConverters.getInstance()
                .read(MeetingScheduleDraftDtos.CommitScheduleDraftRequest.class)
                .get("CommitScheduleDraftRequest");
        assertThat(stringList(schema.getRequired())).contains("expectedVersion");
    }

    @Test
    void draftContractCannotAcceptGuestDeviceConsentOrTenantOwnershipFields() {
        Set<String> fields = stringSet(ModelConverters.getInstance()
                .read(MeetingScheduleDraftDtos.SaveScheduleDraftRequest.class)
                .get("SaveScheduleDraftRequest").getProperties().keySet());
        assertThat(fields).doesNotContain(
                "tenantId", "ownerUserId", "guestInvitees", "guestEmail",
                "deviceId", "microphoneId", "cameraId", "consent", "accessToken",
                "retentionUntil", "recordingConfigured");
    }

    private java.util.List<String> stringList(java.util.Collection<?> values) {
        return values.stream().map(Object::toString).toList();
    }

    private Set<String> stringSet(java.util.Collection<?> values) {
        return values.stream().map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }
}
