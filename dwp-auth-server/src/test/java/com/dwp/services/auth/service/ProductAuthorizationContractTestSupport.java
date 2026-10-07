package com.dwp.services.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

abstract class ProductAuthorizationContractTestSupport {

    protected ObjectMapper objectMapper;
    protected ProductAuthorizationContractValidator validator;

    @BeforeEach
    void setUpContractValidator() {
        objectMapper = Jackson2ObjectMapperBuilder.json().build();
        validator = new ProductAuthorizationContractValidator(objectMapper);
    }

    protected JsonNode generatedDocument(String fileName) throws IOException {
        ClassPathResource resource = new ClassPathResource("product-authorization/" + fileName);
        try (var input = resource.getInputStream()) {
            return objectMapper.readTree(input);
        }
    }

    protected ByteArrayInputStream jsonInput(JsonNode document) throws IOException {
        return jsonInput(objectMapper.writeValueAsString(document));
    }

    protected ByteArrayInputStream jsonInput(String document) {
        return new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8));
    }
}
