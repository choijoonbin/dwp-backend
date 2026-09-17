package com.dwp.services.approval.connectors;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.connectors.ConnectorModels.*;

@Service
public class ConnectorEndpointService {
    private static final String BASE = "/v1/admin/operations/connectors";

    private final ConnectorService service;
    private final ConnectorEndpointAuthority authority;

    public ConnectorEndpointService(
            ConnectorService service,
            ConnectorEndpointAuthority authority) {
        this.service = service;
        this.authority = authority;
    }

    @Transactional(readOnly = true)
    public List<ConnectorView> connectors() {
        authority.read();
        return service.connectors();
    }

    @Transactional(readOnly = true)
    public ConnectorDetail connector(UUID connectorId) {
        authority.read();
        return service.connector(connectorId);
    }

    @Transactional(readOnly = true)
    public ProbeView probe(UUID connectorId, UUID probeId) {
        authority.read();
        return service.probe(connectorId, probeId);
    }

    @Transactional
    public ConnectorView save(ConnectorDraft input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + input.connectorId() + "/draft";
        var permit = authority.begin("CONNECTOR", input.connectorId(), input.expectedVersion(),
                "PUT", path, input, headers);
        ConnectorView result = service.saveDraft(headers.idempotencyKey(), input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public ProbeView startProbe(
            UUID connectorId, ProbeStart input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + connectorId + "/probes";
        var permit = authority.begin("CONNECTOR", connectorId,
                input.expectedConnectorVersion(), "POST", path, input, headers);
        ProbeView result = service.startProbe(headers.idempotencyKey(), connectorId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public ProbeView completeProbe(
            UUID connectorId,
            UUID probeId,
            ProbeCompletion input,
            ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + connectorId + "/probes/" + probeId + "/complete";
        var permit = authority.begin("CONNECTOR_PROBE", probeId,
                input.expectedProbeVersion(), "POST", path, input, headers);
        ProbeView result = service.completeProbe(
                headers.idempotencyKey(), connectorId, probeId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public ConnectorView publish(
            UUID connectorId, PublishCommand input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + connectorId + "/publish";
        var permit = authority.begin("CONNECTOR", connectorId, input.expectedVersion(),
                "POST", path, input, headers);
        ConnectorView result = service.publish(headers.idempotencyKey(), connectorId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public ConnectorView lifecycle(
            UUID connectorId, LifecycleCommand input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/" + connectorId + "/lifecycle";
        var permit = authority.begin("CONNECTOR", connectorId, input.expectedVersion(),
                "POST", path, input, headers);
        ConnectorView result = service.changeLifecycle(
                headers.idempotencyKey(), connectorId, input);
        authority.complete(permit);
        return result;
    }
}
