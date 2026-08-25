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
@Table(name = "usr_home_widget_configurations")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeWidgetConfiguration extends HomePersonalizationEntity {
    @Id
    @Column(name = "widget_configuration_id")
    private UUID widgetConfigurationId;
    @Column(name = "view_id", nullable = false)
    private UUID viewId;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "widget_key", nullable = false, length = 40)
    private String widgetKey;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "configuration_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode configurationPayload;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
