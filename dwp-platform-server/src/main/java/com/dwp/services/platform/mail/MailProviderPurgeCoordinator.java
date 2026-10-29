package com.dwp.services.platform.mail;

import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.platform.contract.MailProviderException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Executes only commands staged after legal-hold revalidation and summarizes truthfully. */
@Component
class MailProviderPurgeCoordinator {
    private final MailProviderPurgeRepository repository;
    private final MailConnectorRegistry connectors;

    MailProviderPurgeCoordinator(
            MailProviderPurgeRepository repository,
            MailConnectorRegistry connectors) {
        this.repository = repository;
        this.connectors = connectors;
    }

    Result execute(long tenantId, long actorId, UUID jobId) {
        List<MailProviderPurgeRepository.Command> commands = repository.commands(tenantId, jobId);
        for (MailProviderPurgeRepository.Command command : commands) {
            if (!"PENDING".equals(command.state())) continue;
            MailConnectorPort.PurgeReceipt receipt = execute(actorId, command);
            if (repository.record(command, receipt) != 1) {
                throw new IllegalStateException("MAIL_PROVIDER_PURGE_RECEIPT_CONFLICT");
            }
        }
        List<MailProviderPurgeRepository.Command> completed = repository.commands(tenantId, jobId);
        int purged = completed.stream().mapToInt(MailProviderPurgeRepository.Command::purgedItems)
                .sum();
        if (completed.isEmpty() || completed.stream().allMatch(item -> "PURGED".equals(item.state())))
            return new Result(State.PURGED, completed.size(), purged);
        if (completed.stream().anyMatch(item -> "RECONCILIATION_REQUIRED".equals(item.state())))
            return new Result(State.RECONCILIATION_REQUIRED, completed.size(), purged);
        if (completed.stream().anyMatch(item -> "NOT_SUPPORTED".equals(item.state())))
            return new Result(State.NOT_SUPPORTED, completed.size(), purged);
        return new Result(State.FAILED, completed.size(), purged);
    }

    private MailConnectorPort.PurgeReceipt execute(
            long actorId, MailProviderPurgeRepository.Command command) {
        MailConnectorPort connector = connectors.connector(command.providerType()).orElse(null);
        if (connector == null) return receipt(MailConnectorPort.PurgeOutcome.NOT_SUPPORTED,
                "MAIL_PROVIDER_PURGE_ADAPTER_UNAVAILABLE");
        if (command.providerAccountReference() == null
                || (command.messageReferences().isEmpty() && command.threadReferences().isEmpty()))
            return receipt(MailConnectorPort.PurgeOutcome.NOT_SUPPORTED,
                    "MAIL_PROVIDER_PURGE_REFERENCES_UNAVAILABLE");
        try {
            MailConnectorPort.ConnectionContext context = new MailConnectorPort.ConnectionContext(
                    new ExecutionContext(Long.toString(command.tenantId()), Long.toString(actorId),
                            Set.of("ADMIN.MAIL:PURGE_EXECUTE"),
                            "mail-provider-purge:" + command.jobId()),
                    command.connectionId(), command.credentialReference(), command.mailDomain());
            return connector.purge(new MailConnectorPort.PurgeRequest(
                    context, command.providerAccountReference(), command.idempotencyKey(),
                    command.messageReferences(), command.threadReferences()));
        } catch (MailProviderException failure) {
            MailConnectorPort.PurgeOutcome outcome = failure.category()
                    == MailProviderException.Category.PERMANENT
                    || failure.category() == MailProviderException.Category.AUTHENTICATION
                    ? MailConnectorPort.PurgeOutcome.FAILED
                    : MailConnectorPort.PurgeOutcome.RECONCILIATION_REQUIRED;
            return receipt(outcome, failure.errorCode());
        } catch (RuntimeException failure) {
            return receipt(MailConnectorPort.PurgeOutcome.RECONCILIATION_REQUIRED,
                    "MAIL_PROVIDER_PURGE_RESULT_UNKNOWN");
        }
    }

    private MailConnectorPort.PurgeReceipt receipt(
            MailConnectorPort.PurgeOutcome outcome, String code) {
        return new MailConnectorPort.PurgeReceipt(outcome, 0, null, Instant.now(), code);
    }

    enum State { PURGED, NOT_SUPPORTED, RECONCILIATION_REQUIRED, FAILED }
    record Result(State state, int commands, int purgedItems) {
        boolean complete() { return state == State.PURGED; }
    }
}
