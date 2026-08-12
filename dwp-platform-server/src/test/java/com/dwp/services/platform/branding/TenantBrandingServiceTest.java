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

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    private TenantBranding branding(Long tenantId, Long version) {
        return TenantBranding.builder()
                .tenantId(tenantId)
                .organizationName("Acme")
                .accentColor("#2457D6")
                .version(version)
                .build();
    }
}
