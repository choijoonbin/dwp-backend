package com.dwp.services.approval.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApprovalFormPayloadNormalizationConfig {
    @Bean
    public ApprovalFormPayloadNormalization approvalFormPayloadNormalization(ApprovalCommandRepository commands,
            ApprovalFormReferenceNormalizer references, ObjectMapper mapper) {
        return new ApprovalFormPayloadNormalizer(mapper, references, commands::normalizeRequestPayload);
    }
}
