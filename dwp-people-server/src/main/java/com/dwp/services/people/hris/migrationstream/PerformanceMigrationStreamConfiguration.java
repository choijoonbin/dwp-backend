package com.dwp.services.people.hris.migrationstream;

import javax.sql.DataSource;

import com.dwp.core.database.RuntimeMigrationDatabaseGuard.MigrationPrincipalPolicy;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

/** Adds the Performance stream without replacing Spring Boot's primary Flyway bean. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        PeopleMigrationStreamProperties.class,
        PerformanceMigrationStreamProperties.class,
        FlywayProperties.class
})
public class PerformanceMigrationStreamConfiguration {

    @Bean
    PeopleFlywayPropertiesGuard peopleFlywayPropertiesGuard(
            FlywayProperties properties,
            @Value("${dwp.people.hris.local-seed-enabled:false}") boolean localSeedEnabled) {
        return PeopleFlywayPropertiesGuard.verify(properties, localSeedEnabled);
    }

    @Bean
    FlywayMigrationStrategy peopleAndPerformanceMigrationStrategy(
            PeopleMigrationStreamProperties peopleProperties,
            PeopleFlywayPropertiesGuard propertiesGuard,
            PerformanceMigrationStreamProperties properties,
            ResourceLoader resourceLoader,
            DataSource dataSource,
            @Value("${dwp.people.hris.database.migration-principal-policy:STRICT}")
                    MigrationPrincipalPolicy migrationPrincipalPolicy,
            @Value("${dwp.people.hris.database.runtime-environment:}")
                    String runtimeEnvironment,
            @Value("${dwp.people.hris.database.service-instance:}") String serviceInstance,
            @Value("${spring.datasource.username}") String applicationUser,
            @Value("${dwp.people.hris.database.adoption-receipt-sha256:}")
                    String peopleReceiptSha256,
            @Value("${dwp.people.hris.database.adoption-control-reference:}")
                    String peopleControlReference,
            @Value("${dwp.people.hris.database.performance-adoption-receipt-sha256:}")
                    String performanceReceiptSha256,
            @Value("${dwp.people.hris.database.performance-adoption-control-reference:}")
                    String performanceControlReference,
            @Value("${dwp.people.hris.database.control-run-receipt-json:}")
                    String controlRunReceiptJson,
            @Value("${dwp.people.hris.database.control-run-receipt-sha256:}")
                    String controlRunReceiptSha256,
            @Value("${dwp.people.hris.database.migration-control-reference:}")
                    String migrationControlReference) {
        // The dependency is intentional: constructing the strategy is forbidden until the
        // primary People stream has passed its exact, fail-closed configuration guard.
        java.util.Objects.requireNonNull(peopleProperties, "peopleProperties must not be null");
        return new PerformanceMigrationStreamBootstrap(
                peopleProperties,
                propertiesGuard,
                properties,
                resourceLoader.getClassLoader(),
                dataSource,
                migrationPrincipalPolicy,
                runtimeEnvironment,
                serviceInstance,
                applicationUser,
                peopleReceiptSha256,
                peopleControlReference,
                performanceReceiptSha256,
                performanceControlReference,
                controlRunReceiptJson,
                controlRunReceiptSha256,
                migrationControlReference);
    }

    @Bean
    PeopleMigrationInfrastructure peopleMigrationInfrastructure(
            Flyway flyway,
            FlywayMigrationInitializer initializer) {
        return new PeopleMigrationInfrastructure(flyway, initializer);
    }

    record PeopleMigrationInfrastructure(
            Flyway flyway,
            FlywayMigrationInitializer initializer) {

        PeopleMigrationInfrastructure {
            java.util.Objects.requireNonNull(flyway, "flyway must not be null");
            java.util.Objects.requireNonNull(initializer, "initializer must not be null");
        }
    }
}
