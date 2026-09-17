package com.dwp.services.approval.connectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.approval.connectors.ConnectorModels.*;

@Service
public class ConnectorService {
    private final ConnectorRepository repository;
    private final ConnectorProbeAttestationVerifier attestationVerifier;
    private final Clock clock;

    public ConnectorService(
            ConnectorRepository repository,
            ConnectorProbeAttestationVerifier attestationVerifier,
            Clock clock) {
        this.repository = repository;
        this.attestationVerifier = attestationVerifier;
        this.clock = clock;
    }

    @Transactional
    public ConnectorView saveDraft(String idempotencyKey, ConnectorDraft input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "SAVE_CONNECTOR_DRAFT", input.connectorId(), input,
                ConnectorView.class, () -> repository.saveDraft(context, input));
    }

    @Transactional
    public ProbeView startProbe(
            String idempotencyKey, UUID connectorId, ProbeStart input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "START_PROBE", input.probeId(), input,
                ProbeView.class, () -> repository.startProbe(
                        context, connectorId, input, clock.instant()));
    }

    @Transactional
    public ProbeView completeProbe(
            String idempotencyKey,
            UUID connectorId,
            UUID probeId,
            ProbeCompletion input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "COMPLETE_PROBE", probeId, input,
                ProbeView.class, () -> {
                    String verificationReference = null;
                    if (input != null && input.state() == ProbeState.VERIFIED) {
                        ProbeView probe = repository.requireProbe(
                                context, connectorId, probeId, true);
                        verificationReference = attestationVerifier.verify(
                                context, connectorId, probe, input);
                    }
                    return repository.completeProbe(
                            context, connectorId, probeId, input,
                            verificationReference, clock.instant());
                });
    }

    @Transactional
    public ConnectorView publish(
            String idempotencyKey, UUID connectorId, PublishCommand input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "PUBLISH_CONNECTOR", connectorId, input,
                ConnectorView.class, () -> repository.publish(
                        context, connectorId, input, clock.instant()));
    }

    @Transactional
    public ConnectorView changeLifecycle(
            String idempotencyKey, UUID connectorId, LifecycleCommand input) {
        Context context = Context.current(idempotencyKey);
        return idempotent(context, "CHANGE_CONNECTOR_LIFECYCLE", connectorId, input,
                ConnectorView.class, () -> repository.changeLifecycle(context, connectorId, input));
    }

    @Transactional(readOnly = true)
    public List<ConnectorView> connectors() {
        return repository.connectors(readContext());
    }

    @Transactional(readOnly = true)
    public ConnectorDetail connector(UUID connectorId) {
        Context context = readContext();
        return new ConnectorDetail(repository.requireConnector(context, connectorId, false),
                repository.probes(context, connectorId));
    }

    @Transactional(readOnly = true)
    public ProbeView probe(UUID connectorId, UUID probeId) {
        return repository.requireProbe(readContext(), connectorId, probeId, false);
    }

    private Context readContext() {
        return Context.current("read-" + UUID.randomUUID());
    }

    private <T> T idempotent(
            Context context,
            String operation,
            UUID target,
            Object input,
            Class<T> type,
            Supplier<T> command) {
        T prior = repository.prior(context, operation, target, input, type);
        if (prior != null) return prior;
        T result = command.get();
        repository.complete(context, operation, input, result);
        return result;
    }
}
