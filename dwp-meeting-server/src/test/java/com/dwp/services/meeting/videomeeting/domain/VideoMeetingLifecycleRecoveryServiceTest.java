package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.MediaOperation;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingLifecycleModels.OperationType;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoMeetingLifecycleRecoveryServiceTest {

    @Mock
    private VideoMeetingLifecycleRecoveryTransactions recovery;
    @Mock
    private VideoMeetingLifecycleTransactions lifecycle;
    @Mock
    private MeetingMediaProvider provider;

    @Test
    void expiredStartIsRecoveredWithoutAClientRetryAndProviderIoIsOutsideTransactions()
            throws Exception {
        MediaOperation operation = operation(OperationType.START);
        MeetingLifecycleRecoveryProperties properties = properties();
        when(provider.capability()).thenReturn(capability());
        when(recovery.claim()).thenReturn(Optional.of(operation));
        when(lifecycle.maximumParticipants(operation.tenantId())).thenReturn(100);

        service(properties).recover();

        InOrder order = inOrder(recovery, provider, lifecycle);
        order.verify(recovery).claim();
        order.verify(provider).ensureRoom(
                new MeetingMediaProvider.PreparedRoom(
                        operation.providerCode(), operation.providerRoomName(),
                        operation.tenantId(), operation.meetingId(),
                        operation.roomIncarnation()), 100);
        order.verify(lifecycle).completeStart(
                org.mockito.ArgumentMatchers.any(MeetingRequestContext.Subject.class),
                org.mockito.ArgumentMatchers.eq(operation));
        assertThat(VideoMeetingLifecycleRecoveryService.class
                .getDeclaredMethod("recover").isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    void providerPartitionPersistsRetryableFailureInsteadOfFinalizing() {
        MediaOperation operation = operation(OperationType.END);
        MeetingLifecycleRecoveryProperties properties = properties();
        when(provider.capability()).thenReturn(capability());
        when(recovery.claim()).thenReturn(Optional.of(operation));
        org.mockito.Mockito.doThrow(new IllegalStateException("network partition"))
                .when(provider).endRoom(operation.providerRoomName());

        service(properties).recover();

        verify(recovery).failed(operation);
        verify(lifecycle, org.mockito.Mockito.never()).completeEnd(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private VideoMeetingLifecycleRecoveryService service(
            MeetingLifecycleRecoveryProperties properties) {
        return new VideoMeetingLifecycleRecoveryService(
                recovery, lifecycle, provider, properties);
    }

    private MeetingLifecycleRecoveryProperties properties() {
        MeetingLifecycleRecoveryProperties properties =
                new MeetingLifecycleRecoveryProperties();
        properties.setBatchSize(1);
        return properties;
    }

    private MeetingMediaProvider.Capability capability() {
        return new MeetingMediaProvider.Capability(
                true, "LIVEKIT", null, true, true, true, true, 300);
    }

    private MediaOperation operation(OperationType type) {
        OffsetDateTime now = OffsetDateTime.of(
                2026, 8, 29, 1, 0, 0, 0, ZoneOffset.UTC);
        return new MediaOperation(
                UUID.randomUUID(), 77L, UUID.randomUUID(), type,
                OperationState.RUNNING, 101L, 4L, "media-recovery-001",
                "a".repeat(64), "corr-recovery", UUID.randomUUID(),
                now.plusMinutes(2), 2, "LIVEKIT", "room-incarnation",
                UUID.randomUUID());
    }
}
