package com.dwp.migration.control;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateOutput;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies the single reviewed Platform V254.1 bridge when an existing database
 * has already crossed immutable V255.
 *
 * <p>This is deliberately narrower than enabling Flyway out-of-order mode for
 * a stream. The normal configuration remains out-of-order disabled. Control
 * permits exactly one ignored, source-pinned migration for one bounded pass,
 * proves the result, and then returns to the original strict configuration.</p>
 */
final class PlatformInventoryBridgeControl {
    private static final MigrationVersion BRIDGE_VERSION =
            MigrationVersion.fromVersion("254.1");
    private static final MigrationVersion V255 = MigrationVersion.fromVersion("255");
    private static final String BRIDGE_FILE =
            "V254_1__bridge_platform_trigger_inventory_before_v255.sql";
    private static final String BRIDGE_RESOURCE = "db/migration/" + BRIDGE_FILE;
    private static final String BRIDGE_SHA256 =
            "ee9d299d9f394dc54c7dda14fa0189442f3bb1ebd125d2f129f7af9983e5e7a0";

    private PlatformInventoryBridgeControl() {
    }

    static void applyIfRequired(Flyway flyway) {
        if (flyway.getConfiguration().isOutOfOrder()) {
            throw new IllegalStateException(
                    "Migration Control normal Flyway configuration enabled out-of-order execution");
        }
        if (!MigrationVersion.LATEST.equals(flyway.getConfiguration().getTarget())) {
            return;
        }
        MigrationInfo[] inventory = flyway.info().all();
        MigrationInfo bridgeInInventory = exactBridge(inventory);
        if (bridgeInInventory == null) {
            return;
        }
        requireExactSources(flyway, inventory);
        List<MigrationInfo> ignored = resolvedVersionedIgnored(inventory);
        if (ignored.isEmpty()) {
            return;
        }
        if (ignored.size() != 1 || !isExactBridge(ignored.getFirst())) {
            throw new IllegalStateException(
                    "Migration Control refuses unexpected out-of-order migrations: "
                            + ignored.stream().map(MigrationInfo::getScript).sorted().toList());
        }
        MigrationInfo bridge = ignored.getFirst();
        requireSuccessfulV255(inventory);
        requireExactSource(flyway, bridge, BRIDGE_FILE, BRIDGE_RESOURCE, BRIDGE_SHA256);

        Flyway bounded = Flyway.configure(flyway.getConfiguration().getClassLoader())
                .configuration(flyway.getConfiguration())
                .target(BRIDGE_VERSION)
                .outOfOrder(true)
                .ignoreMigrationPatterns(new String[0])
                .load();
        MigrateResult result = bounded.migrate();
        if (!result.success || result.migrationsExecuted != 1
                || result.migrations == null || result.migrations.size() != 1
                || !isExactBridge(result.migrations.getFirst())) {
            throw new IllegalStateException(
                    "Migration Control Platform inventory bridge execution was not exact");
        }
        MigrationInfo applied = exactBridge(bounded.info().all());
        if (applied == null
                || (applied.getState() != MigrationState.OUT_OF_ORDER
                    && applied.getState() != MigrationState.SUCCESS)) {
            throw new IllegalStateException(
                    "Migration Control Platform inventory bridge was not recorded successfully");
        }
    }

    static void requireExactSources(Flyway flyway) {
        requireExactSources(flyway, flyway.info().all());
    }

    private static void requireExactSources(
            Flyway flyway, MigrationInfo[] inventory) {
        MigrationInfo bridge = exactBridge(inventory);
        if (bridge == null) {
            throw new IllegalStateException(
                    "Migration Control Platform inventory bridge is missing");
        }
        requireExactSource(
                flyway, bridge, BRIDGE_FILE, BRIDGE_RESOURCE, BRIDGE_SHA256);
    }

    private static List<MigrationInfo> resolvedVersionedIgnored(
            MigrationInfo[] inventory) {
        List<MigrationInfo> matches = new ArrayList<>();
        for (MigrationInfo migration : inventory) {
            if (migration.getState() == MigrationState.IGNORED
                    && migration.getVersion() != null
                    && !migration.getType().isBaseline()) {
                matches.add(migration);
            }
        }
        return List.copyOf(matches);
    }

    private static void requireSuccessfulV255(MigrationInfo[] inventory) {
        List<MigrationInfo> matches = new ArrayList<>();
        for (MigrationInfo migration : inventory) {
            if (V255.equals(migration.getVersion())
                    && migration.getScript() != null
                    && migration.getScript().startsWith("V255__")) {
                matches.add(migration);
            }
        }
        if (matches.size() != 1
                || (matches.getFirst().getState() != MigrationState.SUCCESS
                    && matches.getFirst().getState() != MigrationState.OUT_OF_ORDER)) {
            throw new IllegalStateException(
                    "Migration Control requires the exact successful V255 predecessor before the bridge");
        }
    }

    private static MigrationInfo exactBridge(MigrationInfo[] inventory) {
        MigrationInfo found = null;
        for (MigrationInfo migration : inventory) {
            if (isExactBridge(migration)) {
                if (found != null) {
                    throw new IllegalStateException(
                            "Migration Control resolved multiple Platform inventory bridges");
                }
                found = migration;
            }
        }
        return found;
    }

    private static boolean isExactBridge(MigrationInfo migration) {
        return BRIDGE_VERSION.equals(migration.getVersion())
                && BRIDGE_FILE.equals(migration.getScript());
    }

    private static boolean isExactBridge(MigrateOutput migration) {
        return "254.1".equals(migration.version)
                && migration.filepath != null
                && migration.filepath.endsWith(BRIDGE_FILE);
    }

    private static void requireExactSource(
            Flyway flyway,
            MigrationInfo migration,
            String fileName,
            String resourceName,
            String expectedSha256) {
        byte[] source = sourceBytes(flyway, migration, resourceName);
        String actual = HexFormat.of().formatHex(sha256().digest(source));
        if (!expectedSha256.equals(actual)) {
            throw new IllegalStateException(
                    "Platform migration source digest differs: " + fileName);
        }
    }

    private static byte[] sourceBytes(
            Flyway flyway, MigrationInfo migration, String resourceName) {
        String physicalLocation = migration.getPhysicalLocation();
        if (physicalLocation != null) {
            try {
                Path path = Path.of(physicalLocation);
                if (!Files.isSymbolicLink(path)
                        && Files.isRegularFile(path)) {
                    return Files.readAllBytes(path);
                }
            } catch (IOException | RuntimeException ignored) {
                // A packaged classpath resource is resolved below and must be unique.
            }
        }
        try {
            Enumeration<URL> resources = flyway.getConfiguration().getClassLoader()
                    .getResources(resourceName);
            List<URL> matches = java.util.Collections.list(resources);
            if (matches.size() != 1) {
                throw new IllegalStateException(
                        "Platform inventory bridge packaged source is not unique");
            }
            try (var input = matches.getFirst().openStream()) {
                return input.readAllBytes();
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Platform inventory bridge source cannot be read", exception);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
