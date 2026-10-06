package com.dwp.services.platform.branding;

import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.experience.ExperienceRevisionStore;
import com.dwp.services.platform.media.TenantMediaStorage;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantBrandingServiceTest {

    @Mock
    private TenantBrandingRepository repository;
    @Mock
    private TenantMediaStorage mediaStorage;
    @Mock
    private BrandLogoValidator logoValidator;
    @Mock
    private PlatformAuditService auditService;
    @Mock
    private ExperienceRevisionStore revisionStore;

    private TenantBrandingService service;

    @BeforeEach
    void setUp() {
        service = new TenantBrandingService(
                repository,
                mediaStorage,
                logoValidator,
                auditService,
                revisionStore);
    }

    @Test
    void publishesOrganizationAndAccentWithImmutableRevisionEvidence() {
        TenantBranding branding = branding(7L, 2L);
        when(repository.findById(7L)).thenReturn(Optional.of(branding));
        when(repository.saveAndFlush(branding)).thenAnswer(invocation -> {
            branding.setVersion(3L);
            return branding;
        });

        TenantBrandingDtos.TenantBrandingResponse result = service.update(
                7L,
                11L,
                "corr-brand",
                new TenantBrandingDtos.UpdateTenantBrandingRequest(
                        "  Acme Group  ",
                        "#0a7f66",
                        2L));

        assertThat(result.organizationName()).isEqualTo("Acme Group");
        assertThat(result.accentColor()).isEqualTo("#0A7F66");
        assertThat(result.version()).isEqualTo(3L);
        verify(revisionStore).ensureBaseline(
                eq(7L), eq("BRANDING"), eq(2L), anyMap(), eq(11L), eq("corr-brand"));
        verify(revisionStore).append(
                eq(7L),
                eq("BRANDING"),
                eq(3L),
                eq("SETTINGS_PUBLISHED"),
                anyMap(),
                eq(11L),
                eq("corr-brand"));
    }

    @Test
    void restoresARevisionOnlyAfterConfirmingItsRetainedAssetExists() {
        TenantBranding branding = branding(7L, 4L);
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.put("organizationName", "Prior Acme");
        snapshot.put("accentColor", "#2457D6");
        snapshot.put("logoAssetKey", "7/branding/logos/prior.svg");
        snapshot.put("logoOriginalName", "prior.svg");
        snapshot.put("logoContentType", "image/svg+xml");
        snapshot.put("logoSizeBytes", 512L);
        snapshot.put("logoSha256", "a".repeat(64));
        snapshot.put("logoWidth", 120);
        snapshot.put("logoHeight", 40);
        when(revisionStore.require(7L, "BRANDING", 19L)).thenReturn(
                new ExperienceRevisionStore.ExperienceRevision(
                        19L,
                        7L,
                        "BRANDING",
                        2L,
                        "ASSET_PUBLISHED",
                        snapshot,
                        "prior-correlation",
                        OffsetDateTime.parse("2026-08-10T00:00:00Z"),
                        8L));
        when(repository.findById(7L)).thenReturn(Optional.of(branding));
        when(repository.saveAndFlush(branding)).thenAnswer(invocation -> {
            branding.setVersion(5L);
            return branding;
        });

        TenantBrandingDtos.TenantBrandingResponse result =
                service.rollback(7L, 11L, "corr-rollback", 19L, 4L);

        assertThat(result.organizationName()).isEqualTo("Prior Acme");
        assertThat(result.logoOriginalName()).isEqualTo("prior.svg");
        assertThat(result.version()).isEqualTo(5L);
        verify(mediaStorage).load(7L, "7/branding/logos/prior.svg");
        verify(revisionStore).append(
                eq(7L),
                eq("BRANDING"),
                eq(5L),
                eq("ROLLBACK"),
                anyMap(),
                eq(11L),
                eq("corr-rollback"));
        verify(auditService).success(
                eq(7L),
                eq(11L),
                eq("tenant-branding.rolled-back"),
                eq("TENANT_BRANDING"),
                eq("7"),
                eq("corr-rollback"),
                anyMap(),
                anyMap());
    }

    @Test
    void servesTheBundledSkaxLogoWithoutDependingOnMutableTenantMedia() throws Exception {
        TenantBranding branding = branding(1L, 0L);
        branding.setOrganizationName("SKAX");
        branding.setLogoAssetKey(TenantBrandingService.BUNDLED_SKAX_LOGO_KEY);
        branding.setLogoOriginalName("skax-tenant-logo.svg");
        branding.setLogoContentType("image/svg+xml");
        branding.setLogoSizeBytes(6104L);
        branding.setLogoSha256("d95624857451f9c16f5993c21e14048b253cc3c809e4640d891f3dd1077642b0");
        branding.setLogoWidth(106);
        branding.setLogoHeight(56);
        when(repository.findById(1L)).thenReturn(Optional.of(branding));

        TenantBrandingService.LogoContent result = service.getLogo(1L);
        byte[] content = result.resource().getInputStream().readAllBytes();

        assertThat(content).hasSize(6104);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)))
                .isEqualTo("d95624857451f9c16f5993c21e14048b253cc3c809e4640d891f3dd1077642b0");
        assertThat(new String(content, StandardCharsets.UTF_8))
                .contains("viewBox=\"0 0 106 56\"");
        assertThat(result.contentType()).isEqualTo("image/svg+xml");
        assertThat(result.sizeBytes()).isEqualTo(6104L);
        verifyNoInteractions(mediaStorage);
    }

    @Test
    void restoresABundledLogoRevisionWithoutLookingInMutableTenantMedia() {
        TenantBranding branding = branding(1L, 2L);
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.put("organizationName", "SKAX");
        snapshot.put("accentColor", "#2457D6");
        snapshot.put("logoAssetKey", TenantBrandingService.BUNDLED_SKAX_LOGO_KEY);
        snapshot.put("logoOriginalName", "skax-tenant-logo.svg");
        snapshot.put("logoContentType", "image/svg+xml");
        snapshot.put("logoSizeBytes", 6104L);
        snapshot.put("logoSha256", "d95624857451f9c16f5993c21e14048b253cc3c809e4640d891f3dd1077642b0");
        snapshot.put("logoWidth", 106);
        snapshot.put("logoHeight", 56);
        when(revisionStore.require(1L, "BRANDING", 7L)).thenReturn(
                new ExperienceRevisionStore.ExperienceRevision(
                        7L,
                        1L,
                        "BRANDING",
                        0L,
                        "BASELINE",
                        snapshot,
                        "seed",
                        OffsetDateTime.parse("2026-08-10T00:00:00Z"),
                        1L));
        when(repository.findById(1L)).thenReturn(Optional.of(branding));
        when(repository.saveAndFlush(branding)).thenAnswer(invocation -> {
            branding.setVersion(3L);
            return branding;
        });

        TenantBrandingDtos.TenantBrandingResponse result =
                service.rollback(1L, 11L, "corr-bundled-rollback", 7L, 2L);

        assertThat(result.logoUrl()).isEqualTo("/api/platform/v1/tenant-branding/logo?v=3");
        assertThat(branding.getLogoAssetKey()).isEqualTo(TenantBrandingService.BUNDLED_SKAX_LOGO_KEY);
        verifyNoInteractions(mediaStorage);
    }

    private TenantBranding branding(Long tenantId, Long version) {
        return TenantBranding.builder()
                .tenantId(tenantId)
                .organizationName("Acme")
                .accentColor("#2457D6")
                .version(version)
                .build();
    }
}
