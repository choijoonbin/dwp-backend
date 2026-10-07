package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.dwp.core.database.MigrationAdoptionGuard.Contract;
import com.dwp.core.database.MigrationAdoptionGuard.ControlObjectIds;
import com.dwp.core.database.MigrationAdoptionGuard.Receipt;

/** ACTIVE-safe observer for a predecessor already qualified during BASELINE. */
public final class MigrationAdoptedPredecessorControllerVerifier {

    private MigrationAdoptedPredecessorControllerVerifier() {
    }

    public static Receipt verify(
            Contract contract,
            Connection controllerConnection,
            String controllerPrincipal,
            String migrationPrincipal,
            String runtimePrincipal,
            Receipt expectedReceipt) {
        return verify(
                contract,
                controllerConnection,
                controllerPrincipal,
                migrationPrincipal,
                runtimePrincipal,
                expectedReceipt,
                Set.of());
    }

    public static Receipt verify(
            Contract contract,
            Connection controllerConnection,
            String controllerPrincipal,
            String migrationPrincipal,
            String runtimePrincipal,
            Receipt expectedReceipt,
            Set<String> additionalProtectedObjectOwners) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(controllerConnection, "controllerConnection must not be null");
        Objects.requireNonNull(expectedReceipt, "expectedReceipt must not be null");
        requireIdentifier("controllerPrincipal", controllerPrincipal);
        requireIdentifier("migrationPrincipal", migrationPrincipal);
        requireIdentifier("runtimePrincipal", runtimePrincipal);
        Objects.requireNonNull(
                additionalProtectedObjectOwners,
                "additionalProtectedObjectOwners must not be null");
        LinkedHashSet<String> approvedOwners = new LinkedHashSet<>();
        approvedOwners.add(migrationPrincipal);
        for (String owner : additionalProtectedObjectOwners) {
            requireIdentifier("additional protected-object owner", owner);
            if (!approvedOwners.add(owner)) {
                throw new IllegalArgumentException(
                        "additional protected-object owners must exclude migrationPrincipal");
            }
        }
        if (controllerPrincipal.equals(migrationPrincipal)
                || controllerPrincipal.equals(runtimePrincipal)
                || migrationPrincipal.equals(runtimePrincipal)) {
            throw failure(contract, "controller observation principals must be pairwise distinct");
        }
        try {
            Receipt current = MigrationAdoptionGuard.verifyReceipt(
                    controllerConnection,
                    contract,
                    migrationPrincipal,
                    expectedReceipt.receiptSha256(),
                    expectedReceipt.controlReference(),
                    controllerPrincipal,
                    Set.copyOf(approvedOwners));
            if (!current.equals(expectedReceipt)) {
                throw failure(contract, "adopted predecessor changed after BASELINE verification");
            }
            ControlObjectIds objectIds = MigrationAdoptionGuard.controlObjectIds(
                    contract, controllerConnection);
            MigrationAdoptionGuard.verifyPublicControlMetadataPrivileges(
                    contract, controllerConnection, objectIds);
            verifyObservedPrincipal(
                    contract, controllerConnection, migrationPrincipal, true, objectIds);
            verifyObservedPrincipal(
                    contract, controllerConnection, runtimePrincipal, false, objectIds);
            return current;
        } catch (SQLException exception) {
            throw failure(
                    contract, "cannot revalidate adopted predecessor from controller", exception);
        }
    }

    private static void verifyObservedPrincipal(
            Contract contract,
            Connection controllerConnection,
            String observedPrincipal,
            boolean migration,
            ControlObjectIds objectIds) throws SQLException {
        String sql = "SELECT "
                + "has_schema_privilege(?::name, ?::oid, 'USAGE'), "
                + "has_schema_privilege(?::name, ?::oid, 'CREATE'), "
                + "has_table_privilege(?::name, ?::oid, 'SELECT'), "
                + "has_table_privilege(?::name, ?::oid, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'), "
                + "has_table_privilege(?::name, ?::oid, 'SELECT'), "
                + "has_table_privilege(?::name, ?::oid, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')";
        try (PreparedStatement statement = controllerConnection.prepareStatement(sql)) {
            long[] objectSequence = {
                    objectIds.schemaOid(), objectIds.schemaOid(), objectIds.receiptOid(),
                    objectIds.receiptOid(), objectIds.inventoryOid(), objectIds.inventoryOid()
            };
            for (int index = 0; index < objectSequence.length; index++) {
                statement.setString(index * 2 + 1, observedPrincipal);
                statement.setLong(index * 2 + 2, objectSequence[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw failure(contract, "controller Control privilege query returned no row");
                }
                boolean valid = migration
                        ? result.getBoolean(1) && !result.getBoolean(2)
                                && result.getBoolean(3) && !result.getBoolean(4)
                                && result.getBoolean(5) && !result.getBoolean(6)
                        : !result.getBoolean(1) && !result.getBoolean(2)
                                && !result.getBoolean(3) && !result.getBoolean(4)
                                && !result.getBoolean(5) && !result.getBoolean(6);
                if (!valid) {
                    throw failure(contract, (migration ? "migration" : "runtime")
                            + " Control metadata privilege profile is invalid");
                }
            }
        }
    }

    private static void requireIdentifier(String name, String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a canonical SQL identifier");
        }
    }

    private static IllegalStateException failure(Contract contract, String message) {
        return new IllegalStateException(
                "Migration adoption rejected for " + contract.streamKey() + ": " + message);
    }

    private static IllegalStateException failure(
            Contract contract, String message, Exception cause) {
        return new IllegalStateException(
                "Migration adoption rejected for " + contract.streamKey() + ": " + message,
                cause);
    }
}
