package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Serializes only the same actor/command/key. Receipts contain no user content. */
@Repository
public class MeetingWorkspaceCommands {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MeetingWorkspaceCommands(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Attempt begin(String type, String suppliedKey, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Workspace commands require a transaction.");
        }
        var actor = MeetingRequestContext.get();
        String key = VideoMeetingCommandPolicy.commandKey(suppliedKey);
        String hash;
        try {
            hash = HexFormat.of().formatHex(digest(mapper.writeValueAsBytes(payload)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Workspace command cannot be serialized.");
        }
        byte[] lockBytes = digest((actor.tenantId() + ":" + actor.userId() + ":"
                + type + ":" + key).getBytes(StandardCharsets.UTF_8));
        ByteBuffer lock = ByteBuffer.wrap(lockBytes);
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", row -> { },
                lock.getInt(), lock.getInt());
        Receipt receipt = jdbc.query("""
                SELECT request_sha256, result_id, result_version
                  FROM vm_meeting_workspace_commands
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND command_type = ? AND idempotency_key = ?
                """, (row, index) -> new Receipt(row.getString("request_sha256"),
                        row.getObject("result_id", UUID.class), row.getLong("result_version")),
                actor.tenantId(), actor.userId(), type, key).stream().findFirst().orElse(null);
        if (receipt != null && !VideoMeetingCommandPolicy.requestHashesMatch(hash, receipt.hash())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key belongs to a different workspace command.");
        }
        return new Attempt(type, key, hash, receipt);
    }

    public void complete(Attempt attempt, UUID resultId, long version) {
        var actor = MeetingRequestContext.get();
        jdbc.update("""
                INSERT INTO vm_meeting_workspace_commands (
                    tenant_id, actor_user_id, command_type, idempotency_key,
                    request_sha256, result_id, result_version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, actor.tenantId(), actor.userId(), attempt.type(), attempt.key(),
                attempt.hash(), resultId, version);
    }

    private byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public record Receipt(String hash, UUID resultId, long version) { }
    public record Attempt(String type, String key, String hash, Receipt replay) { }
}
