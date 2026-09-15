package com.dwp.services.approval.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class ApprovalOperationsCanonical {
    private ApprovalOperationsCanonical() {
    }

    static String delivery(
            ApprovalOperationsProtocol.Route route,
            UUID commandId,
            List<ApprovalOperationsDtos.DeliveryTarget> items,
            String reason) {
        Material material = new Material()
                .add("approval-operation-v1")
                .add(route.name())
                .add(commandId)
                .add(reason);
        sortedDeliveries(items).forEach(item -> material
                .add(item.targetId())
                .add(item.expectedVersion()));
        return material.digest();
    }

    static String tasks(
            ApprovalOperationsProtocol.Route route,
            UUID commandId,
            List<ApprovalOperationsDtos.TaskReassignmentTarget> items,
            String reason) {
        Material material = new Material()
                .add("approval-operation-v1")
                .add(route.name())
                .add(commandId)
                .add(reason);
        sortedTasks(items).forEach(item -> material
                .add(item.targetId())
                .add(item.expectedVersion())
                .add(item.assigneeUserId())
                .add(item.assigneePersonPublicId()));
        return material.digest();
    }

    static String target(Object... values) {
        Material material = new Material().add("approval-operation-target-v1");
        for (Object value : values) material.add(value);
        return material.digest();
    }

    static List<ApprovalOperationsDtos.DeliveryTarget> sortedDeliveries(
            List<ApprovalOperationsDtos.DeliveryTarget> items) {
        return items.stream()
                .sorted(Comparator.comparing(item -> item.targetId().toString()))
                .toList();
    }

    static List<ApprovalOperationsDtos.TaskReassignmentTarget> sortedTasks(
            List<ApprovalOperationsDtos.TaskReassignmentTarget> items) {
        return items.stream()
                .sorted(Comparator.comparing(item -> item.targetId().toString()))
                .toList();
    }

    private static final class Material {
        private final StringBuilder value = new StringBuilder();

        Material add(Object part) {
            String text = part == null ? "" : part.toString();
            value.append(text.length()).append(':').append(text).append(';');
            return this;
        }

        String digest() {
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256")
                        .digest(value.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(bytes);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable.", exception);
            }
        }
    }
}
