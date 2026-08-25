package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public final class ProductAuthorizationOperationsRequestParser {

    private static final int MAXIMUM_BYTES = 4_096;
    private static final Set<String> APPROVAL_FIELDS = Set.of(
            "checksum", "requestedBy", "approvedBy", "changeRef");
    private static final Set<String> ACTIVATION_FIELDS = Set.of(
            "checksum", "expectedRevision", "activatedBy", "changeRef");
    private static final Set<String> ROLLBACK_FIELDS = Set.of(
            "checksum", "expectedRevision", "rolledBackBy", "changeRef", "reason");

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public ProductAuthorizationOperationsRequestParser(
            ObjectMapper objectMapper,
            Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public ProductAuthorizationContractDtos.ApprovalCommand parseApproval(InputStream body) {
        ObjectNode object = exactObject(body, APPROVAL_FIELDS);
        requireText(object, "checksum", "requestedBy", "approvedBy", "changeRef");
        return validated(object, ProductAuthorizationContractDtos.ApprovalCommand.class);
    }

    public ProductAuthorizationContractDtos.ActivationCommand parseActivation(InputStream body) {
        ObjectNode object = exactObject(body, ACTIVATION_FIELDS);
        requireText(object, "checksum", "activatedBy", "changeRef");
        requireLong(object, "expectedRevision");
        return validated(object, ProductAuthorizationContractDtos.ActivationCommand.class);
    }

    public ProductAuthorizationContractDtos.RollbackCommand parseRollback(InputStream body) {
        ObjectNode object = exactObject(body, ROLLBACK_FIELDS);
        requireText(object, "checksum", "rolledBackBy", "changeRef", "reason");
        requireLong(object, "expectedRevision");
        return validated(object, ProductAuthorizationContractDtos.RollbackCommand.class);
    }

    private ObjectNode exactObject(InputStream body, Set<String> expectedFields) {
        if (body == null) throw invalid();
        try {
            byte[] bytes = body.readNBytes(MAXIMUM_BYTES + 1);
            if (bytes.length > MAXIMUM_BYTES) throw invalid();
            JsonNode root = objectMapper.readerFor(JsonNode.class)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(bytes);
            if (!(root instanceof ObjectNode object)
                    || !expectedFields.equals(fields(object))) {
                throw invalid();
            }
            return object;
        } catch (BaseException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private void requireText(ObjectNode object, String... names) {
        for (String name : names) {
            if (!object.path(name).isTextual()) throw invalid();
        }
    }

    private void requireLong(ObjectNode object, String name) {
        JsonNode value = object.path(name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
    }

    private <T> T validated(ObjectNode object, Class<T> type) {
        try {
            T command = objectMapper.treeToValue(object, type);
            if (!validator.validate(command).isEmpty()) throw invalid();
            return command;
        } catch (BaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private Set<String> fields(ObjectNode value) {
        return value.propertyStream()
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    private BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_FORMAT,
                "Invalid product authorization operations command.");
    }
}
