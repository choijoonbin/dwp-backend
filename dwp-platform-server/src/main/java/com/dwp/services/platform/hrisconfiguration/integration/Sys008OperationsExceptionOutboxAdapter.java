package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Durable SYS-008 producer adapter for the existing SYS-EVT-009 projection. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class Sys008OperationsExceptionOutboxAdapter
        implements IntegrationRuntimePorts.OperationsExceptionPort {

    static final String SOURCE = "urn:dwp:platform:hris:operational-exception";
    static final String TYPE =
            "dwp.hris.operational-exception.projection-changed.v1";
    static final String AGGREGATE = "HRIS_OPERATIONAL_EXCEPTION";
    static final String ROUTE =
            "route.hcm.configuration.settings-operations.page";
    static final String SUMMARY_PREFIX =
            "hris.operations.exception.sys.integration-";
    static final Set<String> CODES = Set.of(
            "STALE_DISPATCH_CLAIM",
            "PROVIDER_EVIDENCE_UNBOUND",
            "PROVIDER_RESULT_UNKNOWN",
            "STALE_RECONCILIATION_CLAIM",
            "RECONCILIATION_EVIDENCE_UNBOUND",
            "RECONCILIATION_RESULT_UNKNOWN",
            "COMPENSATION_RESULT_UNKNOWN");

    private final DomainEventRecorder recorder;
    private final ObjectMapper json;

    Sys008OperationsExceptionOutboxAdapter(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper json) {
        this.recorder = recorder;
        this.json = json;
        contracts.register(TYPE, 1, 1);
    }

    @Override
    public void record(IntegrationRuntimePorts.OperationsException exception) {
        if (!CODES.contains(exception.category())) {
            throw new IllegalArgumentException(
                    "SYS008_OPERATION_EXCEPTION_CATEGORY_NOT_SEALED");
        }
        String summaryKey = summaryKey(exception.category());
        ObjectNode data = json.createObjectNode();
        data.put("exceptionPublicId", exception.eventId().toString());
        data.put("ownerService", "dwp-platform-server");
        data.put("ownerModule", "SYS");
        data.put("sourceRef", "urn:dwp:hris:sys:integration:"
                + exception.executionId());
        data.put("sourceRevision", 1);
        data.put("type", "INTEGRATION_CONTROL_PLANE");
        data.put("severity", "HIGH");
        data.put("state", "OPEN");
        data.put("code", exception.category());
        data.put("summaryKey", summaryKey);
        data.put("ownerRouteKey", ROUTE);
        data.putArray("evidenceRefs").add(exception.evidenceReference());
        data.put("correlationId", exception.executionId().toString());
        data.put("occurredAt", exception.observedAt().toInstant().toString());

        recorder.record(new DomainEventEnvelope(
                "1.0", exception.eventId(), SOURCE, TYPE, 1,
                exception.observedAt().toInstant(),
                AGGREGATE + "/" + exception.eventId(),
                exception.tenantId(), AGGREGATE,
                exception.eventId().toString(), 1,
                exception.executionId().toString(), null, null,
                data, Map.of()));
    }

    static String summaryKey(String category) {
        return SUMMARY_PREFIX + category.toLowerCase(Locale.ROOT)
                .replace('_', '-');
    }
}

/**
 * The shared SYS-EVT-009 registry is deliberately package-private. This
 * additions-only owner adapter exposes the finite SYS-008 source/code mapping
 * to Spring without editing that shared contract.
 */
@Component
@Primary
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class Sys008OperationsExceptionSummaryKeyRegistryFactory
        implements FactoryBean<Object> {

    private static final String REGISTRY_TYPE =
            "com.dwp.services.platform.hrisconfiguration.operations."
                    + "HrisOperationalExceptionSummaryKeyRegistry";

    private final Class<?> registryType;

    Sys008OperationsExceptionSummaryKeyRegistryFactory() {
        try {
            this.registryType = Class.forName(REGISTRY_TYPE);
        } catch (ClassNotFoundException missingSharedContract) {
            throw new IllegalStateException(
                    "SYS_EVT_009_SUMMARY_KEY_REGISTRY_UNAVAILABLE",
                    missingSharedContract);
        }
    }

    @Override
    public Object getObject() {
        return Proxy.newProxyInstance(
                registryType.getClassLoader(), new Class<?>[]{registryType},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "allows" -> allows(arguments);
                    case "toString" -> "SYS-008 finite SYS-EVT-009 summary registry";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(
                            "Unsupported summary registry operation: "
                                    + method.getName());
                });
    }

    @Override
    public Class<?> getObjectType() {
        return registryType;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    private static boolean allows(Object[] arguments) {
        if (arguments == null || arguments.length != 3) return false;
        String source = String.valueOf(arguments[0]);
        String code = String.valueOf(arguments[1]);
        String summaryKey = String.valueOf(arguments[2]);
        return Sys008OperationsExceptionOutboxAdapter.SOURCE.equals(source)
                && Sys008OperationsExceptionOutboxAdapter.CODES.contains(code)
                && Sys008OperationsExceptionOutboxAdapter.summaryKey(code)
                .equals(summaryKey);
    }
}
