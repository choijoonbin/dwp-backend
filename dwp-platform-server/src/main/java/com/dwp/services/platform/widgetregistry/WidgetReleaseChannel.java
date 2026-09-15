package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "plt_widget_release_channels")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WidgetReleaseChannel {
    @Id
    @Column(name = "release_channel_id", nullable = false)
    private UUID releaseChannelId;
    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;
    @Column(name = "channel", nullable = false, length = 16)
    private String channel;
    @Column(name = "current_version_id")
    private UUID currentVersionId;
    @Column(name = "previous_version_id")
    private UUID previousVersionId;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    @Column(name = "updated_by")
    private Long updatedBy;
}
