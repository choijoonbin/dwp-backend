package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.forms.ApprovalFormLegacySchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApprovalFormLegacySchemaValidationConfig {
    @Bean
    public ApprovalFormLegacySchemaValidator approvalFormLegacySchemaValidator(ObjectMapper mapper, Validator validator) {
        var original = new ApprovalCommandPayloadSupport(mapper);
        return definition -> {
            Object version = definition.get("schemaVersion");
            if (definition.containsKey("schemaContract") || !(Integer.valueOf(1).equals(version) || Integer.valueOf(2).equals(version))
                    || !(definition.get("fields") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 50) throw invalid();
            List<ApprovalDtos.FormFieldInput> fields;
            try {
                fields = raw.stream().map(value -> mapper.convertValue(value, ApprovalDtos.FormFieldInput.class)).toList();
            } catch (IllegalArgumentException exception) { throw invalid(); }
            if (fields.stream().anyMatch(field -> field == null || !validator.validate(field).isEmpty())) throw invalid();
            original.validateFormFields(fields);
            Map<String, Object> result = new LinkedHashMap<>(original.formSchema(fields));
            result.put("schemaVersion", version);
            return result;
        };
    }

    private BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Legacy form authoring must satisfy the original field contract.");
    }
}
