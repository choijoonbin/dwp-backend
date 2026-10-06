package com.dwp.services.platform.branding;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class SkaxBrandingSeedMigrationPostgresTest {

    private static final String BUNDLED_KEY = "builtin/branding/skax-tenant-logo.svg";
    private static final String BUNDLED_SHA =
            "d95624857451f9c16f5993c21e14048b253cc3c809e4640d891f3dd1077642b0";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void cleanMigrationSeedsTheBundledLogoForTheUntouchedSkaxDemoTenant() {
        PGSimpleDataSource source = dataSource();
        migrateThrough(source, "319", true);
        JdbcTemplate jdbc = new JdbcTemplate(source);

        BrandingRow before = branding(jdbc);
        assertThat(before.version()).isZero();
        assertThat(before.assetKey()).isNull();

        assertThat(migrateThrough(source, "320", false).migrationsExecuted).isEqualTo(1);

        BrandingRow seeded = branding(jdbc);
        assertThat(seeded)
                .isEqualTo(new BrandingRow(
                        BUNDLED_KEY,
                        "skax-tenant-logo.svg",
                        "image/svg+xml",
                        6104L,
                        BUNDLED_SHA,
                        106,
                        56,
                        0L));
        assertThat(migrateThrough(source, "320", false).migrationsExecuted).isZero();
    }

    @Test
    void migrationPreservesAnExistingAdministratorUpload() {
        PGSimpleDataSource source = dataSource();
        migrateThrough(source, "319", true);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("""
                UPDATE adm_tenant_branding
                   SET logo_asset_key = '1/branding/logos/custom.svg',
                       logo_original_name = 'custom.svg',
                       logo_content_type = 'image/svg+xml',
                       logo_size_bytes = 321,
                       logo_sha256 = ?,
                       logo_width = 120,
                       logo_height = 40,
                       version = 7
                 WHERE tenant_id = 1
                """, "a".repeat(64));

        migrateThrough(source, "320", false);

        assertThat(branding(jdbc))
                .isEqualTo(new BrandingRow(
                        "1/branding/logos/custom.svg",
                        "custom.svg",
                        "image/svg+xml",
                        321L,
                        "a".repeat(64),
                        120,
                        40,
                        7L));
    }

    @Test
    void migrationPreservesAnExplicitAdministratorReset() {
        PGSimpleDataSource source = dataSource();
        migrateThrough(source, "319", true);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("""
                UPDATE adm_tenant_branding
                   SET version = 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = 900018
                 WHERE tenant_id = 1
                """);

        migrateThrough(source, "320", false);

        BrandingRow reset = branding(jdbc);
        assertThat(reset.version()).isEqualTo(1L);
        assertThat(reset.assetKey()).isNull();
        assertThat(reset.originalName()).isNull();
        assertThat(reset.sha256()).isNull();
    }

    private org.flywaydb.core.api.output.MigrateResult migrateThrough(
            PGSimpleDataSource source,
            String target,
            boolean clean) {
        Flyway flyway = flyway(source).target(target).load();
        if (clean) flyway.clean();
        return flyway.migrate();
    }

    private FluentConfiguration flyway(PGSimpleDataSource source) {
        return Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false);
    }

    private BrandingRow branding(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT logo_asset_key, logo_original_name, logo_content_type,
                       logo_size_bytes, logo_sha256, logo_width, logo_height, version
                  FROM adm_tenant_branding
                 WHERE tenant_id = 1
                """, (result, rowNumber) -> new BrandingRow(
                result.getString("logo_asset_key"),
                result.getString("logo_original_name"),
                result.getString("logo_content_type"),
                result.getObject("logo_size_bytes", Long.class),
                result.getString("logo_sha256"),
                result.getObject("logo_width", Integer.class),
                result.getObject("logo_height", Integer.class),
                result.getLong("version")));
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private record BrandingRow(
            String assetKey,
            String originalName,
            String contentType,
            Long sizeBytes,
            String sha256,
            Integer width,
            Integer height,
            Long version) {
    }
}
