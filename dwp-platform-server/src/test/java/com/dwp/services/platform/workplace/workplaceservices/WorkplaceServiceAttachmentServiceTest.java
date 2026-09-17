package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkplaceServiceAttachmentServiceTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void uploadValidatesContentAndDoesNotStoreAgainWhenCommandIsReplayed() {
        WorkplaceServicesService services = mock(WorkplaceServicesService.class);
        WorkplaceServicesRepository repository = mock(WorkplaceServicesRepository.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        WorkplaceServiceAttachmentService subject =
                new WorkplaceServiceAttachmentService(services, repository, storage);
        ServiceOrderCommandResult result = mock(ServiceOrderCommandResult.class);
        AtomicInteger invocations = new AtomicInteger();
        when(storage.store(eq(42L), eq("workplace/service-orders/order-18"), eq("png"), any()))
                .thenReturn("42/workplace/service-orders/order-18/attachment.png");
        when(services.linkAttachment(eq(42L), eq(99L), any(), eq(false), eq("stable-key"),
                eq(4L), eq("Share room setup"), any(), any(), eq("corr-18")))
                .thenAnswer(call -> {
                    if (invocations.getAndIncrement() == 0) {
                        @SuppressWarnings("unchecked")
                        Supplier<String> store = call.getArgument(8, Supplier.class);
                        store.get();
                    }
                    return result;
                });
        UUID orderId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file", "setup.png", "image/png", PNG);

        assertThat(subject.upload(42, 99, orderId, false, "stable-key", 4,
                "Share room setup", file, "corr-18")).isSameAs(result);
        assertThat(subject.upload(42, 99, orderId, false, "stable-key", 4,
                "Share room setup", file, "corr-18")).isSameAs(result);

        verify(storage, times(1)).store(eq(42L),
                eq("workplace/service-orders/" + orderId), eq("png"), aryEq(PNG));
        verify(storage, never()).delete(anyLong(), anyString());
    }

    @Test
    void failedMetadataLinkDeletesTheStagedObjectAndRejectsSpoofedMedia() {
        WorkplaceServicesService services = mock(WorkplaceServicesService.class);
        WorkplaceServicesRepository repository = mock(WorkplaceServicesRepository.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        WorkplaceServiceAttachmentService subject =
                new WorkplaceServiceAttachmentService(services, repository, storage);
        UUID orderId = UUID.randomUUID();
        String key = "42/workplace/service-orders/" + orderId + "/attachment.png";
        when(storage.store(eq(42L), anyString(), eq("png"), any())).thenReturn(key);
        when(services.linkAttachment(anyLong(), anyLong(), eq(orderId), anyBoolean(),
                anyString(), anyLong(), anyString(), any(), any(), any()))
                .thenAnswer(call -> {
                    @SuppressWarnings("unchecked")
                    Supplier<String> store = call.getArgument(8, Supplier.class);
                    store.get();
                    throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, "Changed");
                });

        assertThatThrownBy(() -> subject.upload(42, 99, orderId, false, "key", 1,
                "Attach evidence", new MockMultipartFile(
                        "file", "setup.png", "image/png", PNG), "corr"))
                .isInstanceOf(BaseException.class);
        verify(storage).delete(42L, key);

        assertThatThrownBy(() -> subject.upload(42, 99, orderId, false, "other", 1,
                "Attach evidence", new MockMultipartFile(
                        "file", "fake.png", "image/png", "%PDF-1.7".getBytes()), "corr"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void downloadResolvesOpaqueIdOnlyAfterTenantAndOwnerAuthorization() {
        WorkplaceServicesService services = mock(WorkplaceServicesService.class);
        WorkplaceServicesRepository repository = mock(WorkplaceServicesRepository.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        WorkplaceServiceAttachmentService subject =
                new WorkplaceServiceAttachmentService(services, repository, storage);
        UUID orderId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        String key = "42/workplace/service-orders/" + orderId + "/attachment.pdf";
        when(services.ownOrder(42, 99, orderId)).thenReturn(mock(ServiceOrder.class));
        when(repository.attachment(42, orderId, attachmentId)).thenReturn(Optional.of(
                new AttachmentRow(attachmentId, 42, orderId, 99, key, "brief.pdf",
                        "application/pdf", 9, "a".repeat(64), AttachmentScanState.CLEAN,
                        2, "scanner-receipt-18", "Clean", OffsetDateTime.now(),
                        OffsetDateTime.now())));
        ByteArrayResource resource = new ByteArrayResource("%PDF-1.7".getBytes());
        when(storage.load(42L, key)).thenReturn(resource);

        assertThat(subject.download(42, 99, orderId, attachmentId, false, "corr-download"))
                .satisfies(content -> {
                    assertThat(content.resource()).isSameAs(resource);
                    assertThat(content.fileName()).isEqualTo("brief.pdf");
                });

        when(services.ownOrder(42, 100, orderId))
                .thenThrow(new BaseException(ErrorCode.NOT_FOUND));
        verify(repository).appendAttachmentAccessAudit(
                42, 99, orderId, attachmentId, false, "corr-download");
        when(services.adminOrder(42, orderId)).thenReturn(mock(ServiceOrder.class));
        assertThat(subject.download(42, 777, orderId, attachmentId, true, "corr-admin").resource())
                .isSameAs(resource);
        verify(repository).appendAttachmentAccessAudit(
                42, 777, orderId, attachmentId, true, "corr-admin");
        assertThatThrownBy(() -> subject.download(
                42, 100, orderId, attachmentId, false, "corr-denied"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(repository, times(2)).attachment(42, orderId, attachmentId);
        verify(storage, times(2)).load(42L, key);
        verify(repository, never()).appendAttachmentAccessAudit(
                eq(42L), eq(100L), any(), any(), anyBoolean(), anyString());
    }

    @Test
    void quarantinedDownloadAndMagicOnlyFilesFailClosedBeforeStorageAccess() {
        WorkplaceServicesService services = mock(WorkplaceServicesService.class);
        WorkplaceServicesRepository repository = mock(WorkplaceServicesRepository.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        WorkplaceServiceAttachmentService subject =
                new WorkplaceServiceAttachmentService(services, repository, storage);
        UUID orderId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(services.ownOrder(42, 99, orderId)).thenReturn(mock(ServiceOrder.class));
        when(repository.attachment(42, orderId, attachmentId)).thenReturn(Optional.of(
                new AttachmentRow(attachmentId, 42, orderId, 99, "opaque/quarantine",
                        "pending.png", "image/png", PNG.length, "b".repeat(64),
                        AttachmentScanState.QUARANTINED, 1, null, null, null,
                        OffsetDateTime.now())));

        assertThatThrownBy(() -> subject.download(
                42, 99, orderId, attachmentId, false, "corr-quarantine"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(storage, never()).load(anyLong(), anyString());
        verify(repository, never()).appendAttachmentAccessAudit(
                anyLong(), anyLong(), any(), any(), anyBoolean(), anyString());

        byte[] magicOnly = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        assertThatThrownBy(() -> subject.upload(42, 99, orderId, false, "magic-only", 1,
                "Reject incomplete image", new MockMultipartFile(
                        "file", "magic.png", "image/png", magicOnly), "corr"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void scanEvidenceLengthsAreValidatedAtTheServiceBoundary() {
        WorkplaceServicesService services = mock(WorkplaceServicesService.class);
        WorkplaceServicesRepository repository = mock(WorkplaceServicesRepository.class);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        WorkplaceServiceAttachmentService subject =
                new WorkplaceServiceAttachmentService(services, repository, storage);
        UUID orderId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(services.adminOrder(42, orderId)).thenReturn(mock(ServiceOrder.class));

        for (AttachmentScanRequest request : java.util.List.of(
                new AttachmentScanRequest(1, AttachmentScanVerdict.CLEAN,
                        "e".repeat(321), null, true, "Register evidence"),
                new AttachmentScanRequest(1, AttachmentScanVerdict.CLEAN,
                        "evidence", "d".repeat(1_001), true, "Register evidence"),
                new AttachmentScanRequest(1, AttachmentScanVerdict.CLEAN,
                        "evidence", null, true, "r".repeat(501)))) {
            assertThatThrownBy(() -> subject.recordScan(
                    42, 99, orderId, attachmentId, "scan-key", request, "corr"))
                    .isInstanceOfSatisfying(BaseException.class,
                            error -> assertThat(error.getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        }

        verify(repository, never()).command(anyLong(), anyLong(), anyString(), anyString());
        verify(repository, never()).attachmentForUpdate(anyLong(), any(), any());
    }
}
