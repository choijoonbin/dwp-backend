package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;

import java.util.List;

import static com.dwp.services.platform.mail.MailTypes.AdapterRuntimeState;
import static com.dwp.services.platform.mail.MailTypes.ProviderType;

@Component
public class MailProviderCatalog {

    private final MailConnectorRegistry connectorRegistry;

    public MailProviderCatalog(MailConnectorRegistry connectorRegistry) {
        this.connectorRegistry = connectorRegistry;
    }

    private final List<MailDtos.ProviderDescriptor> providers = List.of(
            descriptor(
                    ProviderType.MICROSOFT_GRAPH,
                    "Microsoft 365",
                    "Microsoft Graph",
                    "OAuth 2.0",
                    List.of("READ", "SEND", "THREADS", "DELTA_SYNC", "PUSH", "CALENDAR_CONTEXT"),
                    true,
                    true),
            descriptor(
                    ProviderType.GOOGLE_GMAIL,
                    "Google Workspace",
                    "Gmail API",
                    "OAuth 2.0",
                    List.of("READ", "SEND", "THREADS", "HISTORY_SYNC", "PUSH", "LABELS"),
                    true,
                    true),
            descriptor(
                    ProviderType.NAVER_WORKS,
                    "NAVER WORKS",
                    "NAVER WORKS Mail API",
                    "OAuth 2.0",
                    List.of("READ", "SEND", "THREADS", "FOLDERS"),
                    false,
                    true),
            descriptor(
                    ProviderType.JMAP,
                    "JMAP",
                    "RFC 8620 / RFC 8621",
                    "OAuth 2.0",
                    List.of("READ", "SEND", "THREADS", "PUSH"),
                    true,
                    false),
            descriptor(
                    ProviderType.IMAP_SMTP,
                    "IMAP / SMTP",
                    "IMAP4rev2 / SMTP Submission",
                    "OAuth 2.0 or vaulted credentials",
                    List.of("READ", "SEND", "FOLDERS", "IDLE"),
                    true,
                    false),
            descriptor(
                    ProviderType.DWP_SANDBOX,
                    "DWP Sandbox",
                    "DWP native development adapter",
                    "None",
                    List.of("READ", "SEND", "THREADS", "PUSH", "SHARED_INBOX", "COMMENTS"),
                    true,
                    true));

    public List<MailDtos.ProviderDescriptor> all() {
        return providers.stream().map(this::withRuntimeState).toList();
    }

    public boolean isRuntimeAvailable(ProviderType providerType) {
        return connectorRegistry.isAvailable(providerType);
    }

    private MailDtos.ProviderDescriptor withRuntimeState(MailDtos.ProviderDescriptor descriptor) {
        return connectorRegistry.connector(descriptor.providerType())
                .map(connector -> new MailDtos.ProviderDescriptor(
                        descriptor.providerType(), descriptor.name(), descriptor.protocol(),
                        descriptor.authenticationMode(), descriptor.capabilities(),
                        descriptor.pushSupported(), descriptor.tenantWideSupported(),
                        AdapterRuntimeState.AVAILABLE, connector.manifest().adapterVersion()))
                .orElseGet(() -> new MailDtos.ProviderDescriptor(
                        descriptor.providerType(), descriptor.name(), descriptor.protocol(),
                        descriptor.authenticationMode(), descriptor.capabilities(),
                        descriptor.pushSupported(), descriptor.tenantWideSupported(),
                        AdapterRuntimeState.DEPLOYMENT_REQUIRED, null));
    }

    private static MailDtos.ProviderDescriptor descriptor(
            ProviderType type,
            String name,
            String protocol,
            String authenticationMode,
            List<String> capabilities,
            boolean pushSupported,
            boolean tenantWideSupported) {
        return new MailDtos.ProviderDescriptor(
                type,
                name,
                protocol,
                authenticationMode,
                List.copyOf(capabilities),
                pushSupported,
                tenantWideSupported,
                AdapterRuntimeState.DEPLOYMENT_REQUIRED,
                null);
    }
}
