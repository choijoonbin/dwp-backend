package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.append;
import static com.dwp.migration.control.ControlValues.finish;
import static com.dwp.migration.control.ControlValues.json;
import static com.dwp.migration.control.ControlValues.sha256;

import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

record ControlRunReceipt(
        String mode,
        String service,
        String database,
        String migrationPrincipal,
        String controlReference,
        String previousRunReceiptSha256,
        String postgresVersion,
        boolean temporaryPrivilegeRevoked,
        List<StreamSeal> streams,
        String receiptSha256) {

    static ControlRunReceipt create(
            String mode,
            ControlEnvironment environment,
            String postgresVersion,
            boolean temporaryPrivilegeRevoked,
            List<StreamSeal> streams) throws Exception {
        List<StreamSeal> canonical = streams.stream()
                .sorted(Comparator.comparing(StreamSeal::streamKey))
                .toList();
        Set<String> expectedStreams = environment.plan().streams().stream()
                .map(StreamPlan::streamKey)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> actualStreams = canonical.stream()
                .map(StreamSeal::streamKey)
                .collect(Collectors.toUnmodifiableSet());
        if (canonical.size() != expectedStreams.size()
                || !actualStreams.equals(expectedStreams)
                || !temporaryPrivilegeRevoked
                || !postgresVersion.matches("[0-9]+(?:\\.[0-9]+){1,2}")) {
            throw new IllegalStateException(
                    "Migration Control run receipt state is incomplete");
        }
        MessageDigest digest = sha256();
        append(digest, "migration-control-run-receipt-v2");
        append(digest, mode);
        append(digest, environment.plan().service());
        append(digest, environment.database());
        append(digest, environment.migrationPrincipal());
        append(digest, environment.controlReference());
        append(digest, environment.previousRunReceiptSha256());
        append(digest, postgresVersion);
        append(digest, temporaryPrivilegeRevoked);
        for (StreamSeal stream : canonical) {
            append(digest, stream.streamKey());
            append(digest, stream.historyMaxInstalledRank());
            append(digest, stream.historyRowCount());
            append(digest, stream.historySha256());
            append(digest, stream.inventoryObjectCount());
            append(digest, stream.inventorySha256());
            append(digest, stream.adoptionReceiptSha256());
        }
        return new ControlRunReceipt(
                mode,
                environment.plan().service(),
                environment.database(),
                environment.migrationPrincipal(),
                environment.controlReference(),
                environment.previousRunReceiptSha256(),
                postgresVersion,
                temporaryPrivilegeRevoked,
                canonical,
                finish(digest));
    }

    String toJson() {
        String streamJson = streams.stream().map(StreamSeal::toJson)
                .reduce((left, right) -> left + "," + right).orElse("");
        return "{" +
                "\"schemaVersion\":\"2.0\"," +
                "\"mode\":" + json(mode) + "," +
                "\"service\":" + json(service) + "," +
                "\"database\":" + json(database) + "," +
                "\"migrationPrincipal\":" + json(migrationPrincipal) + "," +
                "\"controlReference\":" + json(controlReference) + "," +
                "\"previousRunReceiptSha256\":" + json(previousRunReceiptSha256) + "," +
                "\"postgresVersion\":" + json(postgresVersion) + "," +
                "\"temporaryPrivilegeRevoked\":" + temporaryPrivilegeRevoked + "," +
                "\"streams\":[" + streamJson + "]," +
                "\"receiptSha256\":" + json(receiptSha256) + "}";
    }
}
