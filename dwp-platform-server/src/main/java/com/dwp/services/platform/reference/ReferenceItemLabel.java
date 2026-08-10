package com.dwp.services.platform.reference;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "adm_reference_item_labels",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"tenant_id", "reference_item_id", "locale"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceItemLabel extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reference_item_label_id")
    private Long referenceItemLabelId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "reference_item_id", nullable = false)
    private Long referenceItemId;

    @Column(name = "locale", nullable = false, length = 35)
    private String locale;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "description", length = 1000)
    private String description;
}
