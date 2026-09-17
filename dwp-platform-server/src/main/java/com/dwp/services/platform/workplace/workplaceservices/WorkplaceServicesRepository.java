package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;

@Repository
public class WorkplaceServicesRepository extends WorkplaceServicesOrderRepositorySupport {
    public WorkplaceServicesRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    public Optional<ReservationSnapshot> reservation(
            long tenantId, long actorUserId, ReservationAuthority authority, UUID reservationId) {
        if (authority == ReservationAuthority.WORKPLACE) {
            return jdbc.query("""
                    SELECT booking.booking_id reservation_id, booking.version,
                           booking.user_id owner_user_id, booking.starts_at, booking.ends_at,
                           site.site_id::text site_reference,
                           resource.resource_id::text resource_reference,
                           resource.resource_type,
                           booking.booking_status IN ('RESERVED', 'CHECKED_IN') active
                      FROM wp_bookings booking
                      JOIN wp_resources resource
                        ON resource.tenant_id = booking.tenant_id
                       AND resource.resource_id = booking.resource_id
                      JOIN wp_floors floor
                        ON floor.tenant_id = resource.tenant_id
                       AND floor.floor_id = resource.floor_id
                      JOIN wp_sites site
                        ON site.tenant_id = floor.tenant_id
                       AND site.site_id = floor.site_id
                     WHERE booking.tenant_id = ? AND booking.booking_id = ?
                       AND booking.user_id = ?
                    """, (rs, row) -> reservation(rs, authority), tenantId, reservationId, actorUserId)
                    .stream().findFirst();
        }
        return jdbc.query("""
                SELECT event.event_id reservation_id, event.version,
                       event.organizer_user_id owner_user_id, event.starts_at, event.ends_at,
                       COALESCE(workplace_site.site_id::text, resource.site_name) site_reference,
                       resource.resource_id::text resource_reference,
                       resource.resource_type,
                       (event.status IN ('CONFIRMED', 'TENTATIVE')
                        AND booking.booking_status IN ('PENDING', 'CONFIRMED')) active
                  FROM cal_events event
                  JOIN cal_resource_bookings booking
                    ON booking.tenant_id = event.tenant_id
                   AND booking.event_id = event.event_id
                  JOIN cal_resources resource
                    ON resource.tenant_id = booking.tenant_id
                   AND resource.resource_id = booking.resource_id
                  LEFT JOIN wp_resources workplace_resource
                    ON workplace_resource.tenant_id = resource.tenant_id
                   AND workplace_resource.calendar_resource_id = resource.resource_id
                  LEFT JOIN wp_floors workplace_floor
                    ON workplace_floor.tenant_id = workplace_resource.tenant_id
                   AND workplace_floor.floor_id = workplace_resource.floor_id
                 LEFT JOIN wp_sites workplace_site
                    ON workplace_site.tenant_id = workplace_floor.tenant_id
                   AND workplace_site.site_id = workplace_floor.site_id
                 WHERE event.tenant_id = ? AND event.event_id = ?
                   AND event.organizer_user_id = ?
                   AND event.status IN ('CONFIRMED', 'TENTATIVE')
                   AND booking.booking_status IN ('PENDING', 'CONFIRMED')
                   AND 1 = (
                       SELECT COUNT(*)
                         FROM cal_resource_bookings governed_booking
                        WHERE governed_booking.tenant_id = event.tenant_id
                          AND governed_booking.event_id = event.event_id
                          AND governed_booking.booking_status IN ('PENDING', 'CONFIRMED'))
                """, (rs, row) -> reservation(rs, authority), tenantId, reservationId,
                actorUserId).stream().findFirst();
    }

    public List<CatalogRow> catalog(long tenantId, String resourceType, String siteReference) {
        return jdbc.query("""
                SELECT item.*, truth.configured, truth.configuration_version,
                       truth.observed_configuration_version, truth.reported_state,
                       truth.evidence_reference, truth.observed_at, truth.received_at,
                       truth.error_code provider_error_code,
                       profile.lifecycle_state provider_profile_state,
                       profile.credential_binding_reference,
                       profile.configuration_version provider_profile_configuration_version
                  FROM wp_service_catalog_items item
                  JOIN wp_service_provider_truth truth
                    ON truth.tenant_id = item.tenant_id
                   AND truth.provider_code = item.provider_code
                  LEFT JOIN wp_service_provider_profiles profile
                    ON profile.tenant_id = item.tenant_id
                   AND profile.provider_code = item.provider_code
                 WHERE item.tenant_id = ? AND item.lifecycle_state = 'ACTIVE'
                   AND jsonb_exists(item.supported_resource_types, ?)
                   AND (item.site_scope = '[]'::jsonb OR jsonb_exists(item.site_scope, ?))
                 ORDER BY item.category, item.service_code
                """, this::catalogRow, tenantId, resourceType, siteReference == null ? "" : siteReference);
    }

    public Optional<CatalogRow> catalogItem(long tenantId, UUID catalogItemId) {
        return jdbc.query("""
                SELECT item.*, truth.configured, truth.configuration_version,
                       truth.observed_configuration_version, truth.reported_state,
                       truth.evidence_reference, truth.observed_at, truth.received_at,
                       truth.error_code provider_error_code,
                       profile.lifecycle_state provider_profile_state,
                       profile.credential_binding_reference,
                       profile.configuration_version provider_profile_configuration_version
                  FROM wp_service_catalog_items item
                  JOIN wp_service_provider_truth truth
                    ON truth.tenant_id = item.tenant_id
                   AND truth.provider_code = item.provider_code
                  LEFT JOIN wp_service_provider_profiles profile
                    ON profile.tenant_id = item.tenant_id
                   AND profile.provider_code = item.provider_code
                 WHERE item.tenant_id = ? AND item.catalog_item_id = ?
                   AND item.lifecycle_state = 'ACTIVE'
                """, this::catalogRow, tenantId, catalogItemId).stream().findFirst();
    }

    public Optional<CatalogRow> adminCatalogItem(long tenantId, UUID catalogItemId) {
        return jdbc.query("""
                SELECT item.*, truth.configured, truth.configuration_version,
                       truth.observed_configuration_version, truth.reported_state,
                       truth.evidence_reference, truth.observed_at, truth.received_at,
                       truth.error_code provider_error_code,
                       profile.lifecycle_state provider_profile_state,
                       profile.credential_binding_reference,
                       profile.configuration_version provider_profile_configuration_version
                  FROM wp_service_catalog_items item
                  JOIN wp_service_provider_truth truth
                    ON truth.tenant_id = item.tenant_id
                   AND truth.provider_code = item.provider_code
                  LEFT JOIN wp_service_provider_profiles profile
                    ON profile.tenant_id = item.tenant_id
                   AND profile.provider_code = item.provider_code
                 WHERE item.tenant_id = ? AND item.catalog_item_id = ?
                """, this::catalogRow, tenantId, catalogItemId).stream().findFirst();
    }

    public List<CatalogRow> adminCatalog(long tenantId) {
        return jdbc.query("""
                SELECT item.*, truth.configured, truth.configuration_version,
                       truth.observed_configuration_version, truth.reported_state,
                       truth.evidence_reference, truth.observed_at, truth.received_at,
                       truth.error_code provider_error_code,
                       profile.lifecycle_state provider_profile_state,
                       profile.credential_binding_reference,
                       profile.configuration_version provider_profile_configuration_version
                  FROM wp_service_catalog_items item
                  JOIN wp_service_provider_truth truth
                    ON truth.tenant_id = item.tenant_id
                   AND truth.provider_code = item.provider_code
                  LEFT JOIN wp_service_provider_profiles profile
                    ON profile.tenant_id = item.tenant_id
                   AND profile.provider_code = item.provider_code
                 WHERE item.tenant_id = ?
                 ORDER BY item.category, item.service_code
                """, this::catalogRow, tenantId);
    }

    public boolean providerExists(long tenantId, String providerCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM wp_service_provider_truth
                 WHERE tenant_id = ? AND provider_code = ?)
                """, Boolean.class, tenantId, providerCode));
    }

    public boolean sitesExist(long tenantId, List<UUID> siteIds) {
        if (siteIds.isEmpty()) return true;
        return siteIds.stream().allMatch(siteId -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM wp_sites
                 WHERE tenant_id = ? AND site_id = ?)
                """, Boolean.class, tenantId, siteId)));
    }

    public void createCatalogItem(long tenantId, UUID itemId, CatalogCreateRequest request,
                                  OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_service_catalog_items (
                    catalog_item_id, tenant_id, service_code, category, name_ko, name_en,
                    description_ko, description_en, provider_code, site_scope, option_schema,
                    supported_resource_types, unit_price, currency, minimum_quantity,
                    maximum_quantity, order_cutoff_minutes, cancellation_cutoff_minutes,
                    sla_response_minutes, sla_fulfillment_lead_minutes,
                    cancellation_policy_ko, cancellation_policy_en,
                    capacity_mode, capacity_freshness_seconds,
                    inspection_mode, inspection_checklist_schema,
                    requires_attendee_count, requires_cost_center, lifecycle_state,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        ?, ?, 'ACTIVE', 1, ?, ?)
                """, itemId, tenantId, request.serviceCode().trim().toUpperCase(),
                request.category().name(), request.nameKo().trim(), request.nameEn().trim(),
                normalize(request.descriptionKo()), normalize(request.descriptionEn()),
                request.providerCode().trim(), json(request.siteScope()), json(request.optionSchema()),
                json(request.supportedResourceTypes()), request.unitPrice(),
                request.currency().trim().toUpperCase(), request.minimumQuantity(),
                request.maximumQuantity(), request.orderCutoffMinutes(),
                request.cancellationCutoffMinutes(), request.slaResponseMinutes(),
                request.slaFulfillmentLeadMinutes(), request.cancellationPolicyKo().trim(),
                request.cancellationPolicyEn().trim(), request.capacityMode().name(),
                request.capacityFreshnessSeconds(), request.inspectionMode().name(),
                json(request.inspectionChecklistSchema()), request.requiresAttendeeCount(),
                request.requiresCostCenter(), now, now);
    }

    public boolean updateCatalogItem(long tenantId, UUID itemId, CatalogUpdateRequest request,
                                     OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_catalog_items
                   SET category = ?, name_ko = ?, name_en = ?, description_ko = ?,
                       description_en = ?, provider_code = ?, site_scope = ?::jsonb,
                       option_schema = ?::jsonb, supported_resource_types = ?::jsonb,
                       unit_price = ?, currency = ?, minimum_quantity = ?, maximum_quantity = ?,
                       order_cutoff_minutes = ?, cancellation_cutoff_minutes = ?,
                       sla_response_minutes = ?, sla_fulfillment_lead_minutes = ?,
                       cancellation_policy_ko = ?, cancellation_policy_en = ?,
                       capacity_mode = ?, capacity_freshness_seconds = ?,
                       inspection_mode = ?, inspection_checklist_schema = ?::jsonb,
                       requires_attendee_count = ?, requires_cost_center = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND catalog_item_id = ? AND version = ?
                """, request.category().name(), request.nameKo().trim(), request.nameEn().trim(),
                normalize(request.descriptionKo()), normalize(request.descriptionEn()),
                request.providerCode().trim(), json(request.siteScope()), json(request.optionSchema()),
                json(request.supportedResourceTypes()), request.unitPrice(),
                request.currency().trim().toUpperCase(), request.minimumQuantity(),
                request.maximumQuantity(), request.orderCutoffMinutes(),
                request.cancellationCutoffMinutes(), request.slaResponseMinutes(),
                request.slaFulfillmentLeadMinutes(), request.cancellationPolicyKo().trim(),
                request.cancellationPolicyEn().trim(), request.capacityMode().name(),
                request.capacityFreshnessSeconds(), request.inspectionMode().name(),
                json(request.inspectionChecklistSchema()), request.requiresAttendeeCount(),
                request.requiresCostCenter(), now, tenantId, itemId,
                request.expectedVersion()) == 1;
    }

    public boolean changeCatalogState(long tenantId, UUID itemId, long expectedVersion,
                                      boolean active, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_catalog_items
                   SET lifecycle_state = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND catalog_item_id = ? AND version = ?
                """, active ? "ACTIVE" : "INACTIVE", now, tenantId, itemId,
                expectedVersion) == 1;
    }

    public Optional<CatalogCommandRow> catalogCommand(
            long tenantId, long actorUserId, String scope, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_service_catalog_commands
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND command_scope = ? AND idempotency_key = ?
                """, this::catalogCommandRow, tenantId, actorUserId, scope, idempotencyKey)
                .stream().findFirst();
    }

    public void createCatalogCommand(CatalogCommandRow row) {
        jdbc.update("""
                INSERT INTO wp_service_catalog_commands (
                    command_id, tenant_id, actor_user_id, command_scope, idempotency_key,
                    request_fingerprint, catalog_item_id, command_state, status_href,
                    correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.commandId(), row.tenantId(), row.actorUserId(), row.scope(),
                row.idempotencyKey(), row.fingerprint(), row.catalogItemId(), row.state().name(),
                row.statusHref(), row.correlationId(), row.createdAt(), row.updatedAt());
    }

    public void savePreview(long tenantId, long actorUserId, ServiceOrderPreview preview) {
        jdbc.update("""
                INSERT INTO wp_service_order_previews (
                    preview_id, tenant_id, actor_user_id, reservation_authority,
                    reservation_id, reservation_version, reservation_starts_at,
                    reservation_ends_at, site_reference, resource_reference,
                    attendee_count, cost_center, special_request, estimated_cost,
                    currency, eligible, limitations, request_snapshot, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        ?::jsonb, ?, ?)
                """, preview.previewId(), tenantId, actorUserId,
                preview.reservationAuthority().name(), preview.reservationId(),
                preview.reservationVersion(), preview.reservationStartsAt(),
                preview.reservationEndsAt(), preview.siteReference(), preview.resourceReference(),
                preview.attendeeCount(), preview.costCenter(), preview.specialRequest(),
                preview.estimatedCost(), preview.currency(), preview.eligible(),
                json(preview.limitations()), json(preview), preview.expiresAt(), preview.createdAt());
    }

    public Optional<ServiceOrderPreview> preview(long tenantId, long actorUserId, UUID previewId) {
        return jdbc.query("""
                SELECT request_snapshot::text FROM wp_service_order_previews
                 WHERE tenant_id = ? AND actor_user_id = ? AND preview_id = ?
                """, (rs, row) -> value(rs.getString(1), ServiceOrderPreview.class),
                tenantId, actorUserId, previewId).stream().findFirst();
    }

    public void updatePreviewSnapshot(
            long tenantId, long actorUserId, ServiceOrderPreview preview) {
        jdbc.update("""
                UPDATE wp_service_order_previews
                   SET request_snapshot = ?::jsonb, eligible = ?, limitations = ?::jsonb
                 WHERE tenant_id = ? AND actor_user_id = ? AND preview_id = ?
                """, json(preview), preview.eligible(), json(preview.limitations()),
                tenantId, actorUserId, preview.previewId());
    }

    public Optional<CommandRow> command(
            long tenantId, long actorUserId, String scope, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM wp_service_order_commands
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND command_scope = ? AND idempotency_key = ?
                """, this::commandRow, tenantId, actorUserId, scope, idempotencyKey)
                .stream().findFirst();
    }

    public void lockIdempotencyCommand(
            long tenantId, long actorUserId, String scope, String idempotencyKey) {
        String identity = advisoryLockIdentity(tenantId, actorUserId, scope, idempotencyKey);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, identity), resultSet -> null);
    }

    static String advisoryLockIdentity(
            long tenantId, long actorUserId, String scope, String idempotencyKey) {
        return lengthPrefixed(Long.toString(tenantId)) + lengthPrefixed(Long.toString(actorUserId))
                + lengthPrefixed(scope) + lengthPrefixed(idempotencyKey);
    }

    private static String lengthPrefixed(String value) {
        if (value == null) return "-1:";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void createOrder(OrderRow order) {
        jdbc.update("""
                INSERT INTO wp_service_orders (
                    service_order_id, tenant_id, requester_user_id, preview_id,
                    reservation_authority, reservation_id, reservation_version,
                    reservation_starts_at, reservation_ends_at, site_reference,
                    resource_reference, attendee_count, cost_center, estimated_cost,
                    currency, special_request, order_state, reservation_impact,
                    reconfirmation_required, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, order.orderId(), order.tenantId(), order.requesterUserId(), order.previewId(),
                order.authority().name(), order.reservationId(), order.reservationVersion(),
                order.startsAt(), order.endsAt(), order.siteReference(), order.resourceReference(),
                order.attendeeCount(), order.costCenter(), order.estimatedCost(), order.currency(),
                order.specialRequest(), order.state().name(), order.impact().name(),
                order.reconfirmationRequired(), order.version(), order.createdAt(), order.updatedAt());
    }

    public void createLine(LineRow line) {
        jdbc.update("""
                INSERT INTO wp_service_order_lines (
                    service_order_line_id, tenant_id, service_order_id, catalog_item_id,
                    service_code, category, name_ko, name_en, provider_code,
                    catalog_version, provider_configuration_version,
                    provider_credential_binding_reference, quantity,
                    options, unit_price, estimated_cost, currency,
                    site_scope_snapshot, supported_resource_types_snapshot,
                    option_schema_snapshot, minimum_quantity, maximum_quantity,
                    order_cutoff_minutes,
                    cancellation_cutoff_minutes, cancellation_policy_ko,
                    cancellation_policy_en, sla_response_minutes,
                    sla_fulfillment_lead_minutes, inspection_mode,
                    inspection_checklist_schema, line_state, fulfilled_quantity,
                    cancelled_quantity, refunded_amount, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?,
                        ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                """, line.lineId(), line.tenantId(), line.orderId(), line.catalogItemId(),
                line.serviceCode(), line.category().name(), line.nameKo(), line.nameEn(),
                line.providerCode(), line.catalogVersion(), line.providerConfigurationVersion(),
                line.providerCredentialBindingReference(), line.quantity(), json(line.options()),
                line.unitPrice(), line.estimatedCost(),
                line.currency(), json(line.siteScopeSnapshot()),
                json(line.supportedResourceTypesSnapshot()), json(line.optionSchemaSnapshot()),
                line.minimumQuantity(), line.maximumQuantity(), line.orderCutoffMinutes(),
                line.cancellationCutoffMinutes(), line.cancellationPolicyKo(),
                line.cancellationPolicyEn(), line.slaResponseMinutes(),
                line.slaFulfillmentLeadMinutes(), line.inspectionMode().name(),
                json(line.inspectionChecklistSchema()), line.state().name(), line.fulfilledQuantity(),
                line.cancelledQuantity(), line.refundedAmount(), line.version(), line.createdAt(),
                line.updatedAt());
    }

    public void createTask(TaskRow task) {
        jdbc.update("""
                INSERT INTO wp_service_fulfillment_tasks (
                    fulfillment_task_id, tenant_id, service_order_id, service_order_line_id,
                    task_state, provider_code, response_due_at, due_at, provider_receipt_at,
                    blocker_code, blocker_detail, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.taskId(), task.tenantId(), task.orderId(), task.lineId(),
                task.state().name(), task.providerCode(), task.responseDueAt(), task.dueAt(),
                task.providerReceiptAt(), task.blockerCode(), task.blockerDetail(), task.version(),
                task.createdAt(), task.updatedAt());
    }

    public void createCommand(CommandRow row) {
        jdbc.update("""
                INSERT INTO wp_service_order_commands (
                    command_id, tenant_id, actor_user_id, command_scope, idempotency_key,
                    request_fingerprint, service_order_id, command_state, status_href,
                    correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.commandId(), row.tenantId(), row.actorUserId(), row.scope(),
                row.idempotencyKey(), row.fingerprint(), row.orderId(), row.state().name(),
                row.statusHref(), row.correlationId(), row.createdAt(), row.updatedAt());
    }

    public void appendEvent(
            long tenantId, UUID orderId, String type, long actorUserId,
            JsonNode detail, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_service_order_events (
                    service_order_event_id, tenant_id, service_order_id, event_type,
                    actor_user_id, detail, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), tenantId, orderId, type, actorUserId,
                json(detail), now);
    }

    public boolean advanceOrderVersion(
            long tenantId, UUID orderId, Long requesterUserId,
            long expectedVersion, OffsetDateTime now) {
        if (requesterUserId == null) {
            return jdbc.update("""
                    UPDATE wp_service_orders
                       SET version = version + 1, updated_at = ?
                     WHERE tenant_id = ? AND service_order_id = ? AND version = ?
                    """, now, tenantId, orderId, expectedVersion) == 1;
        }
        return jdbc.update("""
                UPDATE wp_service_orders
                   SET version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND requester_user_id = ? AND version = ?
                """, now, tenantId, orderId, requesterUserId, expectedVersion) == 1;
    }

    public boolean reconfirmOrder(
            long tenantId, long requesterUserId, UUID orderId, long expectedVersion,
            ReservationSnapshot reservation, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_orders
                   SET reservation_version = ?, reservation_starts_at = ?,
                       reservation_ends_at = ?, site_reference = ?, resource_reference = ?,
                       reservation_impact = 'NONE', reconfirmation_required = FALSE,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND requester_user_id = ?
                   AND service_order_id = ? AND version = ?
                """, reservation.version(), reservation.startsAt(), reservation.endsAt(),
                reservation.siteReference(), reservation.resourceReference(), now,
                tenantId, requesterUserId, orderId, expectedVersion) == 1;
    }

    public void addMessage(
            long tenantId, UUID orderId, UUID messageId, long authorUserId,
            String authorRole, String message, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_service_order_messages (
                    message_id, tenant_id, service_order_id, author_user_id,
                    author_role, message, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, messageId, tenantId, orderId, authorUserId, authorRole, message, now);
    }

    public void addAttachment(
            long tenantId, UUID orderId, UUID attachmentId, long uploaderUserId,
            String storageReference, AttachmentUpload attachment, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_service_order_attachments (
                    attachment_id, tenant_id, service_order_id, uploader_user_id,
                    storage_reference, file_name, content_type, byte_size,
                    checksum_sha256, scan_state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUARANTINED', ?)
                """, attachmentId, tenantId, orderId, uploaderUserId, storageReference,
                attachment.fileName(), attachment.contentType(), attachment.byteSize(),
                attachment.checksumSha256(), now);
    }

    public void appendAuditAndOutbox(
            long tenantId, long actorUserId, UUID orderId, String action,
            String eventType, String correlationId, JsonNode detail, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_audit_events (
                    audit_event_id, tenant_id, action, aggregate_type, aggregate_id,
                    actor_user_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, ?, 'WORKPLACE_SERVICE_ORDER', ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), tenantId, action, orderId, actorUserId,
                correlationId, json(detail), now);
        jdbc.update("""
                INSERT INTO wp_service_order_outbox (
                    outbox_event_id, tenant_id, aggregate_id, event_type,
                    payload, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, UUID.randomUUID(), tenantId, orderId, eventType,
                json(detail), correlationId, now);
    }

    public void appendCatalogAuditAndOutbox(
            long tenantId, long actorUserId, UUID catalogItemId, String action,
            String eventType, String correlationId, JsonNode detail, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_audit_events (
                    audit_event_id, tenant_id, action, aggregate_type, aggregate_id,
                    actor_user_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, ?, 'WORKPLACE_SERVICE_CATALOG', ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), tenantId, action, catalogItemId, actorUserId,
                correlationId, json(detail), now);
        jdbc.update("""
                INSERT INTO wp_service_order_outbox (
                    outbox_event_id, tenant_id, aggregate_id, event_type,
                    payload, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, UUID.randomUUID(), tenantId, catalogItemId, eventType,
                json(detail), correlationId, now);
    }

    public Optional<OrderRow> order(long tenantId, UUID orderId) {
        return jdbc.query("""
                SELECT * FROM wp_service_orders
                 WHERE tenant_id = ? AND service_order_id = ?
                """, this::orderRow, tenantId, orderId).stream().findFirst();
    }

    public Optional<OrderRow> orderForUpdate(long tenantId, UUID orderId) {
        return jdbc.query("""
                SELECT * FROM wp_service_orders
                 WHERE tenant_id = ? AND service_order_id = ?
                 FOR UPDATE
                """, this::orderRow, tenantId, orderId).stream().findFirst();
    }

    public Optional<OrderRow> userOrder(long tenantId, long actorUserId, UUID orderId) {
        return jdbc.query("""
                SELECT * FROM wp_service_orders
                 WHERE tenant_id = ? AND requester_user_id = ? AND service_order_id = ?
                """, this::orderRow, tenantId, actorUserId, orderId).stream().findFirst();
    }

    public Optional<OrderRow> userOrderForUpdate(
            long tenantId, long actorUserId, UUID orderId) {
        return jdbc.query("""
                SELECT * FROM wp_service_orders
                 WHERE tenant_id = ? AND requester_user_id = ? AND service_order_id = ?
                 FOR UPDATE
                """, this::orderRow, tenantId, actorUserId, orderId).stream().findFirst();
    }

    public List<OrderRow> userOrderPage(
            long tenantId, long actorUserId, OffsetDateTime cursorCreatedAt,
            UUID cursorOrderId, int fetchLimit) {
        if (cursorCreatedAt == null) {
            return jdbc.query("""
                    SELECT * FROM wp_service_orders
                     WHERE tenant_id = ? AND requester_user_id = ?
                     ORDER BY created_at DESC, service_order_id DESC
                     LIMIT ?
                    """, this::orderRow, tenantId, actorUserId, fetchLimit);
        }
        return jdbc.query("""
                SELECT * FROM wp_service_orders
                 WHERE tenant_id = ? AND requester_user_id = ?
                   AND (created_at, service_order_id) < (?, ?)
                 ORDER BY created_at DESC, service_order_id DESC
                 LIMIT ?
                """, this::orderRow, tenantId, actorUserId, cursorCreatedAt,
                cursorOrderId, fetchLimit);
    }

    public List<OrderRow> adminOrderPage(
            long tenantId, OrderState state, OffsetDateTime cursorCreatedAt,
            UUID cursorOrderId, int fetchLimit) {
        if (state == null && cursorCreatedAt == null) {
            return jdbc.query("""
                    SELECT * FROM wp_service_orders WHERE tenant_id = ?
                     ORDER BY created_at DESC, service_order_id DESC LIMIT ?
                    """, this::orderRow, tenantId, fetchLimit);
        }
        if (state == null) {
            return jdbc.query("""
                    SELECT * FROM wp_service_orders
                     WHERE tenant_id = ? AND (created_at, service_order_id) < (?, ?)
                     ORDER BY created_at DESC, service_order_id DESC LIMIT ?
                    """, this::orderRow, tenantId, cursorCreatedAt, cursorOrderId, fetchLimit);
        }
        if (cursorCreatedAt == null) {
            return jdbc.query("""
                    SELECT * FROM wp_service_orders
                     WHERE tenant_id = ? AND order_state = ?
                     ORDER BY created_at DESC, service_order_id DESC LIMIT ?
                    """, this::orderRow, tenantId, state.name(), fetchLimit);
        }
        return jdbc.query("""
                SELECT * FROM wp_service_orders
                 WHERE tenant_id = ? AND order_state = ?
                   AND (created_at, service_order_id) < (?, ?)
                 ORDER BY created_at DESC, service_order_id DESC LIMIT ?
                """, this::orderRow, tenantId, state.name(), cursorCreatedAt,
                cursorOrderId, fetchLimit);
    }

    public Map<UUID, ReservationSnapshot> reservationsForOrders(
            long tenantId, List<UUID> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        String in = placeholders(orderIds.size());
        Object[] arguments = tenantAndIds(tenantId, orderIds);
        List<OrderReservationRow> rows = new ArrayList<>();
        rows.addAll(jdbc.query("""
                SELECT order_row.service_order_id page_order_id,
                       booking.booking_id reservation_id, booking.version,
                       booking.user_id owner_user_id, booking.starts_at, booking.ends_at,
                       site.site_id::text site_reference,
                       resource.resource_id::text resource_reference,
                       resource.resource_type,
                       booking.booking_status IN ('RESERVED', 'CHECKED_IN') active
                  FROM wp_service_orders order_row
                  JOIN wp_bookings booking
                    ON booking.tenant_id = order_row.tenant_id
                   AND booking.booking_id = order_row.reservation_id
                   AND booking.user_id = order_row.requester_user_id
                  JOIN wp_resources resource
                    ON resource.tenant_id = booking.tenant_id
                   AND resource.resource_id = booking.resource_id
                  JOIN wp_floors floor
                    ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                  JOIN wp_sites site
                    ON site.tenant_id = floor.tenant_id
                   AND site.site_id = floor.site_id
                 WHERE order_row.tenant_id = ?
                   AND order_row.reservation_authority = 'WORKPLACE'
                   AND order_row.service_order_id IN (""" + in + ")",
                (rs, row) -> new OrderReservationRow(
                        rs.getObject("page_order_id", UUID.class),
                        reservation(rs, ReservationAuthority.WORKPLACE)), arguments));
        rows.addAll(jdbc.query("""
                SELECT order_row.service_order_id page_order_id,
                       event.event_id reservation_id, event.version,
                       event.organizer_user_id owner_user_id, event.starts_at, event.ends_at,
                       COALESCE(workplace_site.site_id::text, resource.site_name) site_reference,
                       resource.resource_id::text resource_reference,
                       resource.resource_type,
                       (event.status IN ('CONFIRMED', 'TENTATIVE')
                        AND booking.booking_status IN ('PENDING', 'CONFIRMED')) active
                  FROM wp_service_orders order_row
                  JOIN cal_events event
                    ON event.tenant_id = order_row.tenant_id
                   AND event.event_id = order_row.reservation_id
                   AND event.organizer_user_id = order_row.requester_user_id
                  JOIN cal_resource_bookings booking
                    ON booking.tenant_id = event.tenant_id
                   AND booking.event_id = event.event_id
                  JOIN cal_resources resource
                    ON resource.tenant_id = booking.tenant_id
                   AND resource.resource_id = booking.resource_id
                  LEFT JOIN wp_resources workplace_resource
                    ON workplace_resource.tenant_id = resource.tenant_id
                   AND workplace_resource.calendar_resource_id = resource.resource_id
                  LEFT JOIN wp_floors workplace_floor
                    ON workplace_floor.tenant_id = workplace_resource.tenant_id
                   AND workplace_floor.floor_id = workplace_resource.floor_id
                  LEFT JOIN wp_sites workplace_site
                    ON workplace_site.tenant_id = workplace_floor.tenant_id
                   AND workplace_site.site_id = workplace_floor.site_id
                 WHERE order_row.tenant_id = ?
                   AND order_row.reservation_authority = 'CALENDAR'
                   AND order_row.service_order_id IN (""" + in + ")"
                + " AND 1 = (SELECT COUNT(*) FROM cal_resource_bookings governed_booking"
                + " WHERE governed_booking.tenant_id = event.tenant_id"
                + " AND governed_booking.event_id = event.event_id"
                + " AND governed_booking.booking_status IN ('PENDING', 'CONFIRMED'))",
                (rs, row) -> new OrderReservationRow(
                        rs.getObject("page_order_id", UUID.class),
                        reservation(rs, ReservationAuthority.CALENDAR)), arguments));
        Map<UUID, ReservationSnapshot> result = new LinkedHashMap<>();
        rows.forEach(value -> result.put(value.orderId(), value.reservation()));
        return Map.copyOf(result);
    }

}
