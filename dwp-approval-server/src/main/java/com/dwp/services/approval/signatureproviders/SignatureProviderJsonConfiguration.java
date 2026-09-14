package com.dwp.services.approval.signatureproviders;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class SignatureProviderJsonConfiguration {
    @Bean Jackson2ObjectMapperBuilderCustomizer approvalSignatureProviderScopedInputs() {
        return builder -> builder.postConfigurer(mapper -> {
            var json = new SignatureProviderJson(mapper);
            var module = new SimpleModule("approvalSignatureProviderScopedInputs");
            for (var type : SignatureProviderJson.INPUTS) register(module, json, type);
            mapper.registerModule(module);
        });
    }

    private static <T> void register(SimpleModule module, SignatureProviderJson json, Class<T> type) {
        module.addDeserializer(type, new JsonDeserializer<T>() {
            @Override public T deserialize(com.fasterxml.jackson.core.JsonParser parser,
                                           com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
                return json.input(parser, type);
            }
        });
    }
}
