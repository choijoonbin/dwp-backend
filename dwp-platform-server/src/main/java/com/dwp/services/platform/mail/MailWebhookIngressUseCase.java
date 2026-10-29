package com.dwp.services.platform.mail;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Inbound application port for verified provider webhook processing. */
interface MailWebhookIngressUseCase {

    MailWebhookVerificationPort.Verification verify(
            String rawProvider,
            Map<String, List<String>> headers,
            byte[] body);

    Accepted acceptVerified(MailWebhookVerificationPort.Verification receipt);

    record Accepted(UUID operationId, UUID jobId) {
        public Accepted {
            if (operationId == null || jobId == null) {
                throw new IllegalArgumentException(
                        "Accepted webhook work requires operation and job identifiers");
            }
        }
    }
}
