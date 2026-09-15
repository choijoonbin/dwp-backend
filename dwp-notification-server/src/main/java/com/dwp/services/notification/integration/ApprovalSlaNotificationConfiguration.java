package com.dwp.services.notification.integration;

import com.dwp.services.notification.domain.DirectNotificationMaterializer;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "dwp.notification.approval-sla.enabled", havingValue = "true", matchIfMissing = false)
public class ApprovalSlaNotificationConfiguration {
    @Bean
    ApprovalSlaRecipientAuthorityClient approvalSlaRecipientAuthorityClient(
            @Value("${dwp.notification.approval-sla.endpoint:}") String endpoint,
            @Value("${dwp.notification.approval-sla.transport-private-jwk:}") String privateKey,
            @Value("${dwp.notification.approval-sla.recipient-authority-jwks:}") String currentKeys,
            @Value("${dwp.notification.approval-sla.prohibited-jwks:}") String prohibitedKeys) {
        try {
            if (!(JWK.parse(privateKey) instanceof RSAKey transport)) throw new IllegalArgumentException("SLA transport must be RSA.");
            List<RSAKey> prohibited = publicKeys(prohibitedKeys);
            List<RSAKey> trusted = publicKeys(currentKeys);
            var recipient = new HashMap<String, RSAKey>();
            for (RSAKey key : trusted) {
                if (recipient.put(key.getKeyID(), key) != null) throw new IllegalArgumentException("Duplicate SLA key ID.");
            }
            var transportProhibited = new ArrayList<>(prohibited); transportProhibited.addAll(trusted);
            var recipientProhibited = new ArrayList<>(prohibited); recipientProhibited.add(transport.toPublicJWK());
            return new ApprovalSlaRecipientAuthorityClient(URI.create(endpoint),
                    new ApprovalSlaTransportProof(transport, transportProhibited, Clock.systemUTC()),
                    new ApprovalSlaRecipientAuthority(recipient, recipientProhibited, Clock.systemUTC()));
        } catch (Exception error) {
            throw new IllegalStateException("Approval SLA requires complete disjoint trusted public inventories and a dedicated transport private key.", error);
        }
    }

    @Bean
    ApprovalSlaNotificationConsumer approvalSlaNotificationConsumer(PlatformTransactionManager manager,
            NotificationDatabaseScope scope, ApprovalSlaDeliveryJournal journal,
            ApprovalSlaRecipientAuthorityClient client, DirectNotificationMaterializer materializer) {
        return new ApprovalSlaNotificationConsumer(manager, scope, journal, client, materializer);
    }

    @Bean
    ApprovalSlaNotificationKafkaListener approvalSlaNotificationKafkaListener(ApprovalSlaNotificationConsumer consumer) {
        return new ApprovalSlaNotificationKafkaListener(consumer);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> approvalSlaKafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumers, KafkaTemplate<Object, Object> template,
            @Value("${dwp.notification.approval-sla.dead-letter-topic:dwp.approval.events.v1.DLT}") String topic) {
        if (topic == null || topic.isBlank() || !topic.equals(topic.strip())) throw new IllegalArgumentException("Invalid SLA dead-letter topic.");
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        factory.setConsumerFactory(consumers);
        var recoverer = new DeadLetterPublishingRecoverer(template, (record, error) -> new TopicPartition(topic, record.partition()));
        factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000, 3)));
        return factory;
    }

    @Bean
    ApprovalSlaNotificationReadiness approvalSlaNotificationReadiness(
            org.springframework.core.env.Environment environment,
            ApprovalSlaRecipientAuthorityClient authority,
            ApprovalSlaNotificationConsumer consumer,
            ApprovalSlaNotificationKafkaListener listener,
            KafkaAdmin kafkaAdmin) {
        return new ApprovalSlaNotificationReadiness(environment, () -> {
            java.util.Objects.requireNonNull(authority);
            java.util.Objects.requireNonNull(consumer);
            java.util.Objects.requireNonNull(listener);
        }, kafkaAdmin);
    }

    private static List<RSAKey> publicKeys(String raw) throws java.text.ParseException {
        List<RSAKey> result = new ArrayList<>();
        for (JWK parsed : JWKSet.parse(raw).getKeys()) {
            if (!(parsed instanceof RSAKey key) || key.isPrivate() || key.size() < 2048 || key.getKeyID() == null)
                throw new IllegalArgumentException("Invalid SLA public key inventory.");
            result.add(key);
        }
        if (result.isEmpty() || result.size() > 64) throw new IllegalArgumentException("Incomplete SLA public key inventory.");
        return List.copyOf(result);
    }
}
