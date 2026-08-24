package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ApprovalRecoveryAuditorDtos;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public final class ApprovalRecoveryAuditorRequestParser {

    private static final int MAXIMUM_BYTES = 2_048;
    private static final Set<String> FIELDS = Set.of(
            "tenantId", "outboxId", "originatorUserId", "resourceSetKey");
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public ApprovalRecoveryAuditorRequestParser(
            ObjectMapper objectMapper,
            Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public ApprovalRecoveryAuditorDtos.ResolveRequest parse(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
            throw invalid();
        }
        try {
            JsonNode root = objectMapper.readerFor(JsonNode.class)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
            if (!(root instanceof ObjectNode object) || !FIELDS.equals(fields(object))) {
                throw invalid();
            }
            ApprovalRecoveryAuditorDtos.ResolveRequest request = objectMapper.treeToValue(
                    object, ApprovalRecoveryAuditorDtos.ResolveRequest.class);
            if (!validator.validate(request).isEmpty()) throw invalid();
            return request;
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private Set<String> fields(ObjectNode value) {
        return value.propertyStream().map(java.util.Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_FORMAT,
                "Invalid approval recovery auditor request.");
    }
}
