package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;

import java.util.List;

import static com.dwp.services.platform.mail.MailTypes.ProviderType;

@Component
public class MailProviderCatalog {

    private static final List<MailDtos.ProviderDescriptor> PROVIDERS = List.of(
            provider(
                    ProviderType.MICROSOFT_GRAPH,
                    "Microsoft 365",
                    "Microsoft Graph",
                    "OAuth 2.0",
                    List.of("READ", "SEND", "THREADS", "DELTA_SYNC", "PUSH", "CALENDAR_CONTEXT"),
                    true,
                    true),
            provider(
                    ProviderType.GOOGLE_GMAIL,
                    "Google Workspace",
                    "Gmail API",
                    "OAuth 2.0",
                    List.of("READ", "SEND", "THREADS", "HISTORY_SYNC", "PUSH", "LABELS"),
                    true,
                    true),
            provider(
                    ProviderType.NAVER_WORKS,
                    "NAVER WORKS",
                    "NAVER WORKS Mail API",
                    "OAuth 2.0",
                    List.of("READ", "SEND", "THREADS", "FOLDERS"),
                    false,
                    true),
            provider(
                    ProviderType.JMAP,
                    "JMAP",
                    "RFC 8620 / RFC 8621",
                    "OAuth 2.0",
                    List.of("READ", "SEND", "THREADS", "PUSH"),
                    true,
                    false),
            provider(
                    ProviderType.IMAP_SMTP,
                    "IMAP / SMTP",
                    "IMAP4rev2 / SMTP Submission",
                    "OAuth 2.0 or vaulted credentials",
                    List.of("READ", "SEND", "FOLDERS", "IDLE"),
                    true,
                    false),
            provider(
                    ProviderType.DWP_SANDBOX,
                    "DWP Sandbox",
                    "DWP native development adapter",
                    "None",
                    List.of("READ", "SEND", "THREADS", "PUSH", "SHARED_INBOX", "COMMENTS"),
                    true,
                    true));

    public List<MailDtos.ProviderDescriptor> all() {
        return PROVIDERS;
    }

    private static MailDtos.ProviderDescriptor provider(
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
                tenantWideSupported);
    }
}
