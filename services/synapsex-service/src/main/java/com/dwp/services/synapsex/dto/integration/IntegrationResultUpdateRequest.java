package com.dwp.services.synapsex.dto.integration;

import lombok.Data;

@Data
public class IntegrationResultUpdateRequest {

    private String status = "PROCESSED";
    private String resultMessage;
}
