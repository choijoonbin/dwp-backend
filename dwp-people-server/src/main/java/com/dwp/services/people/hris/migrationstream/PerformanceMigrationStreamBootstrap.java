package com.dwp.services.people.hris.migrationstream;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javax.sql.DataSource;

import com.dwp.core.database.DomainEventLedgerRuntimeGuard;
import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.MigrationControlRunReceiptGuard;
import com.dwp.core.database.OwnerTriggerExecutionBoundaryGuard;
import com.dwp.core.database.RuntimeMigrationDatabaseGuard;
import com.dwp.core.database.RuntimeMigrationDatabaseGuard.MigrationPrincipalPolicy;
import com.dwp.core.database.RuntimeRoutineExecutionGuard;
import com.dwp.core.database.RuntimeTablePrivilegeGuard;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

/** Runs the existing People stream before the isolated Performance stream. */
public final class PerformanceMigrationStreamBootstrap implements FlywayMigrationStrategy {

    private static final Set<RuntimeRoutineExecutionGuard.Routine> RUNTIME_ROUTINES = Set.of(
            new RuntimeRoutineExecutionGuard.Routine(
                    "public", "seed_hr_domain_foundation", "bigint"));
    private static final Set<RuntimeTablePrivilegeGuard.TablePrivilege>
            DENIED_AUDIT_TABLE_PRIVILEGES = Set.of(
                    new RuntimeTablePrivilegeGuard.TablePrivilege(
                            PeopleMigrationStreamProperties.SCHEMA,
                            "sys_people_audit_events",
                            "UPDATE"),
                    new RuntimeTablePrivilegeGuard.TablePrivilege(
                            PeopleMigrationStreamProperties.SCHEMA,
                            "sys_people_audit_events",
                            "DELETE"));

    private static final MigrationAdoptionGuard.Contract PEOPLE_ADOPTION_CONTRACT =
            new MigrationAdoptionGuard.Contract(
                    "people-main",
                    PeopleMigrationStreamProperties.SCHEMA,
                    PeopleMigrationStreamProperties.HISTORY_TABLE,
                    List.of(PeopleMigrationStreamProperties.SCHEMA));
    private static final MigrationAdoptionGuard.Contract PERFORMANCE_ADOPTION_CONTRACT =
            new MigrationAdoptionGuard.Contract(
                    "people-performance",
                    PerformanceMigrationStreamProperties.SCHEMA,
                    PerformanceMigrationStreamProperties.HISTORY_TABLE,
                    List.of(PerformanceMigrationStreamProperties.SCHEMA));

    private final Function<DataSource, Flyway> performanceFlywayFactory;
    private final PeopleMigrationStreamProperties peopleProperties;
    private final PeopleFlywayPropertiesGuard propertiesGuard;
    private final PerformanceMigrationStreamProperties performanceProperties;
    private final DataSource applicationDataSource;
    private final MigrationPrincipalPolicy migrationPrincipalPolicy;
    private final String runtimeEnvironment;
    private final String serviceInstance;
    private final String configuredApplicationUser;
    private final String peopleReceiptSha256;
    private final String peopleControlReference;
    private final String performanceReceiptSha256;
    private final String performanceControlReference;
    private final String controlRunReceiptJson;
    private final String controlRunReceiptSha256;
    private final String migrationControlReference;

    public PerformanceMigrationStreamBootstrap(
            PeopleMigrationStreamProperties peopleProperties,
            PeopleFlywayPropertiesGuard propertiesGuard,
            PerformanceMigrationStreamProperties properties,
            ClassLoader classLoader,
            DataSource applicationDataSource,
            MigrationPrincipalPolicy migrationPrincipalPolicy,
            String runtimeEnvironment,
            String serviceInstance,
            String configuredApplicationUser,
            String peopleReceiptSha256,
            String peopleControlReference,
            String performanceReceiptSha256,
            String performanceControlReference,
            String controlRunReceiptJson,
            String controlRunReceiptSha256,
            String migrationControlReference) {
        this.peopleProperties = Objects.requireNonNull(
                peopleProperties, "peopleProperties must not be null");
        this.propertiesGuard = Objects.requireNonNull(
                propertiesGuard, "propertiesGuard must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        performanceProperties = properties;
        Objects.requireNonNull(classLoader, "classLoader must not be null");
        this.applicationDataSource = Objects.requireNonNull(
                applicationDataSource, "applicationDataSource must not be null");
        this.migrationPrincipalPolicy = Objects.requireNonNull(
                migrationPrincipalPolicy, "migrationPrincipalPolicy must not be null");
        this.runtimeEnvironment = Objects.requireNonNull(
                runtimeEnvironment, "runtimeEnvironment must not be null");
        this.serviceInstance = Objects.requireNonNull(
                serviceInstance, "serviceInstance must not be null");
        this.configuredApplicationUser = Objects.requireNonNull(
                configuredApplicationUser, "configuredApplicationUser must not be null");
        this.peopleReceiptSha256 = Objects.requireNonNull(
                peopleReceiptSha256, "peopleReceiptSha256 must not be null");
        this.peopleControlReference = Objects.requireNonNull(
                peopleControlReference, "peopleControlReference must not be null");
        this.performanceReceiptSha256 = Objects.requireNonNull(
                performanceReceiptSha256, "performanceReceiptSha256 must not be null");
        this.performanceControlReference = Objects.requireNonNull(
                performanceControlReference, "performanceControlReference must not be null");
        this.controlRunReceiptJson = Objects.requireNonNull(
                controlRunReceiptJson, "controlRunReceiptJson must not be null");
        this.controlRunReceiptSha256 = Objects.requireNonNull(
                controlRunReceiptSha256, "controlRunReceiptSha256 must not be null");
        this.migrationControlReference = Objects.requireNonNull(
                migrationControlReference, "migrationControlReference must not be null");
        performanceFlywayFactory = dataSource -> {
            assertSafeInitialSchema(properties, dataSource);
            return performanceFlyway(properties, classLoader, dataSource);
        };
    }

    PerformanceMigrationStreamBootstrap(
            PerformanceMigrationStreamProperties properties,
            ClassLoader classLoader) {
        peopleProperties = null;
        propertiesGuard = null;
        performanceProperties = properties;
        applicationDataSource = null;
        migrationPrincipalPolicy = null;
        runtimeEnvironment = null;
        serviceInstance = null;
        configuredApplicationUser = null;
        peopleReceiptSha256 = null;
        peopleControlReference = null;
        performanceReceiptSha256 = null;
        performanceControlReference = null;
        controlRunReceiptJson = null;
        controlRunReceiptSha256 = null;
        migrationControlReference = null;
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(classLoader, "classLoader must not be null");
        performanceFlywayFactory = dataSource -> {
            assertSafeInitialSchema(properties, dataSource);
            return performanceFlyway(properties, classLoader, dataSource);
        };
    }

    PerformanceMigrationStreamBootstrap(Function<DataSource, Flyway> performanceFlywayFactory) {
        peopleProperties = null;
        propertiesGuard = null;
        performanceProperties = null;
        applicationDataSource = null;
        migrationPrincipalPolicy = null;
        runtimeEnvironment = null;
        serviceInstance = null;
        configuredApplicationUser = null;
        peopleReceiptSha256 = null;
        peopleControlReference = null;
        performanceReceiptSha256 = null;
        performanceControlReference = null;
        controlRunReceiptJson = null;
        controlRunReceiptSha256 = null;
        migrationControlReference = null;
        this.performanceFlywayFactory = Objects.requireNonNull(
                performanceFlywayFactory, "performanceFlywayFactory must not be null");
    }

    @Override
    public void migrate(Flyway peopleFlyway) {
        Objects.requireNonNull(peopleFlyway, "peopleFlyway must not be null");
        DataSource configuredMigrationDataSource = null;
        if (peopleProperties != null) {
            configuredMigrationDataSource = Objects.requireNonNull(
                    peopleFlyway.getConfiguration().getDataSource(),
                    "People Flyway dataSource must not be null");
            assertPrimaryConfiguration(
                    peopleFlyway,
                    peopleProperties,
                    applicationDataSource,
                    migrationPrincipalPolicy,
                    runtimeEnvironment,
                    serviceInstance,
                    configuredApplicationUser);
            propertiesGuard.verifyEffectiveConfiguration(peopleFlyway);
        }
        final DataSource preflightMigrationDataSource = configuredMigrationDataSource;
        MigrationControlRunReceiptGuard.RunReceipt controlRunReceipt = null;
        if (migrationPrincipalPolicy == MigrationPrincipalPolicy.STRICT) {
            controlRunReceipt = MigrationControlRunReceiptGuard.verify(
                    "people",
                    List.of(PEOPLE_ADOPTION_CONTRACT, PERFORMANCE_ADOPTION_CONTRACT),
                    preflightMigrationDataSource,
                    peopleProperties.user(),
                    migrationControlReference,
                    controlRunReceiptJson,
                    controlRunReceiptSha256);
        }
        MigrationAdoptionGuard.AdoptionState peopleAdoption = null;
        if (migrationPrincipalPolicy == MigrationPrincipalPolicy.STRICT) {
            peopleAdoption = MigrationAdoptionGuard.verifyBeforeMigration(
                    PEOPLE_ADOPTION_CONTRACT,
                    preflightMigrationDataSource,
                    applicationDataSource,
                    peopleProperties.user(),
                    peopleReceiptSha256,
                    peopleControlReference);
            MigrationControlRunReceiptGuard.verifyAdoptionBinding(
                    controlRunReceipt,
                    PEOPLE_ADOPTION_CONTRACT,
                    peopleReceiptSha256,
                    peopleControlReference,
                    peopleAdoption.receipt());
            MigrationAdoptionGuard.requireNoPendingBeforeMigration(
                    PEOPLE_ADOPTION_CONTRACT, peopleFlyway, peopleAdoption, true);
        }
        peopleFlyway.migrate();
        DataSource migrationDataSource = configuredMigrationDataSource != null
                ? configuredMigrationDataSource
                : Objects.requireNonNull(
                        peopleFlyway.getConfiguration().getDataSource(),
                        "People Flyway dataSource must not be null");
        if (applicationDataSource != null) {
            RuntimeMigrationDatabaseGuard.hardenAndVerifyHistoryTables(
                    "People",
                    migrationDataSource,
                    applicationDataSource,
                    List.of(new RuntimeMigrationDatabaseGuard.HistoryTable(
                            PeopleMigrationStreamProperties.SCHEMA,
                            PeopleMigrationStreamProperties.HISTORY_TABLE)));
        }
        Flyway performanceFlyway = Objects.requireNonNull(
                performanceFlywayFactory.apply(migrationDataSource),
                "performanceFlywayFactory must return a Flyway instance");
        if (performanceProperties != null) {
            assertPerformanceConfiguration(
                    performanceFlyway, performanceProperties, migrationDataSource);
        }
        MigrationAdoptionGuard.AdoptionState performanceAdoption = null;
        if (migrationPrincipalPolicy == MigrationPrincipalPolicy.STRICT) {
            performanceAdoption = MigrationAdoptionGuard.verifyBeforeMigration(
                    PERFORMANCE_ADOPTION_CONTRACT,
                    migrationDataSource,
                    applicationDataSource,
                    peopleProperties.user(),
                    performanceReceiptSha256,
                    performanceControlReference);
            MigrationControlRunReceiptGuard.verifyAdoptionBinding(
                    controlRunReceipt,
                    PERFORMANCE_ADOPTION_CONTRACT,
                    performanceReceiptSha256,
                    performanceControlReference,
                    performanceAdoption.receipt());
            MigrationAdoptionGuard.requireNoPendingBeforeMigration(
                    PERFORMANCE_ADOPTION_CONTRACT,
                    performanceFlyway,
                    performanceAdoption,
                    true);
        }
        performanceFlyway.migrate();
        if (applicationDataSource != null) {
            RuntimeMigrationDatabaseGuard.hardenAndVerifyHistoryTables(
                    "People Performance",
                    migrationDataSource,
                    applicationDataSource,
                    List.of(new RuntimeMigrationDatabaseGuard.HistoryTable(
                            PerformanceMigrationStreamProperties.SCHEMA,
                            PerformanceMigrationStreamProperties.HISTORY_TABLE)));
            assertPrimaryConfiguration(
                    peopleFlyway,
                    peopleProperties,
                    applicationDataSource,
                    migrationPrincipalPolicy,
                    runtimeEnvironment,
                    serviceInstance,
                    configuredApplicationUser);
        }
        if (migrationPrincipalPolicy == MigrationPrincipalPolicy.STRICT) {
            MigrationAdoptionGuard.requireNoPendingAfterMigration(
                    PEOPLE_ADOPTION_CONTRACT, peopleFlyway, peopleAdoption);
            MigrationAdoptionGuard.requireNoPendingAfterMigration(
                    PERFORMANCE_ADOPTION_CONTRACT, performanceFlyway, performanceAdoption);
            MigrationAdoptionGuard.verifyAfterMigration(
                    PEOPLE_ADOPTION_CONTRACT,
                    migrationDataSource,
                    applicationDataSource,
                    peopleProperties.user(),
                    peopleReceiptSha256,
                    peopleControlReference,
                    peopleAdoption);
            MigrationAdoptionGuard.verifyAfterMigration(
                    PERFORMANCE_ADOPTION_CONTRACT,
                    migrationDataSource,
                    applicationDataSource,
                    peopleProperties.user(),
                    performanceReceiptSha256,
                    performanceControlReference,
                    performanceAdoption);
            MigrationControlRunReceiptGuard.verifyAdoptionBinding(
                    controlRunReceipt,
                    PEOPLE_ADOPTION_CONTRACT,
                    peopleReceiptSha256,
                    peopleControlReference,
                    peopleAdoption.receipt());
            MigrationControlRunReceiptGuard.verifyAdoptionBinding(
                    controlRunReceipt,
                    PERFORMANCE_ADOPTION_CONTRACT,
                    performanceReceiptSha256,
                    performanceControlReference,
                    performanceAdoption.receipt());
            MigrationControlRunReceiptGuard.verifyNativeStateUnchanged(
                    controlRunReceipt,
                    List.of(PEOPLE_ADOPTION_CONTRACT, PERFORMANCE_ADOPTION_CONTRACT),
                    migrationDataSource,
                    peopleProperties.user());
            RuntimeRoutineExecutionGuard.verifyExact(
                    "People", applicationDataSource,
                    List.of(PeopleMigrationStreamProperties.SCHEMA,
                            PerformanceMigrationStreamProperties.SCHEMA),
                    RUNTIME_ROUTINES);
            DomainEventLedgerRuntimeGuard.verify(
                    "People", applicationDataSource,
                    DomainEventLedgerRuntimeGuard.Profile.ACTIVE);
            RuntimeTablePrivilegeGuard.verifyDenied(
                    "People", applicationDataSource, DENIED_AUDIT_TABLE_PRIVILEGES);
            OwnerTriggerExecutionBoundaryGuard.verify(
                    "People", applicationDataSource,
                    List.of(PeopleMigrationStreamProperties.SCHEMA,
                            PerformanceMigrationStreamProperties.SCHEMA),
                    peopleProperties.user());
        }
    }

    static Flyway performanceFlyway(
            PerformanceMigrationStreamProperties properties,
            ClassLoader classLoader,
            DataSource dataSource) {
        return Flyway.configure(classLoader)
                .dataSource(Objects.requireNonNull(dataSource, "dataSource must not be null"))
                .locations(properties.location())
                .defaultSchema(properties.schema())
                .schemas(properties.schema())
                .createSchemas(false)
                .table(properties.historyTable())
                .baselineOnMigrate(properties.baselineOnMigrate())
                .baselineVersion(MigrationVersion.fromVersion(properties.baselineVersion()))
                .baselineDescription("<< Flyway Baseline >>")
                .validateOnMigrate(properties.validateOnMigrate())
                .outOfOrder(properties.outOfOrder())
                .failOnMissingLocations(true)
                .encoding(StandardCharsets.UTF_8)
                .target(MigrationVersion.LATEST)
                .connectRetries(0)
                .connectRetriesInterval(120)
                .lockRetryCount(50)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .sqlMigrationPrefix("V")
                .sqlMigrationSuffixes(".sql")
                .sqlMigrationSeparator("__")
                .repeatableSqlMigrationPrefix("R")
                .placeholderReplacement(true)
                .placeholderPrefix("${")
                .placeholderSuffix("}")
                .placeholderSeparator(":")
                .scriptPlaceholderPrefix("FP__")
                .scriptPlaceholderSuffix("__")
                .executeInTransaction(true)
                .skipExecutingMigrations(false)
                .ignoreMigrationPatterns(new String[0])
                .loggers("slf4j")
                .jdbcProperties(Map.of())
                .batch(false)
                .stream(false)
                .outputQueryResults(false)
                .detectEncoding(false)
                .communityDBSupportEnabled(false)
                .group(false)
                .mixed(false)
                .skipDefaultCallbacks(false)
                .skipDefaultResolvers(false)
                .load();
    }

    static void assertPerformanceConfiguration(
            Flyway flyway,
            PerformanceMigrationStreamProperties expected,
            DataSource dataSource) {
        var actual = Objects.requireNonNull(flyway, "flyway must not be null").getConfiguration();
        requirePerformanceIdentity("dataSource", actual.getDataSource(), dataSource);
        requirePerformanceExact("url", actual.getUrl(), null);
        requirePerformanceExact("user", actual.getUser(), null);
        requirePerformanceExact("password", actual.getPassword(), null);
        requirePerformanceExact("driver", actual.getDriver(), null);
        requirePerformanceExact("locations",
                Arrays.stream(actual.getLocations()).map(Object::toString).toList(),
                List.of(expected.location()));
        requirePerformanceExact("defaultSchema", actual.getDefaultSchema(), expected.schema());
        requirePerformanceExact("schemas", Arrays.asList(actual.getSchemas()),
                List.of(expected.schema()));
        requirePerformanceExact("createSchemas", actual.isCreateSchemas(), false);
        requirePerformanceExact("table", actual.getTable(), expected.historyTable());
        requirePerformanceExact("tablespace", actual.getTablespace(), null);
        requirePerformanceExact("baselineOnMigrate", actual.isBaselineOnMigrate(),
                expected.baselineOnMigrate());
        requirePerformanceExact("baselineVersion", actual.getBaselineVersion().getVersion(),
                expected.baselineVersion());
        requirePerformanceExact("baselineDescription", actual.getBaselineDescription(),
                "<< Flyway Baseline >>");
        requirePerformanceExact("validateOnMigrate", actual.isValidateOnMigrate(),
                expected.validateOnMigrate());
        requirePerformanceExact("outOfOrder", actual.isOutOfOrder(), expected.outOfOrder());
        requirePerformanceExact("failOnMissingLocations", actual.isFailOnMissingLocations(), true);
        requirePerformanceExact("cleanDisabled", actual.isCleanDisabled(), true);
        requirePerformanceExact("encoding", actual.getEncoding(), StandardCharsets.UTF_8);
        requirePerformanceExact("target", actual.getTarget(), MigrationVersion.LATEST);
        requirePerformanceExact("connectRetries", actual.getConnectRetries(), 0);
        requirePerformanceExact("connectRetriesInterval",
                actual.getConnectRetriesInterval(), 120);
        requirePerformanceExact("lockRetryCount", actual.getLockRetryCount(), 50);
        requirePerformanceExact("installedBy", actual.getInstalledBy(), null);
        requirePerformanceExact("initSql", actual.getInitSql(), null);
        requirePerformanceExact("placeholderReplacement",
                actual.isPlaceholderReplacement(), true);
        requirePerformanceExact("placeholders", actual.getPlaceholders(), Map.of());
        requirePerformanceExact("placeholderPrefix", actual.getPlaceholderPrefix(), "${");
        requirePerformanceExact("placeholderSuffix", actual.getPlaceholderSuffix(), "}");
        requirePerformanceExact("placeholderSeparator", actual.getPlaceholderSeparator(), ":");
        requirePerformanceExact("sqlMigrationPrefix", actual.getSqlMigrationPrefix(), "V");
        requirePerformanceExact("sqlMigrationSuffixes",
                Arrays.asList(actual.getSqlMigrationSuffixes()), List.of(".sql"));
        requirePerformanceExact("sqlMigrationSeparator", actual.getSqlMigrationSeparator(), "__");
        requirePerformanceExact("repeatableSqlMigrationPrefix",
                actual.getRepeatableSqlMigrationPrefix(), "R");
        requirePerformanceExact("scriptPlaceholderPrefix",
                actual.getScriptPlaceholderPrefix(), "FP__");
        requirePerformanceExact("scriptPlaceholderSuffix",
                actual.getScriptPlaceholderSuffix(), "__");
        requirePerformanceExact("executeInTransaction", actual.isExecuteInTransaction(), true);
        requirePerformanceExact("skipExecutingMigrations",
                actual.isSkipExecutingMigrations(), false);
        requirePerformanceExact("ignoreMigrationPatterns",
                Arrays.stream(actual.getIgnoreMigrationPatterns()).map(Object::toString).toList(),
                List.of());
        requirePerformanceExact("validateMigrationNaming",
                actual.isValidateMigrationNaming(), true);
        requirePerformanceExact("group", actual.isGroup(), false);
        requirePerformanceExact("mixed", actual.isMixed(), false);
        requirePerformanceExact("skipDefaultCallbacks", actual.isSkipDefaultCallbacks(), false);
        requirePerformanceExact("skipDefaultResolvers", actual.isSkipDefaultResolvers(), false);
        requirePerformanceExact("callbacks", actual.getCallbacks().length, 0);
        requirePerformanceExact("resolvers", actual.getResolvers().length, 0);
        requirePerformanceExact("javaMigrations", actual.getJavaMigrations().length, 0);
        requirePerformanceExact("loggers", Arrays.asList(actual.getLoggers()),
                List.of("slf4j"));
        requirePerformanceExact("errorOverrides", Arrays.asList(actual.getErrorOverrides()),
                List.of());
        requirePerformanceExact("jdbcProperties", actual.getJdbcProperties(), Map.of());
        requirePerformanceExact("batch", actual.isBatch(), false);
        requirePerformanceExact("stream", actual.isStream(), false);
        requirePerformanceExact("outputQueryResults", actual.isOutputQueryResults(), false);
        requirePerformanceExact("detectEncoding", actual.isDetectEncoding(), false);
        requirePerformanceExact("communityDbSupportEnabled",
                actual.isCommunityDBSupportEnabled(), false);
        requirePerformanceExact("dryRunOutput", actual.getDryRunOutput(), null);
        requirePerformanceExact("kerberosConfigFile", actual.getKerberosConfigFile(), "");
        PostgreSQLConfigurationExtension postgresql = actual.getConfigurationExtension(
                PostgreSQLConfigurationExtension.class);
        requirePerformanceExact("postgresql.transactionalLock",
                postgresql.isTransactionalLock(), true);
        requirePerformanceExact("postgresql.transactional", postgresql.getTransactional(), null);
    }

    static void assertPrimaryConfiguration(
            Flyway flyway,
            PeopleMigrationStreamProperties expected,
            DataSource applicationDataSource,
            MigrationPrincipalPolicy migrationPrincipalPolicy,
            String runtimeEnvironment,
            String serviceInstance,
            String configuredApplicationUser) {
        var actual = flyway.getConfiguration();
        DataSource migrationDataSource = Objects.requireNonNull(
                actual.getDataSource(), "People Flyway dataSource must not be null");
        RuntimeMigrationDatabaseGuard.verify(
                "People",
                migrationDataSource,
                applicationDataSource,
                expected.user(),
                configuredApplicationUser,
                PeopleMigrationStreamProperties.SCHEMA,
                List.of(
                        PeopleMigrationStreamProperties.SCHEMA,
                        PerformanceMigrationStreamProperties.SCHEMA),
                migrationPrincipalPolicy,
                runtimeEnvironment,
                serviceInstance,
                "dwp_user",
                "dwp_people");
        requireExact("locations",
                Arrays.stream(actual.getLocations()).map(Object::toString).toList(),
                expectedEffectiveLocations(expected.locations()));
        requireExact("defaultSchema", actual.getDefaultSchema(), expected.defaultSchema());
        requireExact("schemas", Arrays.asList(actual.getSchemas()), expected.schemas());
        requireExact("table", actual.getTable(), expected.table());
        requireExact("baselineOnMigrate", actual.isBaselineOnMigrate(),
                expected.baselineOnMigrate());
        requireExact("baselineVersion", actual.getBaselineVersion().getVersion(),
                expected.baselineVersion());
        requireExact("validateOnMigrate", actual.isValidateOnMigrate(),
                expected.validateOnMigrate());
        requireExact("outOfOrder", actual.isOutOfOrder(), expected.outOfOrder());
        requireExact("failOnMissingLocations", actual.isFailOnMissingLocations(),
                expected.failOnMissingLocations());
        requireExact("cleanDisabled", actual.isCleanDisabled(), expected.cleanDisabled());
        requireExact("createSchemas", actual.isCreateSchemas(), expected.createSchemas());
        requireExact("encoding", actual.getEncoding(), expected.encoding());
        requireExact("target", actual.getTarget(), MigrationVersion.LATEST);
        requireExact("placeholderReplacement", actual.isPlaceholderReplacement(),
                expected.placeholderReplacement());
        requireExact("placeholders", actual.getPlaceholders(), expected.placeholders());
        requireExact("placeholderPrefix", actual.getPlaceholderPrefix(),
                expected.placeholderPrefix());
        requireExact("placeholderSuffix", actual.getPlaceholderSuffix(),
                expected.placeholderSuffix());
        requireExact("placeholderSeparator", actual.getPlaceholderSeparator(),
                expected.placeholderSeparator());
        requireExact("sqlMigrationPrefix", actual.getSqlMigrationPrefix(),
                expected.sqlMigrationPrefix());
        requireExact("sqlMigrationSuffixes", Arrays.asList(actual.getSqlMigrationSuffixes()),
                expected.sqlMigrationSuffixes());
        requireExact("sqlMigrationSeparator", actual.getSqlMigrationSeparator(),
                expected.sqlMigrationSeparator());
        requireExact("repeatableSqlMigrationPrefix", actual.getRepeatableSqlMigrationPrefix(),
                expected.repeatableSqlMigrationPrefix());
        requireExact("group", actual.isGroup(), expected.group());
        requireExact("mixed", actual.isMixed(), expected.mixed());
        requireExact("skipDefaultCallbacks", actual.isSkipDefaultCallbacks(),
                expected.skipDefaultCallbacks());
        requireExact("skipDefaultResolvers", actual.isSkipDefaultResolvers(),
                expected.skipDefaultResolvers());
        requireExact("validateMigrationNaming", actual.isValidateMigrationNaming(),
                expected.validateMigrationNaming());
        requireExact("executeInTransaction", actual.isExecuteInTransaction(),
                expected.executeInTransaction());
        requireExact("skipExecutingMigrations", actual.isSkipExecutingMigrations(),
                expected.skipExecutingMigrations());
        requireExact("ignoreMigrationPatterns",
                Arrays.stream(actual.getIgnoreMigrationPatterns()).map(Object::toString).toList(),
                expected.ignoreMigrationPatterns());
        requireExact("customCallbacks", actual.getCallbacks().length, 0);
        requireExact("customResolvers", actual.getResolvers().length, 0);
        requireExact("javaMigrations", actual.getJavaMigrations().length, 0);
    }

    static void assertSafeInitialSchema(
            PerformanceMigrationStreamProperties properties,
            DataSource dataSource) {
        String sql = """
                SELECT
                    EXISTS (
                        SELECT 1
                          FROM pg_catalog.pg_namespace namespace
                         WHERE namespace.nspname = ?
                    ) AS schema_exists,
                    EXISTS (
                        SELECT 1
                          FROM pg_catalog.pg_namespace namespace
                          JOIN pg_catalog.pg_roles owner ON owner.oid = namespace.nspowner
                         WHERE namespace.nspname = ?
                           AND owner.rolname = current_user
                    ) AS schema_owned,
                    EXISTS (
                        SELECT 1
                          FROM information_schema.tables
                         WHERE table_schema = ?
                           AND table_name = ?
                    ) AS history_exists,
                    (
                        SELECT COUNT(*)
                          FROM pg_catalog.pg_class object
                          JOIN pg_catalog.pg_namespace namespace
                            ON namespace.oid = object.relnamespace
                         WHERE namespace.nspname = ?
                           AND object.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
                    ) + (
                        SELECT COUNT(*)
                          FROM pg_catalog.pg_proc routine
                          JOIN pg_catalog.pg_namespace namespace
                            ON namespace.oid = routine.pronamespace
                         WHERE namespace.nspname = ?
                    ) + (
                        SELECT COUNT(*)
                          FROM pg_catalog.pg_type data_type
                          JOIN pg_catalog.pg_namespace namespace
                            ON namespace.oid = data_type.typnamespace
                         WHERE namespace.nspname = ?
                           AND data_type.typtype IN ('d', 'e')
                    ) AS object_count
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, properties.schema());
            statement.setString(2, properties.schema());
            statement.setString(3, properties.schema());
            statement.setString(4, properties.historyTable());
            statement.setString(5, properties.schema());
            statement.setString(6, properties.schema());
            statement.setString(7, properties.schema());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException(
                            "Performance migration schema inspection returned no result");
                }
                if (!result.getBoolean("schema_exists")
                        || !result.getBoolean("schema_owned")) {
                    throw new IllegalStateException(
                            "Performance migration schema must be preprovisioned and owned "
                                    + "by the migration principal " + properties.schema());
                }
                boolean historyExists = result.getBoolean("history_exists");
                long objectCount = result.getLong("object_count");
                if (!historyExists && objectCount > 0) {
                    throw new IllegalStateException(
                            "Refusing to baseline unmanaged nonempty Performance schema "
                                    + properties.schema());
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify ownership of Performance migration schema "
                            + properties.schema(),
                    exception);
        }
    }

    private static void requireExact(String name, Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalStateException(
                    "Effective primary Flyway " + name + " drift: expected "
                            + expected + ", got " + actual);
        }
    }

    static List<String> expectedEffectiveLocations(List<String> configuredLocations) {
        return PeopleMigrationStreamProperties.LOCAL_SEED_LOCATIONS.equals(configuredLocations)
                ? PeopleMigrationStreamProperties.LOCAL_SEED_EFFECTIVE_LOCATIONS
                : configuredLocations;
    }

    private static void requirePerformanceExact(String name, Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            throw new IllegalStateException(
                    "Effective Performance Flyway " + name + " drift: expected "
                            + expected + ", got " + actual);
        }
    }

    private static void requirePerformanceIdentity(
            String name,
            Object actual,
            Object expected) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Effective Performance Flyway " + name
                            + " is not the People-owned DataSource instance");
        }
    }

}
