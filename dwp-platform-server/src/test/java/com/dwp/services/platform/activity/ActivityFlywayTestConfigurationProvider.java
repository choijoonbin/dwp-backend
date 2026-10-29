package com.dwp.services.platform.activity;

import org.flywaydb.core.api.configuration.ClassicConfiguration;
import org.flywaydb.core.extensibility.ConfigurationProvider;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;

import java.util.Map;

/**
 * Applies the production PostgreSQL advisory-lock model to every programmatic Flyway migration
 * in this module's test runtime. V342 contains CREATE INDEX CONCURRENTLY and therefore cannot run
 * while Flyway keeps a separate transactional advisory-lock snapshot open.
 */
public final class ActivityFlywayTestConfigurationProvider
        implements ConfigurationProvider<PostgreSQLConfigurationExtension> {

    @Override
    public Map<String, String> getConfiguration(
            PostgreSQLConfigurationExtension extension,
            ClassicConfiguration configuration) {
        return Map.of("flyway.postgresql.transactional.lock", "false");
    }

    @Override
    public Class<PostgreSQLConfigurationExtension> getConfigurationExtensionClass() {
        return PostgreSQLConfigurationExtension.class;
    }
}
