package com.dwp.services.platform.navigation;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "adm_navigation_labels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NavigationLabel extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "navigation_label_id")
    private Long navigationLabelId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "navigation_item_id", nullable = false)
    private Long navigationItemId;

    @Column(nullable = false, length = 35)
    private String locale;

    @Column(nullable = false, length = 160)
    private String label;

    @Column(length = 500)
    private String description;
}
