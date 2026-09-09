package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceReportExportService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingIntelligenceReportExportControllerContractTest {

    @Test
    void exposesOneVersionBoundPostDownloadRoute() throws Exception {
        RequestMapping root = MeetingIntelligenceReportExportController.class
                .getAnnotation(RequestMapping.class);
        Method method = MeetingIntelligenceReportExportController.class.getDeclaredMethod(
                "export", UUID.class, UUID.class,
                MeetingIntelligenceReportExportDtos.ExportCommand.class, String.class);

        assertThat(root.value())
                .containsExactly("/v1/meetings/{meetingId}/intelligence/reports");
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/{reportId}/exports");
        assertThat(method.getAnnotation(PostMapping.class).produces())
                .containsExactly("application/json", "text/markdown");
        assertThat(MeetingIntelligenceReportExportService.class
                .getDeclaredMethod(
                        "export", UUID.class, UUID.class,
                        MeetingIntelligenceReportExportDtos.ExportCommand.class, String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                .isNotNull();
    }

    @Test
    void responseIsANoStoreAttachmentWithVersionAndDigestEvidence() {
        MeetingIntelligenceReportExportService service =
                mock(MeetingIntelligenceReportExportService.class);
        UUID meetingId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        var command = new MeetingIntelligenceReportExportDtos.ExportCommand(3L, "MARKDOWN");
        byte[] content = "# recap\n".getBytes(StandardCharsets.UTF_8);
        when(service.export(meetingId, reportId, command, "correlation-1"))
                .thenReturn(new MeetingIntelligenceReportExportService.ReportExport(
                        "dwp-meeting-recap.md", "text/markdown;charset=UTF-8",
                        3, "a".repeat(64), content));

        var response = new MeetingIntelligenceReportExportController(service)
                .export(meetingId, reportId, command, "correlation-1");

        assertThat(response.getBody()).isSameAs(content);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", "dwp-meeting-recap.md");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("X-DWP-Report-Version"))
                .isEqualTo("3");
        assertThat(response.getHeaders().getFirst("X-DWP-Content-SHA256"))
                .isEqualTo("a".repeat(64));
    }

    @Test
    void commandAcceptsOnlyKnownFormatsAndAPositiveObservedVersion() {
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var validator = validatorFactory.getValidator();

            assertThat(validator.validate(
                    new MeetingIntelligenceReportExportDtos.ExportCommand(1L, "JSON")))
                    .isEmpty();
            assertThat(validator.validate(
                    new MeetingIntelligenceReportExportDtos.ExportCommand(-1L, "PDF")))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("expectedReportVersion", "format");
            assertThat(validator.validate(
                    new MeetingIntelligenceReportExportDtos.ExportCommand(0L, "JSON")))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("expectedReportVersion");
        }
    }
}
