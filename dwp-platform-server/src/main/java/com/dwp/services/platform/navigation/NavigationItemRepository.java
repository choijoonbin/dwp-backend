package com.dwp.services.platform.navigation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NavigationItemRepository extends JpaRepository<NavigationItem, Long> {

    List<NavigationItem> findByTenantIdOrderBySortOrderAscNavigationItemIdAsc(Long tenantId);

    List<NavigationItem> findByTenantIdAndLifecycleStateOrderBySortOrderAscNavigationItemIdAsc(
            Long tenantId, String lifecycleState);

    Optional<NavigationItem> findByNavigationItemIdAndTenantId(Long itemId, Long tenantId);

    Optional<NavigationItem> findByTenantIdAndNavigationKey(Long tenantId, String navigationKey);

    long countByTenantIdAndParentNavigationItemIdAndLifecycleState(
            Long tenantId, Long parentId, String lifecycleState);
}
