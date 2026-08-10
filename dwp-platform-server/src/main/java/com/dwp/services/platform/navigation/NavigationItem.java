package com.dwp.services.platform.navigation;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "adm_navigation_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NavigationItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "navigation_item_id")
    private Long navigationItemId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "navigation_key", nullable = false, length = 120)
    private String navigationKey;

    @Column(name = "item_type", nullable = false, length = 20)
    private String itemType;

    @Column(name = "parent_navigation_item_id")
    private Long parentNavigationItemId;

    @Column(name = "registry_entry_key", length = 100)
    private String registryEntryKey;

    @Column(length = 500)
    private String route;

    @Column(name = "icon_key", length = 80)
    private String iconKey;

    @Column(name = "required_resource_key", length = 255)
    private String requiredResourceKey;

    @Builder.Default
    @Column(name = "required_permission_code", nullable = false, length = 50)
    private String requiredPermissionCode = "VIEW";

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Builder.Default
    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState = "DRAFT";

    @Version
    @Column(nullable = false)
    private Long version;
}
