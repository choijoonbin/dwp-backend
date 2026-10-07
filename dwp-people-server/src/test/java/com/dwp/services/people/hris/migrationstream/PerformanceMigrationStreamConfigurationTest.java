package com.dwp.services.people.hris.migrationstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

class PerformanceMigrationStreamConfigurationTest {

    private static final String MIGRATION_URL = "jdbc:postgresql://localhost:5432/dwp_people";
    private static final String MIGRATION_USER = "dwp_people_migration";
    private static final String MIGRATION_PASSWORD = "migration-password";

    private static final PerformanceMigrationStreamProperties PROPERTIES =
            new PerformanceMigrationStreamProperties(
                    PerformanceMigrationStreamProperties.LOCATION,
                    PerformanceMigrationStreamProperties.SCHEMA,
                    PerformanceMigrationStreamProperties.HISTORY_TABLE,
                    PerformanceMigrationStreamProperties.BASELINE_VERSION,
                    false,
                    true,
                    false);

    private static final PeopleMigrationStreamProperties PEOPLE_PROPERTIES =
            new PeopleMigrationStreamProperties(
                    true,
                    PeopleMigrationStreamProperties.LOCATIONS,
                    PeopleMigrationStreamProperties.SCHEMA,
                    java.util.List.of(PeopleMigrationStreamProperties.SCHEMA),
                    PeopleMigrationStreamProperties.HISTORY_TABLE,
                    true,
                    PeopleMigrationStreamProperties.BASELINE_VERSION,
                    true,
                    false,
                    true,
                    true,
                    false,
                    java.nio.charset.StandardCharsets.UTF_8,
                    PeopleMigrationStreamProperties.TARGET,
                    MIGRATION_URL,
                    MIGRATION_USER,
                    MIGRATION_PASSWORD,
                    null,
                    java.util.List.of(),
                    java.util.Map.of(),
                    true,
                    "${",
                    "}",
                    ":",
                    "V",
                    java.util.List.of(".sql"),
                    "__",
                    "R",
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    true,
                    false,
                    java.util.List.of());

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PerformanceMigrationStreamConfiguration.class)
            .withBean(Flyway.class, () -> mock(Flyway.class))
            .withBean(FlywayMigrationInitializer.class,
                    () -> mock(FlywayMigrationInitializer.class))
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withPropertyValues(
                    "spring.flyway.enabled=true",
                    "spring.flyway.url=" + MIGRATION_URL,
                    "spring.flyway.user=" + MIGRATION_USER,
                    "spring.flyway.password=" + MIGRATION_PASSWORD,
                    "spring.flyway.locations=classpath:db/migration",
                    "spring.flyway.default-schema=public",
                    "spring.flyway.schemas=public",
                    "spring.flyway.table=flyway_schema_history",
                    "spring.flyway.baseline-on-migrate=true",
                    "spring.flyway.baseline-version=0",
                    "spring.flyway.validate-on-migrate=true",
                    "spring.flyway.out-of-order=false",
                    "spring.flyway.fail-on-missing-locations=true",
                    "spring.flyway.clean-disabled=true",
                    "spring.flyway.create-schemas=false",
                    "spring.flyway.placeholder-replacement=true",
                    "spring.flyway.validate-migration-naming=true",
                    "spring.flyway.execute-in-transaction=true",
                    "spring.flyway.output-query-results=false",
                    "spring.flyway.community-db-support-enabled=false",
                    "spring.flyway.skip-executing-migrations=false",
                    "spring.flyway.ignore-migration-patterns=",
                    "dwp.people.hris.performance-migration.location="
                            + PerformanceMigrationStreamProperties.LOCATION,
                    "dwp.people.hris.performance-migration.schema="
                            + PerformanceMigrationStreamProperties.SCHEMA,
                    "dwp.people.hris.performance-migration.history-table="
                            + PerformanceMigrationStreamProperties.HISTORY_TABLE,
                    "dwp.people.hris.performance-migration.baseline-version=0",
                    "dwp.people.hris.performance-migration.baseline-on-migrate=false",
                    "dwp.people.hris.performance-migration.validate-on-migrate=true",
                    "dwp.people.hris.performance-migration.out-of-order=false");

    @Test
    void applicationConfigurationPinsBothMigrationStreams() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                "people", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        FlywayProperties people = Binder.get(environment)
                .bind("spring.flyway", FlywayProperties.class)
                .orElseThrow(() -> new AssertionError("spring.flyway must be configured"));
        PerformanceMigrationStreamProperties performance = Binder.get(environment)
                .bind("dwp.people.hris.performance-migration",
                        PerformanceMigrationStreamProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "Performance migration stream must be configured"));

        assertThat(people.getLocations()).containsExactly("classpath:db/migration");
        assertThat(people.getUrl()).startsWith("jdbc:postgresql:");
        assertThat(people.getUser()).isNotBlank();
        assertThat(people.getPassword()).isNotBlank();
        assertThat(people.getDefaultSchema()).isEqualTo("public");
        assertThat(people.getSchemas()).containsExactly("public");
        assertThat(people.getTable()).isEqualTo("flyway_schema_history");
        assertThat(people.getBaselineVersion()).isEqualTo("0");
        assertThat(people.isValidateOnMigrate()).isTrue();
        assertThat(people.isOutOfOrder()).isFalse();
        assertThat(people.isFailOnMissingLocations()).isTrue();
        assertThat(people.isCleanDisabled()).isTrue();
        assertThat(people.isCreateSchemas()).isFalse();
        assertThat(people.getInstalledBy()).isNull();
        assertThat(people.getOutputQueryResults()).isFalse();
        assertThat(people.getCommunityDbSupportEnabled()).isFalse();
        assertThat(people.getIgnoreMigrationPatterns()).isEmpty();
        assertThat(performance).isEqualTo(PROPERTIES);
        assertThat(new ClassPathResource("db/performance-migration/README.md").exists()).isTrue();
    }

    @Test
    void performanceFlywayUsesItsOwnSchemaHistoryAndClosedSettings() {
        DataSource dataSource = mock(DataSource.class);
        Flyway flyway = PerformanceMigrationStreamBootstrap.performanceFlyway(
                PROPERTIES, getClass().getClassLoader(), dataSource);
        Configuration configuration = flyway.getConfiguration();

        PerformanceMigrationStreamBootstrap.assertPerformanceConfiguration(
                flyway, PROPERTIES, dataSource);

        assertThat(Arrays.stream(configuration.getLocations()).map(Object::toString))
                .containsExactly(PerformanceMigrationStreamProperties.LOCATION);
        assertThat(configuration.getDefaultSchema())
                .isEqualTo(PerformanceMigrationStreamProperties.SCHEMA);
        assertThat(configuration.getSchemas())
                .containsExactly(PerformanceMigrationStreamProperties.SCHEMA);
        assertThat(configuration.isCreateSchemas()).isFalse();
        assertThat(configuration.getTable())
                .isEqualTo(PerformanceMigrationStreamProperties.HISTORY_TABLE);
        assertThat(configuration.getBaselineVersion().getVersion()).isEqualTo("0");
        assertThat(configuration.isBaselineOnMigrate()).isFalse();
        assertThat(configuration.isValidateOnMigrate()).isTrue();
        assertThat(configuration.isOutOfOrder()).isFalse();
        assertThat(configuration.isFailOnMissingLocations()).isTrue();
    }

    @Test
    void performanceEffectiveConfigurationRejectsInstalledByDrift() {
        DataSource dataSource = mock(DataSource.class);
        Flyway hardened = PerformanceMigrationStreamBootstrap.performanceFlyway(
                PROPERTIES, getClass().getClassLoader(), dataSource);
        Flyway drifted = Flyway.configure(getClass().getClassLoader())
                .configuration(hardened.getConfiguration())
                .installedBy("spoofed-principal")
                .load();

        assertThatThrownBy(() -> PerformanceMigrationStreamBootstrap
                .assertPerformanceConfiguration(drifted, PROPERTIES, dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Effective Performance Flyway installedBy drift");
    }

    @Test
    void bootMigrationStrategyRunsPeopleThenPerformanceAndPropagatesFailures() {
        Flyway people = mock(Flyway.class);
        Flyway performance = mock(Flyway.class);
        Configuration peopleConfiguration = mock(Configuration.class);
        DataSource dataSource = mock(DataSource.class);
        when(people.getConfiguration()).thenReturn(peopleConfiguration);
        when(peopleConfiguration.getDataSource()).thenReturn(dataSource);

        new PerformanceMigrationStreamBootstrap(ignored -> performance).migrate(people);

        InOrder order = inOrder(people, peopleConfiguration, performance);
        order.verify(people).migrate();
        order.verify(people).getConfiguration();
        order.verify(peopleConfiguration).getDataSource();
        order.verify(performance).migrate();

        AtomicBoolean secondaryCreated = new AtomicBoolean();
        Flyway failingPeople = mock(Flyway.class);
        doThrow(new IllegalStateException("people migration failed"))
                .when(failingPeople).migrate();
        PerformanceMigrationStreamBootstrap bootstrap = new PerformanceMigrationStreamBootstrap(
                ignored -> {
                    secondaryCreated.set(true);
                    return performance;
                });

        assertThatThrownBy(() -> bootstrap.migrate(failingPeople))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("people migration failed");
        assertThat(secondaryCreated).isFalse();
        verify(performance).migrate();
        verify(failingPeople, never()).getConfiguration();
    }

    @Test
    void performanceFailurePropagatesAndPreventsStartupCompletion() {
        Flyway people = mock(Flyway.class);
        Flyway failingPerformance = mock(Flyway.class);
        Configuration peopleConfiguration = mock(Configuration.class);
        DataSource dataSource = mock(DataSource.class);
        when(people.getConfiguration()).thenReturn(peopleConfiguration);
        when(peopleConfiguration.getDataSource()).thenReturn(dataSource);
        doThrow(new IllegalStateException("performance migration failed"))
                .when(failingPerformance).migrate();

        PerformanceMigrationStreamBootstrap bootstrap =
                new PerformanceMigrationStreamBootstrap(ignored -> failingPerformance);

        assertThatThrownBy(() -> bootstrap.migrate(people))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("performance migration failed");
        verify(people).migrate();
    }

    @Test
    void configurationKeepsBootPrimaryFlywayAndInitializerInControl() {
        Class<?>[] beanReturnTypes = Arrays.stream(
                        PerformanceMigrationStreamConfiguration.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .map(method -> method.getReturnType())
                .toArray(Class<?>[]::new);

        assertThat(beanReturnTypes).contains(
                FlywayMigrationStrategy.class,
                PerformanceMigrationStreamConfiguration.PeopleMigrationInfrastructure.class);
        assertThat(beanReturnTypes).doesNotContain(Flyway.class, FlywayMigrationInitializer.class);
        FlywayMigrationStrategy strategy = new PerformanceMigrationStreamConfiguration()
                .peopleAndPerformanceMigrationStrategy(
                        PEOPLE_PROPERTIES,
                        PeopleFlywayPropertiesGuard.verify(canonicalFlywayProperties()),
                        PROPERTIES,
                        new DefaultResourceLoader(),
                        mock(DataSource.class),
                        com.dwp.core.database.RuntimeMigrationDatabaseGuard
                                .MigrationPrincipalPolicy.STRICT,
                        "test",
                        "test",
                        "application_user",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        "");
        assertThat(strategy).isInstanceOf(PerformanceMigrationStreamBootstrap.class);
    }

    @Test
    void smallContextLoadsStrategyAndRequiresBootMigrationInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FlywayMigrationStrategy.class);
            assertThat(context).hasSingleBean(Flyway.class);
            assertThat(context).hasSingleBean(FlywayMigrationInitializer.class);
            assertThat(context).hasSingleBean(FlywayProperties.class);
            assertThat(context).hasSingleBean(PeopleFlywayPropertiesGuard.class);
            assertThat(context).hasSingleBean(
                    PerformanceMigrationStreamConfiguration.PeopleMigrationInfrastructure.class);
        });
    }

    @Test
    void localSeedProfileRequiresExplicitFlagAndExactOrderedLocations() {
        String localLocations = String.join(",",
                PeopleMigrationStreamProperties.LOCAL_SEED_LOCATIONS);
        contextRunner.withPropertyValues(
                        "dwp.people.hris.local-seed-enabled=true",
                        "spring.flyway.locations=" + localLocations)
                .run(context -> assertThat(context).hasNotFailed());

        contextRunner.withPropertyValues("spring.flyway.locations=" + localLocations)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("spring.flyway.locations must be")
                            .hasStackTraceContaining("classpath:db/migration");
                });

        contextRunner.withPropertyValues(
                        "dwp.people.hris.local-seed-enabled=true",
                        "spring.flyway.locations=classpath:db/migration")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("classpath:db/local-seed");
                });

        contextRunner.withPropertyValues(
                        "dwp.people.hris.local-seed-enabled=true",
                        "spring.flyway.locations=classpath:db/local-seed,classpath:db/migration")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("spring.flyway.locations must be");
                });

        contextRunner.withPropertyValues(
                        "dwp.people.hris.local-seed-enabled=true",
                        "spring.flyway.locations=" + localLocations + ",classpath:db/other")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("spring.flyway.locations must be");
                });

        Flyway localSeedFlyway = Flyway.configure()
                .dataSource(mock(DataSource.class))
                .locations(PeopleMigrationStreamProperties.LOCAL_SEED_LOCATIONS
                        .toArray(String[]::new))
                .load();
        assertThat(Arrays.stream(localSeedFlyway.getConfiguration().getLocations())
                .map(Object::toString))
                .containsExactlyElementsOf(
                        PeopleMigrationStreamProperties.LOCAL_SEED_EFFECTIVE_LOCATIONS);
        assertThat(PerformanceMigrationStreamBootstrap.expectedEffectiveLocations(
                PeopleMigrationStreamProperties.LOCAL_SEED_LOCATIONS))
                .isEqualTo(PeopleMigrationStreamProperties.LOCAL_SEED_EFFECTIVE_LOCATIONS);
    }

    @Test
    void guardClassifiesEverySpringBootFlywayPropertyExactly() {
        Set<String> bootFields = Arrays.stream(FlywayProperties.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getName())
                .collect(Collectors.toSet());

        assertThat(PeopleFlywayPropertiesGuard.classifiedPropertyFields())
                .containsExactlyInAnyOrderElementsOf(bootFields);
        assertThat(PeopleFlywayPropertiesGuard.bootPropertyFields()).isEqualTo(bootFields);
        assertThat(PeopleFlywayPropertiesGuard.classifiedNestedPropertyFields())
                .isEqualTo(PeopleFlywayPropertiesGuard.bootNestedPropertyFields());
    }

    @Test
    void guardRejectsInstalledByProvenanceSpoofBeforeMigration() {
        contextRunner.withPropertyValues("spring.flyway.installed-by=spoofed-principal")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("spring.flyway.installedBy must be <unset>")
                            .hasStackTraceContaining("got spoofed-principal");
                });
    }

    @Test
    void guardRejectsPreviouslyUnclassifiedBootProperties() {
        contextRunner.withPropertyValues("spring.flyway.connect-retries=1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("spring.flyway.connectRetries must be 0");
                });

        contextRunner.withPropertyValues("spring.flyway.postgresql.transactional-lock=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("spring.flyway.postgresql must be");
                });
    }

    @Test
    void guardRequiresExplicitPostgresMigrationCredentialsWithoutLeakingSecrets() {
        FlywayProperties missingUrl = canonicalFlywayProperties();
        missingUrl.setUrl(null);
        assertThatThrownBy(() -> PeopleFlywayPropertiesGuard.verify(missingUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spring.flyway.url")
                .hasMessageNotContaining(MIGRATION_PASSWORD);

        FlywayProperties missingUser = canonicalFlywayProperties();
        missingUser.setUser(" ");
        assertThatThrownBy(() -> PeopleFlywayPropertiesGuard.verify(missingUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spring.flyway.user")
                .hasMessageNotContaining(MIGRATION_PASSWORD);

        FlywayProperties missingPassword = canonicalFlywayProperties();
        missingPassword.setPassword("");
        assertThatThrownBy(() -> PeopleFlywayPropertiesGuard.verify(missingPassword))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spring.flyway.password")
                .hasMessageNotContaining(MIGRATION_PASSWORD);
    }

    @Test
    void propertiesRejectConfigurationDrift() {
        assertThatThrownBy(() -> new PerformanceMigrationStreamProperties(
                "classpath:db/migration", PROPERTIES.schema(), PROPERTIES.historyTable(),
                PROPERTIES.baselineVersion(), false, true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PerformanceMigrationStreamProperties(
                PROPERTIES.location(), PROPERTIES.schema(), PROPERTIES.historyTable(),
                PROPERTIES.baselineVersion(), false, false, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PerformanceMigrationStreamProperties(
                PROPERTIES.location(), PROPERTIES.schema(), PROPERTIES.historyTable(),
                PROPERTIES.baselineVersion(), false, true, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PerformanceMigrationStreamProperties(
                PROPERTIES.location(), PROPERTIES.schema(), PROPERTIES.historyTable(),
                PROPERTIES.baselineVersion(), true, true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void peoplePropertiesRejectDisabledOrDriftedPrimaryMigrationStream() {
        assertThatThrownBy(() -> peopleProperties(false, PeopleMigrationStreamProperties.LOCATIONS,
                PeopleMigrationStreamProperties.SCHEMA, true, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enabled");
        assertThatThrownBy(() -> peopleProperties(true, java.util.List.of("classpath:db/other"),
                PeopleMigrationStreamProperties.SCHEMA, true, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locations");
        assertThatThrownBy(() -> peopleProperties(true, PeopleMigrationStreamProperties.LOCATIONS,
                "other", true, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultSchema");
        assertThatThrownBy(() -> peopleProperties(true, PeopleMigrationStreamProperties.LOCATIONS,
                PeopleMigrationStreamProperties.SCHEMA, false, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validateOnMigrate");
        assertThatThrownBy(() -> peopleProperties(true, PeopleMigrationStreamProperties.LOCATIONS,
                PeopleMigrationStreamProperties.SCHEMA, true, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failOnMissingLocations");
        assertThatThrownBy(() -> peopleProperties(true, PeopleMigrationStreamProperties.LOCATIONS,
                PeopleMigrationStreamProperties.SCHEMA, true, true, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outOfOrder");
    }

    private static PeopleMigrationStreamProperties peopleProperties(
            boolean enabled,
            java.util.List<String> locations,
            String defaultSchema,
            boolean validateOnMigrate,
            boolean failOnMissingLocations,
            boolean outOfOrder) {
        return new PeopleMigrationStreamProperties(
                enabled,
                locations,
                defaultSchema,
                java.util.List.of(PeopleMigrationStreamProperties.SCHEMA),
                PeopleMigrationStreamProperties.HISTORY_TABLE,
                true,
                PeopleMigrationStreamProperties.BASELINE_VERSION,
                validateOnMigrate,
                outOfOrder,
                failOnMissingLocations,
                true,
                false,
                java.nio.charset.StandardCharsets.UTF_8,
                PeopleMigrationStreamProperties.TARGET,
                MIGRATION_URL,
                MIGRATION_USER,
                MIGRATION_PASSWORD,
                null,
                java.util.List.of(),
                java.util.Map.of(),
                true,
                "${",
                "}",
                ":",
                "V",
                java.util.List.of(".sql"),
                "__",
                "R",
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                false,
                java.util.List.of());
    }

    private static FlywayProperties canonicalFlywayProperties() {
        FlywayProperties properties = new FlywayProperties();
        properties.setFailOnMissingLocations(true);
        properties.setUrl(MIGRATION_URL);
        properties.setUser(MIGRATION_USER);
        properties.setPassword(MIGRATION_PASSWORD);
        properties.setDefaultSchema(PeopleMigrationStreamProperties.SCHEMA);
        properties.setSchemas(java.util.List.of(PeopleMigrationStreamProperties.SCHEMA));
        properties.setBaselineOnMigrate(true);
        properties.setBaselineVersion(PeopleMigrationStreamProperties.BASELINE_VERSION);
        properties.setValidateMigrationNaming(true);
        properties.setOutputQueryResults(false);
        properties.setCommunityDbSupportEnabled(false);
        properties.setCreateSchemas(false);
        properties.setSkipExecutingMigrations(false);
        properties.setIgnoreMigrationPatterns(java.util.List.of());
        return properties;
    }
}
