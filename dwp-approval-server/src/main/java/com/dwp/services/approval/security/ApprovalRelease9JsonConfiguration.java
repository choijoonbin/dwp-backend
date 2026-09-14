package com.dwp.services.approval.security;

import com.dwp.services.approval.attachment.ApprovalAttachmentDtos;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Separate readers for unreleased9 inputs, preserving the configured mapper and all legacy converter behavior. */
@Configuration(proxyBeanMethods = false)
public class ApprovalRelease9JsonConfiguration implements WebMvcConfigurer {
    private static final List<Class<?>> INPUTS = List.of(ApprovalFormLifecycleDtos.MetadataInput.class,
            ApprovalFormLifecycleDtos.Branch.class, ApprovalFormLifecycleDtos.AvailabilityChange.class,
            ApprovalFormLifecycleDtos.PublishReviewed.class, ApprovalFormLifecycleDtos.UpdateWorkingDraft.class,
            ApprovalAttachmentDtos.InitializePolicy.class, ApprovalAttachmentDtos.SavePolicy.class,
            ApprovalAttachmentDtos.PublishPolicy.class, ApprovalAttachmentDtos.Reserve.class,
            ApprovalAttachmentDtos.Cancel.class, ApprovalAttachmentDtos.Selection.class, ApprovalAttachmentDtos.Download.class);

    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : converters) {
            if (!(converter instanceof MappingJackson2HttpMessageConverter jackson)) continue;
            var strict = jackson.getObjectMapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            for (var input : INPUTS) jackson.registerObjectMappersForType(input, mappings -> mappings.put(MediaType.APPLICATION_JSON, strict));
        }
    }
}
