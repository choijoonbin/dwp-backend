package com.dwp.services.platform.home.personalization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HomeDeviceLayoutRepository extends JpaRepository<HomeDeviceLayout, UUID> {
    List<HomeDeviceLayout> findByViewIdAndTenantIdAndUserIdOrderByDeviceClass(
            UUID viewId, Long tenantId, Long userId);
    Optional<HomeDeviceLayout> findByViewIdAndTenantIdAndUserIdAndDeviceClass(
            UUID viewId, Long tenantId, Long userId, String deviceClass);
}
