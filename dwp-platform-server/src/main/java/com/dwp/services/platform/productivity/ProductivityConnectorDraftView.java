package com.dwp.services.platform.productivity;

import java.util.List;

import static com.dwp.services.platform.productivity.ProductivityTypes.*;

/** Connector draft values consumed by persistence parameter mapping. */
interface ProductivityConnectorDraftView {
    String connectorKey();
    String displayName();
    ProviderType providerType();
    AuthMode authMode();
    String providerTenantId();
    String clientId();
    String credentialReference();
    String redirectUri();
    List<String> requestedScopes();
    List<String> capabilities();
    PolicyState policyState();
}
