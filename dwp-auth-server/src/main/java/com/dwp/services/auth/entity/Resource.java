package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "com_resources",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "type", "key"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Resource extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(nullable = false, length = 255)
    private String key;

    @Column(nullable = false, length = 200)
    private String name;

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;
}
