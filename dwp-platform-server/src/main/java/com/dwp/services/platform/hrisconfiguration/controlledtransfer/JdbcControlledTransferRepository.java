package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySeals;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferDtos.*;
import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferRepository.*;

@Repository
final class JdbcControlledTransferRepository implements ControlledTransferRepository {

    static final String ACTION = "CONTROLLED_TRANSFER_CREATE";
    private final JdbcTemplate jdbc;
    private final ControlledTransferDomainEvents events;
    private final ObjectMapper json;

    JdbcControlledTransferRepository(
            JdbcTemplate jdbc,
            ControlledTransferDomainEvents events,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.events = events;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Receipt> findReplay(ReplayProbe probe) {
        AccessSeal access = probe.access();
        bind(access.tenantId(), access.subjectPrincipalPublicId());
        List<TransferReplaySeal> matches = jdbc.query("""
                SELECT request_digest, requested_by, receipt_public_id
                  FROM sys_controlled_file_transfers
                 WHERE tenant_id = ? AND owner_module = ? AND idempotency_key = ?
                """, (rs, row) -> new TransferReplaySeal(
                rs.getString("request_digest"),
                rs.getObject("requested_by", UUID.class),
                rs.getObject("receipt_public_id", UUID.class)),
                access.tenantId(), probe.ownerModule().name(), access.idempotencyKey());
        if (matches.isEmpty()) return Optional.empty();
        TransferReplaySeal match = matches.getFirst();
        if (!access.subjectPrincipalPublicId().equals(match.requestedBy())
                || !probe.requestDigest().equals(match.requestDigest())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "CONTROLLED_TRANSFER_IDEMPOTENCY_CONFLICT");
        }
        return Optional.of(storedReceipt(access, match.receiptPublicId()));
    }

    @Override
    @Transactional
    public Receipt create(PreparedTransfer transfer) {
        bind(transfer.access().tenantId(), transfer.access().subjectPrincipalPublicId());
        lockIdempotency(transfer);
        Optional<Receipt> existing = findReplay(new ReplayProbe(
                transfer.access(), transfer.ownerModule(), transfer.requestDigest()));
        if (existing.isPresent()) {
            return existing.get();
        }
        requireFreshInTransaction(transfer);

        UUID receiptId = UUID.randomUUID();
        Receipt receipt = new Receipt(
                receiptId, ACTION, ACTION,
                transfer.access().subjectPrincipalPublicId(),
                transfer.access().populationScopeDigest(),
                ControlledTransferService.publicRevision(
                        transfer.access().fieldPolicyRevision()),
                transfer.access().purposeCode(),
                ControlledTransferService.publicRevision(
                        transfer.access().authorizationRevision()),
                ReceiptStatus.ACCEPTED, transfer.access().correlationId(),
                transfer.requestDigest(), transfer.requestedAt(), 1L,
                "file-transfer:" + transfer.transferPublicId());
        String receiptDigest = digest(receipt);

        jdbc.update("""
                INSERT INTO sys_controlled_file_transfers (
                    controlled_transfer_id, public_id, tenant_id, transfer_kind,
                    owner_module, purpose_code, recipient_policy_ref,
                    population_kind, population_scope_digest,
                    authority_population_scope_digest,
                    access_authority_digest, access_authority_fresh_until,
                    egress_policy_revision, classification, object_ref, object_version,
                    storage_etag, upload_session_id, object_digest, media_type,
                    byte_size, metadata_authority, metadata_snapshot_digest,
                    metadata_authority_digest, metadata_authority_fresh_until,
                    scanner_key, scanner_version, scanned_at, scan_object_digest,
                    scan_byte_size, scan_authority_digest, scan_authority_fresh_until,
                    egress_authority_digest, egress_authority_fresh_until,
                    malware_state, state,
                    idempotency_key, request_digest, receipt_public_id,
                    receipt_digest, requested_by, requested_at, updated_at,
                    expires_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CLEAN', 'REQUESTED',
                        ?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, UUID.randomUUID(), transfer.transferPublicId(),
                transfer.access().tenantId(), transfer.transferKind().name(),
                transfer.ownerModule().name(), transfer.access().purposeCode(),
                transfer.recipientPolicyRef(), transfer.access().populationKind(),
                transfer.access().populationScopeDigest(),
                HrisExplorerAuthoritySeals.populationScopeDigest(
                        transfer.access().contextScopeKey()),
                transfer.access().authorityDigest(),
                transfer.access().authorityFreshUntil(), transfer.egressPolicyRevision(),
                transfer.classification().name(), transfer.objectRef(),
                transfer.objectVersion(), transfer.storageETag(),
                transfer.uploadSessionId(), transfer.objectDigest(), transfer.mediaType(),
                transfer.byteSize(), transfer.metadataAuthority(),
                transfer.metadataSnapshotDigest(), transfer.metadataAuthorityDigest(),
                transfer.metadataAuthorityFreshUntil(), transfer.scannerKey(),
                transfer.scannerVersion(), transfer.scannedAt(), transfer.objectDigest(),
                transfer.byteSize(), transfer.scanAuthorityDigest(),
                transfer.scanAuthorityFreshUntil(), transfer.egressAuthorityDigest(),
                transfer.egressAuthorityFreshUntil(),
                transfer.access().idempotencyKey(), transfer.requestDigest(), receiptId,
                receiptDigest, transfer.access().subjectPrincipalPublicId(),
                transfer.requestedAt(), transfer.requestedAt(), transfer.expiresAt());

        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    command_receipt_id, public_id, tenant_id, command_type,
                    originating_action, subject_principal_public_id,
                    population_scope_digest, authority_population_scope_digest,
                    field_policy_revision, authority_field_policy_revision,
                    purpose_code, authorization_revision,
                    authority_authorization_revision, origin_route_contract_key,
                    origin_context_key, origin_context_scope_key,
                    origin_decision_revision, idempotency_key, request_digest,
                    aggregate_public_id, aggregate_revision, status, result_ref,
                    correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?)
                """, receiptId, receiptId, transfer.access().tenantId(), ACTION, ACTION,
                transfer.access().subjectPrincipalPublicId(),
                transfer.access().populationScopeDigest(),
                HrisExplorerAuthoritySeals.populationScopeDigest(
                        transfer.access().contextScopeKey()),
                receipt.fieldPolicyRevision(),
                transfer.access().fieldPolicyRevision(), transfer.access().purposeCode(),
                receipt.authorizationRevision(), transfer.access().authorizationRevision(),
                transfer.access().routeContractKey(), transfer.access().contextKey(),
                transfer.access().contextScopeKey(), transfer.access().decisionRevision(),
                internalReceiptKey(transfer), transfer.requestDigest(),
                transfer.transferPublicId(), 1L, receipt.status().name(),
                receipt.resultRef(), transfer.access().correlationId(),
                transfer.requestedAt());
        events.requested(transfer, receiptDigest, transfer.requestedAt());
        return receipt;
    }

    @Override
    @Transactional(readOnly = true)
    public TransferPage catalog(
            AccessSeal access,
            OffsetDateTime asOf,
            String filter,
            Cursor cursor,
            int limit) {
        bind(access.tenantId(), access.subjectPrincipalPublicId());
        String normalized = filter == null ? null : filter.toUpperCase(java.util.Locale.ROOT);
        StringBuilder sql = new StringBuilder("""
                SELECT public_id,
                       owner_module || ':' || transfer_kind AS catalog_key,
                       transfer_kind, state, version, updated_at
                  FROM sys_controlled_file_transfers
                 WHERE tenant_id = ?
                   AND population_kind = ?
                   AND population_scope_digest = ?
                   AND updated_at <= ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(access.tenantId());
        arguments.add(access.populationKind());
        arguments.add(access.populationScopeDigest());
        arguments.add(asOf);
        if (normalized != null) {
            sql.append(" AND (owner_module LIKE ? OR transfer_kind LIKE ? OR state LIKE ?)");
            String pattern = '%' + normalized.replace("%", "\\%").replace("_", "\\_") + '%';
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (cursor != null) {
            sql.append(" AND (updated_at, public_id) < (?, ?)");
            arguments.add(cursor.updatedAt());
            arguments.add(cursor.publicId());
        }
        sql.append(" ORDER BY updated_at DESC, public_id DESC LIMIT ?");
        arguments.add(limit + 1);
        List<CatalogItem> rows = jdbc.query(sql.toString(), (rs, row) -> new CatalogItem(
                rs.getObject("public_id", UUID.class), rs.getString("catalog_key"),
                rs.getString("transfer_kind"), rs.getString("state"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class)),
                arguments.toArray());
        boolean more = rows.size() > limit;
        List<CatalogItem> items = more ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        CatalogItem last = more ? items.getLast() : null;
        return new TransferPage(items, last == null ? null
                : new Cursor(last.updatedAt(), last.publicId()));
    }

    private Receipt storedReceipt(AccessSeal access, UUID receiptPublicId) {
        try {
            Receipt receipt = jdbc.queryForObject("""
                    SELECT request_digest, public_id, command_type, originating_action,
                           subject_principal_public_id, population_scope_digest,
                           field_policy_revision, purpose_code, authorization_revision,
                           status, correlation_id, created_at, aggregate_revision, result_ref
                      FROM sys_hris_command_receipts
                     WHERE tenant_id = ? AND subject_principal_public_id = ?
                       AND public_id = ?
                    """, (rs, row) -> receipt(rs),
                    access.tenantId(), access.subjectPrincipalPublicId(),
                    receiptPublicId);
            if (receipt == null) throw new EmptyResultDataAccessException(1);
            return receipt;
        } catch (EmptyResultDataAccessException ignored) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "CONTROLLED_TRANSFER_RECEIPT_INTEGRITY_UNAVAILABLE");
        }
    }

    private void requireFreshInTransaction(PreparedTransfer transfer) {
        Boolean valid = jdbc.queryForObject("""
                SELECT CURRENT_TIMESTAMP < CAST(? AS TIMESTAMPTZ)
                   AND CURRENT_TIMESTAMP < CAST(? AS TIMESTAMPTZ)
                   AND CURRENT_TIMESTAMP < CAST(? AS TIMESTAMPTZ)
                   AND CURRENT_TIMESTAMP < CAST(? AS TIMESTAMPTZ)
                   AND (CAST(? AS TIMESTAMPTZ) IS NULL
                        OR CURRENT_TIMESTAMP < CAST(? AS TIMESTAMPTZ))
                """, Boolean.class,
                transfer.expiresAt(), transfer.access().authorityFreshUntil(),
                transfer.metadataAuthorityFreshUntil(),
                transfer.scanAuthorityFreshUntil(),
                transfer.egressAuthorityFreshUntil(),
                transfer.egressAuthorityFreshUntil());
        if (!Boolean.TRUE.equals(valid)) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "CONTROLLED_TRANSFER_AUTHORITY_EXPIRED_IN_TRANSACTION");
        }
    }

    private String internalReceiptKey(PreparedTransfer transfer) {
        return internalReceiptKey(
                transfer.ownerModule(), transfer.access().idempotencyKey());
    }

    static String internalReceiptKey(OwnerModule ownerModule, String clientKey) {
        String digest = java.util.HexFormat.of().formatHex(sha256Bytes(
                clientKey.getBytes(StandardCharsets.UTF_8)));
        return "ct1:" + ownerModule.name() + ":" + digest;
    }

    private Receipt receipt(ResultSet rs) throws SQLException {
        Long aggregateVersion = rs.getObject("aggregate_revision") == null
                ? null : rs.getLong("aggregate_revision");
        return new Receipt(
                rs.getObject("public_id", UUID.class), rs.getString("command_type"),
                rs.getString("originating_action"),
                rs.getObject("subject_principal_public_id", UUID.class),
                rs.getString("population_scope_digest"),
                rs.getLong("field_policy_revision"), rs.getString("purpose_code"),
                rs.getLong("authorization_revision"),
                ReceiptStatus.valueOf(rs.getString("status")),
                rs.getObject("correlation_id", UUID.class),
                rs.getString("request_digest"),
                rs.getObject("created_at", OffsetDateTime.class), aggregateVersion,
                rs.getString("result_ref"));
    }

    private void bind(long tenantId, UUID subjectPrincipalPublicId) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                String.class, Long.toString(tenantId));
        jdbc.queryForObject("SELECT set_config('dwp.principal_public_id', ?, true)",
                String.class, subjectPrincipalPublicId.toString());
    }

    private void lockIdempotency(PreparedTransfer transfer) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", ignored -> null,
                advisoryKey(transfer));
    }

    private long advisoryKey(PreparedTransfer transfer) {
        return ByteBuffer.wrap(sha256Bytes(String.join("\u001f",
                Long.toString(transfer.access().tenantId()),
                transfer.ownerModule().name(), transfer.access().idempotencyKey())
                .getBytes(StandardCharsets.UTF_8))).getLong();
    }

    private String digest(Receipt receipt) {
        try {
            return java.util.HexFormat.of().formatHex(
                    sha256Bytes(json.writeValueAsBytes(receipt)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Receipt digest failed", exception);
        }
    }

    private static byte[] sha256Bytes(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record TransferReplaySeal(
            String requestDigest, UUID requestedBy, UUID receiptPublicId) {
    }
}
