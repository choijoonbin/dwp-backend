package com.dwp.services.meeting.videomeeting.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMeetingExpectedVersionContractTest {

    private static final List<ExpectedVersionContract> CONTRACTS = List.of(
            new ExpectedVersionContract(
                    VideoMeetingDtos.AdmissionCommand.class, "expectedVersion"),
            new ExpectedVersionContract(
                    VideoMeetingIntelligenceDtos.CreateRunCommand.class,
                    "expectedContentPlanVersion"),
            new ExpectedVersionContract(
                    MeetingTranscriptArtifactDtos.FinalizeTranscriptCommand.class,
                    "expectedArtifactVersion"),
            new ExpectedVersionContract(
                    MeetingTranscriptArtifactDtos.FinalizeTranscriptCommand.class,
                    "expectedContentPlanVersion"),
            new ExpectedVersionContract(
                    MeetingRecordingArtifactDtos.FinalizeRecordingCommand.class,
                    "expectedArtifactVersion"),
            new ExpectedVersionContract(
                    MeetingRecordingArtifactDtos.FinalizeRecordingCommand.class,
                    "expectedContentPlanVersion"),
            new ExpectedVersionContract(
                    MeetingRecordingAccessDtos.AccessTicketCommand.class,
                    "expectedArtifactVersion"),
            new ExpectedVersionContract(
                    VideoMeetingIntelligenceDtos.GrantCommand.class,
                    "expectedReportVersion"),
            new ExpectedVersionContract(
                    MeetingTranscriptArtifactDtos.RegisterTranscriptCommand.class,
                    "expectedContentPlanVersion"),
            new ExpectedVersionContract(
                    VideoMeetingContentDtos.RequestRecordingCommand.class,
                    "expectedPlanVersion"),
            new ExpectedVersionContract(
                    VideoMeetingIntelligenceDtos.ReviewCommand.class, "expectedVersion"),
            new ExpectedVersionContract(
                    VideoMeetingContentDtos.StopRecordingCommand.class,
                    "expectedSessionVersion"),
            new ExpectedVersionContract(
                    VideoMeetingDtos.TenantPolicyUpdateRequest.class, "expectedVersion"),
            new ExpectedVersionContract(
                    VideoMeetingContentDtos.UpdateContentPlanCommand.class,
                    "expectedVersion"),
            new ExpectedVersionContract(
                    VideoMeetingIntelligenceDtos.VersionCommand.class, "expectedVersion"),
            new ExpectedVersionContract(
                    VideoMeetingDtos.VersionedCommand.class, "expectedVersion"));

    @Test
    void missingExpectedVersionsFailBeanValidationAndAreRequiredInOpenApiSchemas()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (ExpectedVersionContract contract : CONTRACTS) {
                Object missingVersion = mapper.readValue("{}", contract.commandType());
                assertThat(validator.validate(missingVersion))
                        .as("%s.%s validation", contract.commandType().getSimpleName(),
                                contract.property())
                        .anySatisfy(violation -> {
                            assertThat(violation.getPropertyPath().toString())
                                    .isEqualTo(contract.property());
                            assertThat(violation.getConstraintDescriptor()
                                            .getAnnotation().annotationType())
                                    .isEqualTo(NotNull.class);
                        });

                Schema<?> schema = ModelConverters.getInstance()
                        .read(contract.commandType())
                        .get(contract.commandType().getSimpleName());
                assertThat(schema)
                        .as("%s OpenAPI schema", contract.commandType().getSimpleName())
                        .isNotNull();
                assertThat(schema.getRequired())
                        .as("%s required properties", contract.commandType().getSimpleName())
                        .contains(contract.property());
            }
        }
    }

    private record ExpectedVersionContract(Class<?> commandType, String property) {
    }
}
