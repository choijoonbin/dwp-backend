package com.dwp.services.approval.formsv3;

import com.dwp.services.approval.templates.ApprovalTemplateHttpModels;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class ApprovalFormV3JsonConfiguration implements WebMvcConfigurer {
    private static final Set<Class<?>> INPUTS = Set.of(
            ApprovalTemplateHttpModels.CloneDraftRequest.class,
            ApprovalTemplateHttpModels.InstallDraftRequest.class,
            ApprovalFormV3HttpModels.ValidateSchemaRequest.class,
            ApprovalFormV3HttpModels.EvaluateRequest.class,
            ApprovalFormV3HttpModels.CloneDraftRequest.class,
            ApprovalFormV3HttpModels.UpdateDraftRequest.class,
            ApprovalFormV3HttpModels.ArchiveDraftRequest.class);

    @Bean
    Jackson2ObjectMapperBuilderCustomizer approvalFormV3StrictUnknownPropertyCustomizer() {
        return builder -> builder.postConfigurer(mapper -> mapper.addHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
                    JsonDeserializer<?> deserializer, Object beanOrClass, String propertyName) throws IOException {
                Class<?> type = beanOrClass instanceof Class<?> candidate ? candidate
                        : beanOrClass == null ? null : beanOrClass.getClass();
                if (type == null || !INPUTS.contains(type)) return false;
                throw UnrecognizedPropertyException.from(parser, beanOrClass, propertyName,
                        deserializer.getKnownPropertyNames());
            }
        }));
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (!(converter instanceof MappingJackson2HttpMessageConverter jackson)) continue;
            var strict = jackson.getObjectMapper().copy()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            for (Class<?> input : INPUTS) {
                jackson.registerObjectMappersForType(input,
                        mappings -> mappings.put(MediaType.APPLICATION_JSON, strict));
            }
        }
    }
}
