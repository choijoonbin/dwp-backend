package com.dwp.services.mcp.dto.mcp;

import lombok.Data;

@Data
public class MasterDataNormalizeRequest {
    private String mccCode;
    private String expenseType;
    private String hrStatus;
}
