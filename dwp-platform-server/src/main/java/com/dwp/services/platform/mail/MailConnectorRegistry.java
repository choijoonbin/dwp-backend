package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.dwp.services.platform.mail.MailTypes.ProviderType;

@Component
class MailConnectorRegistry {

    private final Map<ProviderType, MailConnectorPort> connectors;

    MailConnectorRegistry(List<MailConnectorPort> availableConnectors) {
        Map<ProviderType, MailConnectorPort> registered = new EnumMap<>(ProviderType.class);
        for (MailConnectorPort connector : availableConnectors) {
            ProviderType provider = ProviderType.valueOf(connector.manifest().provider().name());
            if (registered.put(provider, connector) != null) {
                throw new IllegalStateException("Duplicate mail connector for " + provider);
            }
        }
        connectors = Map.copyOf(registered);
    }

    Optional<MailConnectorPort> connector(ProviderType providerType) {
        return Optional.ofNullable(connectors.get(providerType));
    }

    boolean isAvailable(ProviderType providerType) {
        return connectors.containsKey(providerType);
    }
}
