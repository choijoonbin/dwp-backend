package com.dwp.services.people.workforce;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public final class WorkforceReferenceDtos {

    private WorkforceReferenceDtos() {
    }

    public record ReferenceCatalog(
            String catalogKey,
            String ownership,
            boolean editable,
            List<ReferenceValue> values) {
    }

    public record ReferenceValue(
            String code,
            String displayName,
            String description,
            Map<String, String> labels,
            String localizedLabel,
            int sortOrder,
            String lifecycleState,
            boolean predefined,
            String detail,
            long version) {
    }

    public record UpdateReferenceValueRequest(
            @NotBlank @Size(max = 160) String displayName,
            @Size(max = 1000) String description,
            @NotNull Map<@Pattern(regexp = "[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8})?") String,
                    @Size(max = 160) String> labels,
            @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String lifecycleState,
            @Min(0) long version) {
    }
}
