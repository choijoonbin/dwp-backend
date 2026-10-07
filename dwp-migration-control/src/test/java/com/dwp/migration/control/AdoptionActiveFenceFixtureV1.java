package com.dwp.migration.control;

import java.io.File;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.dwp.core.database.MigrationAdoptionGuard.Receipt;
import org.testcontainers.containers.PostgreSQLContainer;

/** Owned native reproduction fixture. Neither its predecessor nor secrets are a production issuer. */
final class AdoptionActiveFenceFixtureV1 {
    static final String DATABASE = "adoption_active_fixture";
    static final String MIGRATION = "adoption_active_migration";
    static final String RUNTIME = "adoption_active_runtime";
    static final String METADATA = "dwp_provider_metadata_auth";
    static final String OLD_REFERENCE = "dwp-migration-control-v2:" + "a".repeat(64);
    static final String CURRENT_REFERENCE = "dwp-migration-control-v2:" + "b".repeat(64);
    private final PostgreSQLContainer<?> postgres;
    private final String password = UUID.randomUUID().toString();
    private final String url;
    private ControlEnvironment environment;
    private Receipt receipt;

    AdoptionActiveFenceFixtureV1(PostgreSQLContainer<?> postgres) {
        this.postgres = postgres;
        url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + DATABASE);
    }

    void provision(boolean existingReceipt) throws Exception {
        try (Connection bootstrap = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            sql(bootstrap, "DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            for (String role : List.of(MIGRATION, RUNTIME, METADATA)) {
                sql(bootstrap, "DROP ROLE IF EXISTS " + role);
                sql(bootstrap, "CREATE ROLE " + role + " LOGIN NOINHERIT NOSUPERUSER NOCREATEDB "
                        + "NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD " + ControlValues.quoteLiteral(password));
            }
            sql(bootstrap, "CREATE DATABASE " + DATABASE + " OWNER " + postgres.getUsername());
            sql(bootstrap, "REVOKE ALL ON DATABASE " + DATABASE + " FROM PUBLIC");
            for (String role : List.of(MIGRATION, RUNTIME, METADATA)) {
                sql(bootstrap, "GRANT CONNECT ON DATABASE " + DATABASE + " TO " + role);
                sql(bootstrap, "ALTER ROLE " + role + " IN DATABASE " + DATABASE + " SET search_path TO "
                        + (role.equals(METADATA) ? "pg_catalog" : "pg_catalog, public"));
            }
            for (String catalog : List.of("postgres", "template1", postgres.getDatabaseName())) {
                sql(bootstrap, "REVOKE CONNECT, TEMPORARY ON DATABASE " + catalog + " FROM PUBLIC");
            }
        }
        ControlEnvironment original = environment(OLD_REFERENCE, Map.of(), "", "");
        try (Connection bootstrap = admin()) {
            sql(bootstrap, "DROP SCHEMA public CASCADE");
            sql(bootstrap, "CREATE SCHEMA public AUTHORIZATION " + MIGRATION);
            sql(bootstrap, "REVOKE ALL ON SCHEMA public FROM PUBLIC");
            sql(bootstrap, "GRANT USAGE ON SCHEMA public TO " + RUNTIME);
            sql(bootstrap, "CREATE TABLE public.flyway_schema_history (installed_rank integer PRIMARY KEY, "
                    + "version varchar(50),description varchar(200) NOT NULL,type varchar(20) NOT NULL,"
                    + "script varchar(1000) NOT NULL,checksum integer,installed_by varchar(100) NOT NULL,"
                    + "installed_on timestamptz NOT NULL DEFAULT pg_catalog.now(),"
                    + "execution_time integer NOT NULL DEFAULT 1,success boolean NOT NULL DEFAULT true)");
            sql(bootstrap, "ALTER TABLE public.flyway_schema_history OWNER TO " + MIGRATION);
            sql(bootstrap, "INSERT INTO public.flyway_schema_history "
                    + "(installed_rank,version,description,type,script,checksum,installed_by) "
                    + "VALUES (1,'1','owned legacy fixture','SQL','V1__owned_legacy_fixture.sql',101,'legacy_operator')");
            sql(bootstrap, "CREATE TABLE public.adoption_domain_record (id bigint PRIMARY KEY,payload text)");
            sql(bootstrap, "ALTER TABLE public.adoption_domain_record OWNER TO " + MIGRATION);
            sql(bootstrap, "GRANT SELECT,INSERT,UPDATE,DELETE ON public.adoption_domain_record TO " + RUNTIME);
            if (existingReceipt) {
                try (Connection migration = migration()) {
                    bootstrap.setAutoCommit(false);
                    try {
                        receipt = AdoptionSealer.seal(bootstrap, migration, stream(), original, 1);
                        bootstrap.commit();
                    } catch (Exception exception) {
                        bootstrap.rollback();
                        throw exception;
                    } finally {
                        bootstrap.setAutoCommit(true);
                    }
                }
            }
        }
        String previousRun = "";
        if (existingReceipt) {
            try (Connection bootstrap = admin()) {
                String version = DatabaseControl.scalar(bootstrap,
                        "SELECT pg_catalog.split_part(pg_catalog.current_setting('server_version'),' ',1)");
                previousRun = ControlRunReceipt.create("ADOPTED", original, version, true,
                        List.of(new StreamSeal(receipt.streamKey(),receipt.sealedHistoryMaxInstalledRank(),
                                receipt.sealedHistoryRowCount(),receipt.sealedHistorySha256(),
                                receipt.inventoryObjectCount(),receipt.inventorySha256(),receipt.receiptSha256())))
                        .receiptSha256();
            }
        }
        environment = environment(CURRENT_REFERENCE,
                existingReceipt ? Map.of(stream().streamKey(), receipt.receiptSha256()) : Map.of(),
                existingReceipt ? OLD_REFERENCE : "", previousRun);
    }

    ControlEnvironment environment() { return environment; }
    StreamPlan stream() { return ControlPlan.forService("auth").streams().getFirst(); }
    Receipt receipt() { return receipt; }
    Connection admin() throws SQLException {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }
    Connection migration() throws SQLException { return DriverManager.getConnection(url, MIGRATION, password); }
    Connection migration(String credential) throws SQLException {
        return DriverManager.getConnection(url, MIGRATION, credential);
    }
    Connection runtime(String credential) throws SQLException { return DriverManager.getConnection(url, RUNTIME, credential); }

    String predecessorSnapshot() throws SQLException {
        try (Connection bootstrap = admin()) {
            String history = DatabaseControl.scalar(bootstrap, "SELECT installed_rank::text||':'||checksum::text "
                    + "||':'||installed_by||':'||success::text FROM public.flyway_schema_history");
            String sealed = receipt == null ? "NO_RECEIPT" : DatabaseControl.scalar(bootstrap,
                    "SELECT receipt_sha256 FROM dwp_migration_control.adoption_receipt WHERE stream_key='auth-main'");
            return history + ":" + sealed;
        }
    }

    ChildResult forkActualPublicMain() throws Exception {
        List<String> argv = List.of("python3", "-c",
                "import os,sys; os.setsid(); os.execv(sys.argv[1],sys.argv[1:])",
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", childClasspath(),
                MainChild.class.getName());
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.environment().entrySet().removeIf(entry -> entry.getKey().startsWith("DWP_MIGRATION_CONTROL_"));
        builder.environment().putAll(childEnvironment());
        String started = Instant.now().toString();
        Process child = builder.start();
        System.out.println("ADOPTION_REPRO_CHILD_START pid=" + child.pid() + " startedAtUtc=" + started + " argv=" + argv);
        ExecutorService readers = Executors.newFixedThreadPool(2);
        Future<String> out = readers.submit(() -> new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        Future<String> err = readers.submit(() -> new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        String processIdentity = "";
        boolean timedOut = false;
        try {
            for (int attempt = 0; attempt < 20; attempt++) {
                processIdentity = processIdentity(child.pid());
                String[] values = processIdentity.strip().split("\\s+", 3);
                if (values.length >= 2 && values[0].equals(Long.toString(child.pid()))
                        && values[1].equals(Long.toString(child.pid()))) break;
                Thread.sleep(10);
            }
            if (!processIdentity.strip().startsWith(child.pid() + " " + child.pid() + " ")
                    && !processIdentity.strip().matches(child.pid() + "\\s+" + child.pid() + "\\s+.*")) {
                throw new IllegalStateException("Owned Main child did not acquire its separate process group");
            }
            timedOut = !child.waitFor(45, TimeUnit.SECONDS);
        } finally {
            if (child.isAlive()) {
                child.destroy();
                if (!child.waitFor(5, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    child.waitFor(5, TimeUnit.SECONDS);
                }
            }
            readers.shutdown();
            System.out.println("ADOPTION_REPRO_CHILD_CLEANUP pid=" + child.pid() + " processIdentity="
                    + processIdentity.strip() + " alive=" + child.isAlive() + " ownedGroup=" + ownedProcessGroup(child.pid()));
            if (child.isAlive()) throw new IllegalStateException("Exact owned Main child did not terminate");
        }
        String stdout = redact(out.get(5, TimeUnit.SECONDS));
        String stderr = redact(err.get(5, TimeUnit.SECONDS));
        return new ChildResult(argv, started, Instant.now().toString(), child.pid(), processIdentity.strip(),
                child.exitValue(), timedOut, ownedProcessGroup(child.pid()), stdout, stderr);
    }

    private ControlEnvironment environment(String reference, Map<String,String> previous, String oldReference, String run) {
        return new ControlEnvironment(ControlEnvironment.Mode.ADOPT_OR_UPGRADE, ControlPlan.forService("auth"),
                url, DATABASE, postgres.getUsername(), postgres.getPassword(), MIGRATION, password,
                RUNTIME, password, reference, previous, oldReference, Map.of(), run);
    }

    private Map<String,String> childEnvironment() {
        return Map.ofEntries(
                Map.entry("DWP_MIGRATION_CONTROL_MODE", "ADOPT_OR_UPGRADE"),
                Map.entry("DWP_MIGRATION_CONTROL_SERVICE", "auth"),
                Map.entry("DWP_MIGRATION_CONTROL_JDBC_URL", url),
                Map.entry("DWP_MIGRATION_CONTROL_DATABASE", DATABASE),
                Map.entry("DWP_MIGRATION_CONTROL_BOOTSTRAP_PRINCIPAL", postgres.getUsername()),
                Map.entry("DWP_MIGRATION_CONTROL_BOOTSTRAP_PASSWORD", postgres.getPassword()),
                Map.entry("DWP_MIGRATION_CONTROL_MIGRATION_PRINCIPAL", MIGRATION),
                Map.entry("DWP_MIGRATION_CONTROL_MIGRATION_PASSWORD", password),
                Map.entry("DWP_MIGRATION_CONTROL_RUNTIME_PRINCIPAL", RUNTIME),
                Map.entry("DWP_MIGRATION_CONTROL_RUNTIME_PASSWORD", password),
                Map.entry("DWP_MIGRATION_CONTROL_REFERENCE", CURRENT_REFERENCE),
                Map.entry("DWP_MIGRATION_CONTROL_PREVIOUS_RECEIPTS", "auth-main=" + receipt.receiptSha256()),
                Map.entry("DWP_MIGRATION_CONTROL_PREVIOUS_CONTROL_REFERENCE", OLD_REFERENCE),
                Map.entry("DWP_MIGRATION_CONTROL_PREVIOUS_RUN_RECEIPT_SHA256", environment.previousRunReceiptSha256()));
    }

    private String redact(String value) {
        return value.replace(password, "<owned-fixture-credential-redacted>")
                .replace(postgres.getPassword(), "<owned-bootstrap-credential-redacted>");
    }

    private static String childClasspath() throws Exception {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (ClassLoader loader = MainChild.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) if (url.getProtocol().equals("file")) entries.add(Path.of(url.toURI()).toString());
            }
        }
        entries.addAll(List.of(System.getProperty("java.class.path").split(File.pathSeparator)));
        return String.join(File.pathSeparator, entries);
    }

    private static String processIdentity(long pid) throws Exception {
        Process ps = new ProcessBuilder("ps", "-o", "pid=,pgid=,comm=", "-p", Long.toString(pid)).start();
        String output = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ps.waitFor(5, TimeUnit.SECONDS);
        return output;
    }

    private static List<String> ownedProcessGroup(long pgid) throws Exception {
        Process ps = new ProcessBuilder("ps", "-axo", "pid=,pgid=,comm=").start();
        String output = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ps.waitFor(5, TimeUnit.SECONDS);
        List<String> group = new ArrayList<>();
        for (String line : output.lines().toList()) {
            String[] values = line.strip().split("\\s+", 3);
            if (values.length >= 2 && values[1].equals(Long.toString(pgid))) group.add(line.strip());
        }
        return List.copyOf(group);
    }

    static void sql(Connection connection, String query) throws SQLException { DatabaseControl.execute(connection, query); }

    record ChildResult(List<String> argv, String startedAtUtc, String finishedAtUtc, long pid,
                       String processIdentity, int exitCode, boolean timedOut, List<String> processGroupAtEnd,
                       String stdout, String stderr) {
        boolean actualPublicMainEntered() { return stdout.contains("ADOPTION_REPRO_ENTRY=ACTUAL_PUBLIC_MAIN"); }
        boolean runtime42501InActiveAdoption() {
            return stderr.contains("ADOPTION_REPRO_SQLSTATE=42501")
                    && stderr.contains("MigrationControlMain.runAdoption")
                    && stderr.contains("AdoptionSealer.legacyBoundary")
                    && stderr.contains("MigrationAdoptionGuard.verifyControlPrincipal")
                    && stderr.contains("ControlDataSource.getConnection");
        }
    }

    /** Diagnostic-only wrapper calls the unchanged public Main and reports native SQLException states. */
    public static final class MainChild {
        private MainChild() { }
        public static void main(String[] arguments) throws Exception {
            System.out.println("ADOPTION_REPRO_ENTRY=ACTUAL_PUBLIC_MAIN");
            try {
                MigrationControlMain.main(arguments);
            } catch (Exception exception) {
                for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SQLException sql) System.err.println("ADOPTION_REPRO_SQLSTATE=" + sql.getSQLState());
                }
                throw exception;
            }
        }
    }
}
