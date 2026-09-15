package com.dwp.services.approval.operations;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
final class ApprovalOperationsAudit {
    private final AuditOutboxRecorder recorder;

    ApprovalOperationsAudit(AuditOutboxRecorder recorder) {
        this.recorder = recorder;
    }

    UUID record(
            ApprovalOperationsAuthority.Current current,
            ApprovalOperationsDtos.OperationReceipt receipt,
            String idempotencyKey) {
        return recorder.record(AuditEvent.builder()
                .tenantId(current.actor().tenantId())
                .category("ADMIN_CHANGE")
                .occurredAt(receipt.committedAt())
                .action("approval.operations." + receipt.operation().toLowerCase())
                .outcome("SUCCESS")
                .severity("HIGH")
                .actorType("USER")
                .actorId(current.actor().userId().toString())
                .actorRoles(List.copyOf(current.actor().roles()))
                .sourceService("dwp-approval-server")
                .sourceModule("approval-native-operations")
                .targetType("APPROVAL_OPERATION_BATCH")
                .targetId(receipt.operationId().toString())
                .correlationId(idempotencyKey)
                .afterState(Map.of(
                        "operation", receipt.operation(),
                        "commandMode", receipt.commandMode(),
                        "managementResourceSetKey", receipt.managementResourceSetKey(),
                        "itemCount", receipt.itemCount(),
                        "targetIds", receipt.items().stream()
                                .map(item -> item.targetId().toString()).toList()))
                .retentionClass("EXTENDED")
                .build());
    }
}
