package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeExperienceServiceTest {

    @Mock
    private HomeExperienceRepository repository;
    @Mock
    private TenantMediaStorage storage;
    @Mock
    private HomeBackgroundValidator validator;
    @Mock
    private PlatformAuditService auditService;

    private HomeExperienceService service;

    @BeforeEach
    void setUp() {
        service = new HomeExperienceService(repository, storage, validator, auditService);
    }

    @Test
    void returnsAStableBuiltInFallbackWithoutCreatingTenantData() {
        when(repository.findById(7L)).thenReturn(Optional.empty());

        HomeExperienceDtos.HomeExperienceResponse result = service.get(7L);

        assertThat(result.backgroundUrl()).isNull();
        assertThat(result.backgroundPosition()).isEqualTo("RIGHT");
        assertThat(result.overlayOpacity()).isEqualTo(18);
        assertThat(result.version()).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    void updatesOnlyTheRequestedTenantAndWritesAudit() {
        HomeExperience experience = experience(7L, 2L, null);
        when(repository.findById(7L)).thenReturn(Optional.of(experience));
        when(repository.saveAndFlush(experience)).thenAnswer(invocation -> {
            experience.setVersion(3L);
            return experience;
        });

        HomeExperienceDtos.HomeExperienceResponse result = service.update(
                7L,
                11L,
                "corr-1",
                new HomeExperienceDtos.UpdateHomeExperienceRequest(
                        "  Acme workspace  ",
                        "Connected work",
                        "RIGHT",
                        24,
                        2L));

        assertThat(result.headline()).isEqualTo("Acme workspace");
        assertThat(result.backgroundPosition()).isEqualTo("RIGHT");
        assertThat(result.version()).isEqualTo(3L);
        verify(repository).findById(7L);
        verify(auditService).success(
                eq(7L),
                eq(11L),
                eq("home-experience.updated"),
                eq("HOME_EXPERIENCE"),
                eq("7"),
                eq("corr-1"),
                anyMap(),
                anyMap());
    }

    @Test
    void rejectsAStaleVersionBeforeWritingFilesOrData() {
        when(repository.findById(7L)).thenReturn(Optional.of(experience(7L, 3L, null)));

        assertThatThrownBy(() -> service.update(
                        7L,
                        11L,
                        null,
                        new HomeExperienceDtos.UpdateHomeExperienceRequest(
                                null, null, "CENTER", 18, 2L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(repository, never()).saveAndFlush(any());
        verify(storage, never()).store(any(), any(), any(), any());
    }

    @Test
    void rejectsAStaleUploadBeforeReadingTheImage() {
        HomeExperience experience = experience(7L, 3L, null);
        MockMultipartFile file = new MockMultipartFile(
                "file", "new.png", "image/png", new byte[]{1, 2, 3});
        when(repository.findById(7L)).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.uploadBackground(7L, 11L, null, 2L, file))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(validator, never()).validate(file);
        verify(storage, never()).store(any(), any(), any(), any());
    }

    @Test
    void replacesBackgroundMetadataWithoutExposingStorageKeys() {
        HomeExperience experience = experience(7L, 1L, "7/old.png");
        MockMultipartFile file = new MockMultipartFile(
                "file", "new.png", "image/png", new byte[]{1, 2, 3});
        HomeBackgroundValidator.ValidatedBackground validated =
                new HomeBackgroundValidator.ValidatedBackground(
                        new byte[]{1, 2, 3},
                        "image/png",
                        "png",
                        "new.png",
                        3,
                        "a".repeat(64),
                        1909,
                        494);
        when(repository.findById(7L)).thenReturn(Optional.of(experience));
        when(validator.validate(file)).thenReturn(validated);
        when(storage.store(7L, "home/backgrounds", "png", validated.content()))
                .thenReturn("7/home/backgrounds/new.png");
        when(repository.saveAndFlush(experience)).thenAnswer(invocation -> {
            experience.setVersion(2L);
            return experience;
        });

        HomeExperienceDtos.HomeExperienceResponse result = service.uploadBackground(
                7L, 11L, "corr-2", 1L, file);

        assertThat(result.backgroundUrl()).isEqualTo(
                "/api/platform/v1/home-experience/background?v=2");
        assertThat(result.backgroundOriginalName()).isEqualTo("new.png");
        assertThat(result.backgroundWidth()).isEqualTo(1909);
        verify(storage).delete(7L, "7/old.png");
        verify(auditService).success(
                eq(7L),
                eq(11L),
                eq("home-experience.background-uploaded"),
                eq("HOME_EXPERIENCE"),
                eq("7"),
                eq("corr-2"),
                anyMap(),
                anyMap());
    }

    @Test
    void resetsBackgroundMetadataAndDeletesTheCommittedAsset() {
        HomeExperience experience = experience(7L, 2L, "7/current.png");
        when(repository.findById(7L)).thenReturn(Optional.of(experience));
        when(repository.saveAndFlush(experience)).thenAnswer(invocation -> {
            experience.setVersion(3L);
            return experience;
        });

        HomeExperienceDtos.HomeExperienceResponse result = service.resetBackground(
                7L, 11L, "corr-3", 2L);

        assertThat(result.backgroundUrl()).isNull();
        assertThat(result.backgroundOriginalName()).isNull();
        assertThat(result.version()).isEqualTo(3L);
        verify(storage).delete(7L, "7/current.png");
        verify(auditService).success(
                eq(7L),
                eq(11L),
                eq("home-experience.background-reset"),
                eq("HOME_EXPERIENCE"),
                eq("7"),
                eq("corr-3"),
                anyMap(),
                anyMap());
    }

    private HomeExperience experience(Long tenantId, Long version, String storageKey) {
        return HomeExperience.builder()
                .tenantId(tenantId)
                .backgroundPosition("CENTER")
                .overlayOpacity(18)
                .backgroundAssetKey(storageKey)
                .backgroundOriginalName(storageKey == null ? null : "old.png")
                .backgroundContentType(storageKey == null ? null : "image/png")
                .backgroundSizeBytes(storageKey == null ? null : 3L)
                .backgroundSha256(storageKey == null ? null : "b".repeat(64))
                .backgroundWidth(storageKey == null ? null : 1600)
                .backgroundHeight(storageKey == null ? null : 420)
                .version(version)
                .build();
    }
}
