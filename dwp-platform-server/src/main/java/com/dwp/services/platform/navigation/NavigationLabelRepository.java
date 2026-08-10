package com.dwp.services.platform.navigation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface NavigationLabelRepository extends JpaRepository<NavigationLabel, Long> {

    List<NavigationLabel> findByTenantIdAndNavigationItemIdIn(
            Long tenantId, Collection<Long> itemIds);

    void deleteByTenantIdAndNavigationItemId(Long tenantId, Long itemId);
}
