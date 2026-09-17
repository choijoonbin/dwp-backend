package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceResourceFavoriteDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkplaceResourceFavoriteServiceTest {
    private final WorkplaceResourceFavoriteRepository repository =
            mock(WorkplaceResourceFavoriteRepository.class);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-09-17T06:00:00Z");
    private final WorkplaceResourceFavoriteService service = new WorkplaceResourceFavoriteService(
            repository, Clock.fixed(Instant.parse("2026-09-17T06:00:00Z"), ZoneOffset.UTC));

    @Test
    void favoriteIsTenantUserScopedVersionedAuditedAndReplaySafe() {
        UUID resourceId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        when(repository.resourceExists(42, resourceId)).thenReturn(true);
        when(repository.favorite(42, 7, resourceId)).thenReturn(Optional.empty());
        when(repository.audit(42, 7, resourceId, true, 1, "corr-14", now))
                .thenReturn(auditId);
        when(repository.insertCommand(eq(42L), eq(7L), eq(resourceId), eq("favorite-key"),
                anyString(), any(), eq(auditId), eq("corr-14"), eq(now)))
                .thenReturn(commandId);

        FavoriteReceipt created = service.set(42, 7, resourceId, "favorite-key", "corr-14",
                new SetFavoriteRequest(true, 0));

        assertThat(created.commandId()).isEqualTo(commandId);
        assertThat(created.favorite()).isEqualTo(new FavoriteView(resourceId, true, 1, now));
        verify(repository).lock(42, 7, resourceId);
        verify(repository).save(42, 7, resourceId, true, 0, now);
        verify(repository).audit(42, 7, resourceId, true, 1, "corr-14", now);

        String fingerprint = captureFingerprint(resourceId, auditId);
        when(repository.command(42, 7, "favorite-key"))
                .thenReturn(Optional.of(new WorkplaceResourceFavoriteRepository.CommandRow(
                        commandId, resourceId, fingerprint, true, 1, now, auditId, "corr-14")));
        assertThat(service.set(42, 7, resourceId, "favorite-key", "corr-14",
                new SetFavoriteRequest(true, 0))).isEqualTo(created);
        verify(repository, times(1)).save(anyLong(), anyLong(), any(), anyBoolean(), anyLong(), any());
    }

    @Test
    void staleVersionAndCrossTenantResourceFailClosed() {
        UUID resourceId = UUID.randomUUID();
        when(repository.resourceExists(42, resourceId)).thenReturn(true);
        when(repository.favorite(42, 7, resourceId)).thenReturn(Optional.of(
                new FavoriteView(resourceId, true, 3, now.minusMinutes(1))));

        assertThatThrownBy(() -> service.set(42, 7, resourceId, "clear-key", null,
                new SetFavoriteRequest(false, 2)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        reset(repository);
        when(repository.resourceExists(42, resourceId)).thenReturn(false);
        assertThatThrownBy(() -> service.set(42, 7, resourceId, "favorite-key", null,
                new SetFavoriteRequest(true, 0)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));
    }

    @Test
    void listIsBoundedAndDeduplicated() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.favorites(42, 7, List.of(first, first, second));
        verify(repository).favorites(42, 7, List.of(first, second));

        List<UUID> tooMany = java.util.stream.IntStream.range(0, 201)
                .mapToObj(index -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> service.favorites(42, 7, tooMany))
                .isInstanceOf(BaseException.class);
    }

    private String captureFingerprint(UUID resourceId, UUID auditId) {
        var fingerprint = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).insertCommand(eq(42L), eq(7L), eq(resourceId), eq("favorite-key"),
                fingerprint.capture(), any(), eq(auditId), eq("corr-14"), eq(now));
        return fingerprint.getValue();
    }
}
