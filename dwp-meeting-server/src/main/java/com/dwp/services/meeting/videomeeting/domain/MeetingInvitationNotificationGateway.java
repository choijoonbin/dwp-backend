package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingInvitationDeliveryModels.Claim;

import java.util.UUID;

public interface MeetingInvitationNotificationGateway {

    Acceptance deliver(Claim claim);

    record Acceptance(
            UUID intentId,
            UUID notificationId,
            int recipientCount,
            boolean duplicate,
            String highestChangeVersion) {
    }
}
