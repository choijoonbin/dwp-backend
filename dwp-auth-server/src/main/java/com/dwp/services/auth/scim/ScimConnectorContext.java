package com.dwp.services.auth.scim;

import java.util.Set;
import java.util.UUID;

public final class ScimConnectorContext {

    private static final ThreadLocal<ConnectorIdentity> IDENTITY = new ThreadLocal<>();

    private ScimConnectorContext() {
    }

    public static void set(ConnectorIdentity identity) {
        IDENTITY.set(identity);
    }

    public static ConnectorIdentity require() {
        ConnectorIdentity identity = IDENTITY.get();
        if (identity == null) throw new IllegalStateException("SCIM connector identity is missing.");
        return identity;
    }

    public static void clear() {
        IDENTITY.remove();
    }

    public static void requireOperation(String operation) {
        ConnectorIdentity identity = require();
        if (!identity.allowedOperations().contains(operation)) {
            throw ScimException.forbidden(
                    "This SCIM credential is not authorized for " + operation + " resources.");
        }
    }

    public record ConnectorIdentity(
            UUID connectorId,
            Long tenantId,
            String connectorKey,
            Set<String> allowedOperations) {
        public ConnectorIdentity {
            allowedOperations = Set.copyOf(allowedOperations);
        }
    }
}
