package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.platform.contract.MailProviderException;
import org.springframework.stereotype.Component;

import java.util.List;

/** Selects exactly one credential owner and fails closed on missing or ambiguous ownership. */
@Component
class MailProviderTokenBroker {

    private final List<MailProviderTokenOwner> owners;

    MailProviderTokenBroker(List<MailProviderTokenOwner> owners) {
        this.owners = owners == null ? List.of() : List.copyOf(owners);
    }

    MailProviderTokenOwner.AccessTokenLease lease(
            MailConnectorPort.ProviderFamily provider,
            MailConnectorPort.ConnectionContext context) {
        if (provider == null || context == null || context.secretReference() == null) {
            throw failure(MailProviderException.Category.AUTHENTICATION,
                    "MAIL_PROVIDER_CREDENTIAL_REFERENCE_MISSING");
        }
        List<MailProviderTokenOwner> supported = owners.stream()
                .filter(owner -> owner.supports(provider, context))
                .toList();
        if (supported.isEmpty()) {
            throw failure(MailProviderException.Category.AUTHENTICATION,
                    "MAIL_PROVIDER_TOKEN_OWNER_UNAVAILABLE");
        }
        if (supported.size() != 1) {
            throw failure(MailProviderException.Category.PERMANENT,
                    "MAIL_PROVIDER_TOKEN_OWNER_AMBIGUOUS");
        }
        try {
            MailProviderTokenOwner.AccessTokenLease lease = supported.get(0)
                    .lease(provider, context);
            if (lease == null) {
                throw failure(MailProviderException.Category.AUTHENTICATION,
                        "MAIL_PROVIDER_TOKEN_LEASE_INVALID");
            }
            return lease;
        } catch (MailProviderException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure(MailProviderException.Category.TRANSIENT,
                    "MAIL_PROVIDER_TOKEN_BROKER_UNAVAILABLE");
        }
    }

    boolean ready(
            MailConnectorPort.ProviderFamily provider,
            MailConnectorPort.ConnectionContext context) {
        if (provider == null || context == null || context.secretReference() == null) return false;
        return owners.stream().filter(owner -> owner.supports(provider, context)).count() == 1;
    }

    private MailProviderException failure(
            MailProviderException.Category category,
            String code) {
        return new MailProviderException(category, code, null);
    }
}
