package com.dwp.services.auth.scim;

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

    public record ConnectorIdentity(UUID connectorId, Long tenantId, String connectorKey) {
    }
}
