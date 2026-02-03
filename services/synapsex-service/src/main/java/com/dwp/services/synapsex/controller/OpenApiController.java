package com.dwp.services.synapsex.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * OpenAPI 계약 고정 엔드포인트.
 * 프론트엔드가 TS 타입/클라이언트 생성 시 사용하는 고정 경로.
 * 버전은 OpenApiConfig.info.version (1.0) 기준.
 */
@RestController
public class OpenApiController {

    /**
     * GET /openapi.json - Synapse API 스펙 (v3/api-docs/synapse와 동일)
     * breaking change는 PR에서 diff로 감지.
     */
    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public void getOpenApiJson(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        RequestDispatcher rd = request.getRequestDispatcher("/v3/api-docs/synapse");
        rd.forward(request, response);
    }
}
