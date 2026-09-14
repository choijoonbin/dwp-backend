package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.MeetingCard;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingQueryModels.HistoryItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoMeetingQueryModelsTest {

    @Test
    void meetingPagesKeepTheirQuerySnapshotAfterProducerOrConsumerMutation() {
        var source = new ArrayList<MeetingCard>();
        var page = new VideoMeetingQueryModels.PagedMeetings(source, 0);

        source.add(null);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThatThrownBy(() -> page.items().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void historyPagesKeepTheirQuerySnapshotAfterProducerOrConsumerMutation() {
        var source = new ArrayList<HistoryItem>();
        var page = new VideoMeetingQueryModels.PagedHistory(source, 0);

        source.add(null);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThatThrownBy(() -> page.items().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
