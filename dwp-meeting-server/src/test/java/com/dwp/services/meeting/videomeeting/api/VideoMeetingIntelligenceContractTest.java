package com.dwp.services.meeting.videomeeting.api;

import com.dwp.services.meeting.videomeeting.domain.MeetingIntelligenceRunTransactions;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.Audience;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportView;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceService;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Citation;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.CitedText;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateLabel;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ClimateSignal;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ConversationClimate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMeetingIntelligenceContractTest {

    @Test
    void exposesCanonicalIntelligenceRoutes() throws Exception {
        RequestMapping root = VideoMeetingIntelligenceController.class
                .getAnnotation(RequestMapping.class);

        assertThat(root.value()).containsExactly("/v1/meetings/{meetingId}/intelligence");
        assertPost("createRun", "/runs");
        assertGet("run", "/runs/{runId}");
        assertGet("report", "/reports/{reportId}");
        assertGet("latestReport", "/reports/latest");
        assertGet("latestPublishedReport", "/reports/latest-published");
        assertPost("review", "/reports/{reportId}/review");
        assertPost("publish", "/reports/{reportId}/publish");
        assertDelete("delete", "/reports/{reportId}");
        assertGet("reviewerAssignments", "/reports/{reportId}/reviewer-assignments");
        assertPut("grant", "/reports/{reportId}/acl/{principalUserId}");
        assertDelete("revoke", "/reports/{reportId}/acl/{principalUserId}/{permission}");
    }

    @Test
    void deleteUsesQueryVersionAndHasNoRequestBody() throws Exception {
        Method method = VideoMeetingIntelligenceController.class.getDeclaredMethod(
                "delete", UUID.class, UUID.class, long.class, String.class);

        assertThat(method.getParameterAnnotations()[2])
                .anyMatch(annotation -> annotation instanceof RequestParam);
        assertThat(method.getParameterAnnotations()[2])
                .noneMatch(annotation -> annotation.annotationType().getSimpleName()
                        .equals("RequestBody"));
    }

    @Test
    void reviewerMutationsRequireTheObservedReportVersion() throws Exception {
        JsonNode command = new ObjectMapper().valueToTree(
                new VideoMeetingIntelligenceDtos.GrantCommand(
                        7L, "REVIEW", null, "HOST_ASSIGNED_REVIEWER"));
        Method revoke = VideoMeetingIntelligenceController.class.getDeclaredMethod(
                "revoke", UUID.class, UUID.class, long.class, String.class,
                long.class, String.class);
        Method grantService = VideoMeetingIntelligenceService.class.getDeclaredMethod(
                "grant", UUID.class, UUID.class, long.class,
                VideoMeetingIntelligenceDtos.GrantCommand.class, String.class);
        Method revokeService = VideoMeetingIntelligenceService.class.getDeclaredMethod(
                "revoke", UUID.class, UUID.class, long.class, String.class,
                long.class, String.class);

        assertThat(fieldNames(command)).containsExactlyInAnyOrder(
                "expectedReportVersion", "permission", "expiresAt", "reasonCode");
        assertThat(revoke.getParameterAnnotations()[4])
                .anyMatch(annotation -> annotation instanceof RequestParam requestParam
                        && requestParam.required()
                        && requestParam.value().equals("expectedReportVersion"));
        assertThat(revoke.getParameterAnnotations()[4])
                .noneMatch(annotation -> annotation.annotationType().getSimpleName()
                        .equals("RequestBody"));
        assertThat(grantService.getAnnotation(Transactional.class)).isNotNull();
        assertThat(revokeService.getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void createRunDoesNotHoldATransactionAcrossExternalIo() throws Exception {
        Method method = VideoMeetingIntelligenceService.class.getDeclaredMethod(
                "createRun", UUID.class,
                VideoMeetingIntelligenceDtos.CreateRunCommand.class,
                String.class, String.class);

        assertThat(method.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void prepareAndFinalizeBoundariesAreTransactional() throws Exception {
        Method prepare = MeetingIntelligenceRunTransactions.class.getDeclaredMethod(
                "prepare",
                com.dwp.services.meeting.security.MeetingRequestContext.Subject.class,
                UUID.class, VideoMeetingIntelligenceDtos.CreateRunCommand.class,
                String.class, String.class, UUID.class, OffsetDateTime.class);
        Method succeed = java.util.Arrays.stream(
                        MeetingIntelligenceRunTransactions.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("succeed"))
                .findFirst().orElseThrow();

        assertThat(prepare.getAnnotation(Transactional.class)).isNotNull();
        assertThat(succeed.getAnnotation(Transactional.class)).isNotNull();
        assertThat(prepare.getAnnotation(Transactional.class).propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        assertThat(succeed.getAnnotation(Transactional.class).propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
    }

    @Test
    void remoteDependencyPreflightDoesNotRunInsideATransaction() throws Exception {
        Method readiness = MeetingIntelligenceRunTransactions.class
                .getDeclaredMethod("ensureExecutionReadiness");

        assertThat(readiness.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void reportResponseNeverExposesCiphertextOrSourceHash() {
        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(
                VideoMeetingIntelligenceDtos.ReportResponse.from(view()));

        assertThat(fieldNames(json)).contains(
                "reportId", "state", "audience", "canCurrentViewerReview",
                "analysis", "reviews");
        assertThat(fieldNames(json)).doesNotContain(
                "encryptedPayload", "payloadSha256", "sourceSha256",
                "objectKey", "transcript");
    }

    @Test
    void conversationClimateContractHasNoPersonInferenceFields() {
        JsonNode climate = new ObjectMapper().valueToTree(
                view().payload().conversationClimate());

        assertThat(fieldNames(climate)).containsExactlyInAnyOrder(
                "label", "signals", "citations");
        assertThat(fieldNames(climate)).doesNotContain(
                "speaker", "participant", "person", "emotion", "sentiment",
                "biometric", "performanceScore");
    }

    @Test
    void conversationClimateSignalsRequireEvidenceAvailableInTheTranscriptContract() {
        assertThat(ClimateSignal.values()).containsExactly(
                ClimateSignal.CONSTRUCTIVE_DISAGREEMENT,
                ClimateSignal.UNRESOLVED_DISAGREEMENT,
                ClimateSignal.LOW_TRANSCRIPT_EVIDENCE);
    }

    @Test
    void createRunInputCannotSupplyProviderOrTranscriptText() {
        JsonNode json = new ObjectMapper().valueToTree(
                new VideoMeetingIntelligenceDtos.CreateRunCommand(
                        UUID.randomUUID(), "ko-KR", 3L));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "sourceArtifactId", "outputLanguage", "expectedContentPlanVersion");
        assertThat(fieldNames(json)).doesNotContain(
                "transcript", "provider", "model", "processingRegion",
                "tenantId", "objectKey");
    }

    @Test
    void reviewContractUsesReasonCodeInsteadOfFreeTextNotes() {
        JsonNode json = new ObjectMapper().valueToTree(
                new VideoMeetingIntelligenceDtos.ReviewCommand(0L, "APPROVE", "VERIFIED"));

        assertThat(fieldNames(json)).containsExactlyInAnyOrder(
                "expectedVersion", "decision", "reasonCode");
        assertThat(fieldNames(json)).doesNotContain("notes", "comment", "transcript");
    }

    private ReportView view() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T00:00:00Z");
        UUID meetingId = UUID.randomUUID();
        IntelligenceReport report = new IntelligenceReport(
                UUID.randomUUID(), 77, meetingId, UUID.randomUUID(),
                ReportState.PUBLISHED, Audience.MEETING_PARTICIPANTS,
                "dwp2.encrypted", "a".repeat(64), "b".repeat(64),
                "meeting-intelligence-v1", now.plusDays(30), false,
                now, 202L, now, 101L, null, null, 2, 101L);
        Citation citation = new Citation("s1", 0, 900);
        Analysis analysis = new Analysis(
                new CitedText("Summary", List.of(citation)), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                new ConversationClimate(
                        ClimateLabel.ALIGNED,
                        List.of(ClimateSignal.CONSTRUCTIVE_DISAGREEMENT),
                        List.of(citation)));
        return new ReportView(report, analysis, List.of());
    }

    private Set<String> fieldNames(JsonNode node) {
        return node.properties().stream().map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void assertGet(String name, String path) {
        assertThat(method(name).getAnnotation(GetMapping.class).value()).containsExactly(path);
    }

    private void assertPost(String name, String path) {
        assertThat(method(name).getAnnotation(PostMapping.class).value()).containsExactly(path);
    }

    private void assertPut(String name, String path) {
        assertThat(method(name).getAnnotation(PutMapping.class).value()).containsExactly(path);
    }

    private void assertDelete(String name, String path) {
        assertThat(method(name).getAnnotation(DeleteMapping.class).value()).containsExactly(path);
    }

    private Method method(String name) {
        return java.util.Arrays.stream(VideoMeetingIntelligenceController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElseThrow();
    }
}
