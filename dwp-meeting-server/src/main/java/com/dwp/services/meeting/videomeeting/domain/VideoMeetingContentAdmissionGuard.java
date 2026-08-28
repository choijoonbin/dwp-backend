package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingContentModels.ContentPlan;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Revalidates the current processing disclosure immediately before media access.
 * A prior acknowledgement is intentionally not portable across notice revisions.
 */
@Component
public class VideoMeetingContentAdmissionGuard {

    private final VideoMeetingContentRepository content;

    public VideoMeetingContentAdmissionGuard(VideoMeetingContentRepository content) {
        this.content = content;
    }

    public void requireCurrentNoticeAcknowledgement(
            long tenantId, UUID meetingId, UUID participantId) {
        ContentPlan plan = content.plan(tenantId, meetingId).orElse(null);
        if (plan == null || !plan.processingRequested()) return;
        UUID noticeId = plan.currentNoticeId();
        if (noticeId == null || content.currentNotice(tenantId, meetingId).isEmpty()) {
            throw disclosureChanged();
        }
        if (!content.acknowledgedBy(tenantId, meetingId, noticeId, participantId)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Acknowledge the current recording and AI processing notice before joining.");
        }
    }

    private BaseException disclosureChanged() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The recording and AI processing notice changed. Refresh before joining.");
    }
}
