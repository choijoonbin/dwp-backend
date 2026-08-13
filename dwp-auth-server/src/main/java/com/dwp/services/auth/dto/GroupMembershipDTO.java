package com.dwp.services.auth.dto;

import java.util.UUID;

public record GroupMembershipDTO(
        UUID groupRef,
        String displayName) {
}
