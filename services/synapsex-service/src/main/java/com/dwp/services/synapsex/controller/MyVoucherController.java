package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.voucher.MyVoucherPageResponse;
import com.dwp.services.synapsex.service.voucher.MyVoucherQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/synapse/vouchers")
@RequiredArgsConstructor
public class MyVoucherController {

    private final MyVoucherQueryService myVoucherQueryService;

    @GetMapping("/my")
    public ApiResponse<MyVoucherPageResponse> getMyVouchers(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestHeader(HeaderConstants.X_USER_ID) Long userId,
            @RequestParam(defaultValue = "ALL") String statusFilter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "postingDate,desc") String sort) {

        MyVoucherPageResponse response = myVoucherQueryService.findMyVouchers(
                tenantId, userId, statusFilter, page, size, sort);
        return ApiResponse.success(response);
    }
}
