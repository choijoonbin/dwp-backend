package com.dwp.services.synapsex.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI(Swagger) 설정 - /api/synapse/** 엔드포인트 스펙
 * Gateway 경유 시: http://localhost:8080/api/synapse/...
 * 직접 호출 시: http://localhost:8085/synapse/...
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI synapseOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DWP Synapse API")
                        .description("Synapse 데이터 신뢰·거버넌스·리포팅 API. " +
                                "documents, open-items, entities, cases, actions, anomalies, archive, RAG, " +
                                "policies, guardrails, dictionary, feedback, reconciliation, action-recon, analytics, audit")
                        .version("1.0")
                        .contact(new Contact().name("DWP Backend")))
                .servers(List.of(
                        new Server().url("/").description("Gateway(8080) 또는 Direct(8085)")));
    }

    @Bean
    public GroupedOpenApi synapseApi() {
        return GroupedOpenApi.builder()
                .group("synapse")
                .pathsToMatch("/synapse/**")
                .build();
    }
}
