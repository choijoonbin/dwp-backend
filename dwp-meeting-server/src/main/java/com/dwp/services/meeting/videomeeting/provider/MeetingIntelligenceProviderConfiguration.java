package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.core.crypto.EnvelopeCiphertextCodec;
import com.dwp.core.crypto.EnvelopeEncryptionService;
import com.dwp.core.crypto.KeyContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeetingIntelligenceHttpProperties.class)
public class MeetingIntelligenceProviderConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "dwp.meeting.intelligence",
            name = "provider",
            havingValue = "http")
    MeetingIntelligenceProvider governedHttpMeetingIntelligenceProvider(
            MeetingIntelligenceHttpProperties properties,
            ObjectMapper objectMapper,
            MeetingWorkloadAssertionSigner signer) {
        return new GovernedHttpMeetingIntelligenceProvider(properties, objectMapper, signer);
    }

    @Bean
    @ConditionalOnExpression(
            "'${dwp.meeting.intelligence.provider:disabled}' == 'http' || "
                    + "'${dwp.meeting.transcript-source.provider:disabled}' == 'http'")
    MeetingWorkloadAssertionSigner meetingWorkloadAssertionSigner(
            MeetingIntelligenceHttpProperties properties) {
        return new MeetingWorkloadAssertionSigner(properties);
    }

    @Bean
    @ConditionalOnMissingBean(MeetingIntelligenceProvider.class)
    MeetingIntelligenceProvider meetingIntelligenceProvider() {
        return new DisabledMeetingIntelligenceProvider();
    }

    @Bean
    @ConditionalOnMissingBean(MeetingTranscriptSource.class)
    MeetingTranscriptSource meetingTranscriptSource() {
        return new DisabledMeetingTranscriptSource();
    }

    @Bean
    @ConditionalOnMissingBean(MeetingIntelligencePayloadProtector.class)
    MeetingIntelligencePayloadProtector meetingIntelligencePayloadProtector(
            ObjectProvider<EnvelopeEncryptionService> encryption,
            Environment environment) {
        EnvelopeEncryptionService service = encryption.getIfAvailable();
        if (service == null) return new DisabledPayloadProtector();
        String runtime = environment.getProperty("dwp.environment", "local")
                .trim().toLowerCase(Locale.ROOT);
        return new EnvelopePayloadProtector(service, runtime);
    }
}

final class DisabledMeetingIntelligenceProvider implements MeetingIntelligenceProvider {

    @Override
    public Capability capability(ExecutionContext context) {
        return Capability.unavailable();
    }

    @Override
    public Analysis analyze(ExecutionContext context, Request request) {
        throw new IllegalStateException("Meeting intelligence provider is disabled.");
    }
}

final class DisabledMeetingTranscriptSource implements MeetingTranscriptSource {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public java.util.List<MeetingIntelligenceProvider.TranscriptSegment> read(
            ReadContext context) {
        throw new IllegalStateException("Meeting transcript source is disabled.");
    }
}

final class DisabledPayloadProtector implements MeetingIntelligencePayloadProtector {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean ready() {
        return false;
    }

    @Override
    public String protect(long tenantId, UUID reportId, byte[] plaintext) {
        throw new IllegalStateException("Meeting intelligence encryption is unavailable.");
    }

    @Override
    public byte[] unprotect(long tenantId, UUID reportId, String protectedPayload) {
        throw new IllegalStateException("Meeting intelligence encryption is unavailable.");
    }
}

final class EnvelopePayloadProtector implements MeetingIntelligencePayloadProtector {

    private static final String SERVICE = "dwp-meeting-server";
    private static final String PURPOSE = "meeting-intelligence";
    private static final String RESOURCE = "meeting-intelligence-report";
    private static final String FIELD = "report-payload";

    private final EnvelopeEncryptionService encryption;
    private final EnvelopeCiphertextCodec codec = new EnvelopeCiphertextCodec();
    private final String environment;

    EnvelopePayloadProtector(EnvelopeEncryptionService encryption, String environment) {
        this.encryption = encryption;
        this.environment = environment;
        new KeyContext(environment, SERVICE, PURPOSE, 0, RESOURCE, "configuration-probe", FIELD);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean ready() {
        try {
            encryption.probe(new KeyContext(
                    environment, SERVICE, PURPOSE, 0,
                    RESOURCE, "runtime-readiness", FIELD));
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public String protect(long tenantId, UUID reportId, byte[] plaintext) {
        return codec.encode(encryption.encrypt(plaintext, context(tenantId, reportId)));
    }

    @Override
    public byte[] unprotect(long tenantId, UUID reportId, String protectedPayload) {
        return encryption.decrypt(codec.decode(protectedPayload), context(tenantId, reportId));
    }

    private KeyContext context(long tenantId, UUID reportId) {
        return new KeyContext(
                environment, SERVICE, PURPOSE, tenantId,
                RESOURCE, reportId.toString(), FIELD);
    }
}
