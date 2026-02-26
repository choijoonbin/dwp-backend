package com.dwp.services.synapsex.service.voucher;

import com.dwp.services.synapsex.dto.voucher.MyVoucherPageResponse;
import com.dwp.services.synapsex.dto.voucher.MyVoucherRowDto;
import com.dwp.services.synapsex.repository.voucher.MyVoucherQueryRepository;
import com.dwp.services.synapsex.service.security.OwnershipAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyVoucherQueryService {

    private final MyVoucherQueryRepository myVoucherQueryRepository;
    private final OwnershipAccessService ownershipAccessService;

    @Transactional(readOnly = true)
    public MyVoucherPageResponse findMyVouchers(Long tenantId, Long userId, String statusFilter, int page, int size, String sort) {
        String normalizedFilter = normalizeStatusFilter(statusFilter);
        String normalizedSort = normalizeSort(sort);
        String orderDirection = normalizedSort.endsWith(",asc") ? "ASC" : "DESC";
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        boolean isAdmin = ownershipAccessService.isAdmin(tenantId, userId);
        boolean applyUserFilter = !isAdmin;

        long totalElements = myVoucherQueryRepository.countMyVouchers(tenantId, userId, normalizedFilter, applyUserFilter);
        int totalPages = (int) Math.ceil(totalElements / (double) safeSize);

        java.util.List<MyVoucherRowDto> content = myVoucherQueryRepository.findMyVouchers(
                tenantId, userId, normalizedFilter, safePage, safeSize, orderDirection, applyUserFilter);

        return MyVoucherPageResponse.builder()
                .content(content)
                .page(safePage)
                .size(safeSize)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .hasNext((long) (safePage + 1) * safeSize < totalElements)
                .sort(normalizedSort)
                .build();
    }

    private static String normalizeStatusFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return "ALL";
        }
        return "PENDING_EXPLANATION".equalsIgnoreCase(statusFilter) ? "PENDING_EXPLANATION" : "ALL";
    }

    private static String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return "postingDate,desc";
        }
        String value = sort.trim().toLowerCase();
        if (!value.startsWith("postingdate")) {
            return "postingDate,desc";
        }
        return value.endsWith(",asc") ? "postingDate,asc" : "postingDate,desc";
    }
}
