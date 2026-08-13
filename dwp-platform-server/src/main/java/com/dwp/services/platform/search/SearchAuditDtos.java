package com.dwp.services.platform.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class SearchAuditDtos {

    private SearchAuditDtos() {
    }

    public record AuditRequest(
            @NotBlank @Pattern(regexp = "QUERY|SELECTION") String phase,
            @NotBlank @Size(max = 256) String query,
            @Size(max = 12) List<@Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String> sources,
            @Min(0) @Max(100) Integer resultCount,
            @Size(max = 40) String selectedKind,
            @Size(max = 256) String selectedId) {
    }

    public record AuditReceipt(String eventId, String queryDigest) {
    }
}
