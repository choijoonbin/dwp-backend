package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportState;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.CitedText;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MeetingFollowupCandidateProjector {

    private MeetingFollowupCandidateProjector() {
    }

    public static List<Candidate> candidates(IntelligenceReport report, Analysis analysis) {
        if (report == null || analysis == null || report.state() != ReportState.PUBLISHED
                || analysis.actionItems() == null) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < analysis.actionItems().size(); index++) {
            CitedText item = analysis.actionItems().get(index);
            if (item != null && item.text() != null && !item.text().isBlank()) {
                candidates.add(new Candidate(
                        candidateId(report, index), report.version(), index, item.text().trim()));
            }
        }
        return List.copyOf(candidates);
    }

    private static UUID candidateId(IntelligenceReport report, int index) {
        try {
            String material = "meeting-followup-v1|" + report.tenantId() + "|"
                    + report.meetingId() + "|" + report.reportId() + "|"
                    + report.payloadSha256() + "|" + index;
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public record Candidate(
            UUID candidateId,
            long sourceVersion,
            int actionItemIndex,
            String confirmedText) {
    }
}
