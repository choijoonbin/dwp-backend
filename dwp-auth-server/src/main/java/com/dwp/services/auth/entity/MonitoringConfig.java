package com.dwp.services.auth.entity;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 모니터링 설정 엔티티 (sys_monitoring_configs)
 * config_key는 sys_codes 코드값(AVAILABILITY_MIN_REQ_PER_MINUTE, AVAILABILITY_ERROR_RATE_THRESHOLD 등)으로 관리
 */
@Entity
@Table(name = "sys_monitoring_configs",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "config_key"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonitoringConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "monitoring_config_id")
    private Long monitoringConfigId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "config_key", nullable = false, length = 100)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 500)
    private String configValue;

    @Column(name = "description", length = 500)
    private String description;
}
