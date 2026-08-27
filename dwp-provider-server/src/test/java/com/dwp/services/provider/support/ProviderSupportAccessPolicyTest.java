package com.dwp.services.provider.support;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSupportAccessPolicyTest {

    @Test
    void keepsTenantAuthorityMetadataEvaluationAndStepUpClosedInSupportMode() {
        for (String method : List.of("GET", "HEAD", "POST")) {
            for (String path : List.of(
                    "/api/auth/product-surface-contexts",
                    "/api/auth/product-surface-access/evaluate",
                    "/api/auth/governed-route-access/evaluate",
                    "/api/auth/product-surface-step-up-challenges")) {
                assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope(method, path))
                        .as(method + " " + path)
                        .isInstanceOf(BaseException.class)
                        .hasMessageContaining("does not permit this resource");
            }
        }
    }

    @Test
    void keepsBroadConfigurationScopesClosedUntilExactMaskedProjectionsExist() {
        for (String method : List.of("GET", "HEAD", "PUT", "POST")) {
            for (String path : List.of(
                    "/api/platform/v1/admin/tenant-branding",
                    "/api/platform/v1/admin/tenant-branding/logo",
                    "/api/platform/v1/admin/home-experience",
                    "/api/platform/v1/admin/home-experience/revisions")) {
                assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope(method, path))
                        .as(method + " " + path)
                        .isInstanceOf(BaseException.class);
            }
        }
    }

    @Test
    void keepsWorkforceSupportClosedUntilAFieldMaskedProjectionExists() {
        for (String path : List.of(
                "/api/people/v1/people",
                "/api/people/v1/people/00000000-0000-0000-0000-000000000001",
                "/api/people/v1/org-chart",
                "/api/people/v1/workforce/people",
                "/api/people/v1/workforce/organization/chart",
                "/api/people/v1/workforce/data-operations/hris/connectors",
                "/api/people/v1/workforce/exports",
                "/api/people/v1/workforce/reference-data",
                "/api/people/v1/admin/workforce/access-policies")) {
            assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope("GET", path))
                    .as(path)
                    .isInstanceOf(BaseException.class);
        }
    }

    @Test
    void keepsAnnouncementContentClosedUntilAFieldMaskedProjectionExists() {
        for (String path : List.of(
                "/api/platform/v1/admin/announcements",
                "/api/platform/v1/admin/announcements/42",
                "/api/platform/v1/announcements")) {
            assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope("GET", path))
                    .as(path)
                    .isInstanceOf(BaseException.class);
        }
    }

    @Test
    void rejectsUnrelatedTenantAdministrationSurfaces() {
        assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope(
                "GET", "/api/auth/admin/v1/access/roles"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope(
                "GET", "/api/provider/v1/admin/code-catalog/code-sets"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void givesTheSafeExperiencePreviewItsOwnReadOnlyScope() {
        assertThat(ProviderSupportAccessPolicy.requiredScope(
                "GET", "/api/platform/v1/admin/tenant-experience-preview"))
                .isEqualTo("TENANT_EXPERIENCE_PREVIEW");
        assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope(
                "POST", "/api/platform/v1/admin/tenant-experience-preview"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> ProviderSupportAccessPolicy.requiredScope(
                "HEAD", "/api/platform/v1/admin/tenant-experience-preview"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("read-only");
    }
}
