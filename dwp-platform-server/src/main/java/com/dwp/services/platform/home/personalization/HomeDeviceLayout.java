package com.dwp.services.platform.home.personalization;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "usr_home_view_device_layouts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeDeviceLayout extends HomePersonalizationEntity {
    @Id
    @Column(name = "device_layout_id")
    private UUID deviceLayoutId;
    @Column(name = "view_id", nullable = false)
    private UUID viewId;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "device_class", nullable = false, length = 16)
    private String deviceClass;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "overlay_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode overlayPayload;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
