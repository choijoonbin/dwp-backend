package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import org.springframework.stereotype.Component;

@Component
public class MeetingContentAccessPolicy {

    public boolean canView(
            Participant viewer,
            IntelligenceReport report,
            boolean explicitGrant) {
        if (report.state() == ReportState.DELETED || viewer == null
                || viewer.attendanceState() == AttendanceState.DENIED) {
            return false;
        }
        if (viewer.canHost() || explicitGrant) return true;
        return report.state() == ReportState.PUBLISHED && viewer.admitted();
    }

    public boolean canReview(Participant viewer, boolean explicitGrant) {
        return active(viewer) && (viewer.canHost() || explicitGrant);
    }

    public boolean canManage(Participant viewer, boolean explicitGrant) {
        return active(viewer) && (viewer.canHost() || explicitGrant);
    }

    public boolean canRequest(Participant viewer) {
        return active(viewer) && viewer.canHost();
    }

    private boolean active(Participant viewer) {
        return viewer != null && viewer.attendanceState() != AttendanceState.DENIED;
    }
}
