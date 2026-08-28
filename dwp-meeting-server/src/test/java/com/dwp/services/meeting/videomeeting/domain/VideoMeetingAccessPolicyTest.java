package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMeetingAccessPolicyTest {

    @Test
    void internalScopeAloneNeverGrantsMeetingDetailAccess() {
        assertThat(VideoMeetingRepository.ACCESS_PREDICATE)
                .doesNotContain("meeting.access_scope = 'INTERNAL'")
                .contains("access.user_id = :userId")
                .contains("access.attendance_state <> 'DENIED'");
    }
}
