package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import static com.dwp.services.approval.signatures.ApprovalSignatureDtos.MAX_VERSION;
import com.dwp.services.approval.signatures.ApprovalSignatureCommandReceiptDtos.*;
import com.dwp.services.approval.signatures.ApprovalSignatureDtos.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Resolves only a matching original command/event journal; there is no mutation or full receipt recovery. */
final class ApprovalSignatureCommandReceiptRepository {
    private final NamedParameterJdbcTemplate jdbc;private final ApprovalSignatureCanonical json;private final ApprovalSignatureReceiptCurrentRepository current;
    ApprovalSignatureCommandReceiptRepository(NamedParameterJdbcTemplate jdbc,ApprovalSignatureCanonical json,ApprovalSignatureReceiptCurrentRepository current){this.jdbc=jdbc;this.json=json;this.current=current;}
    record Snapshot(CommandReceipt receipt,String resourceSetKey,Map<String,Object> source){Snapshot{source=Map.copyOf(source);}}
    Snapshot capture(ApprovalSignatureInstalledSource.Seal seal,Query query) {
        var p=new MapSqlParameterSource().addValue("tenant",seal.actor().tenantId()).addValue("actor",seal.actor().userId())
                .addValue("op",query.originalOperation().name()).addValue("key",query.idempotencyKey()).addValue("target",query.targetId()).addValue("sha",query.bodySha256());
        var rows=jdbc.query("""
            SELECT command.command_receipt_id,command.request_id,command.signature_request_id,c.resource_set_key,c.source_pin::text,
                   command.result->>'committedAt' AS committed_at,event.sequence,
                   command.result->'ceremony'->>'state' AS result_state,
                   (command.result->'ceremony'->>'version')::bigint AS result_version,
                   command.body_sha256::text=encode(sha256(convert_to(approval_typed_form_canonical_json(command.private_body),'UTF8')),'hex') AS body_valid,
                   command.result->>'outcome'='COMMITTED'
                   AND command.command_receipt_id::text=command.result->>'commandReceiptId'
                   AND command.request_id::text=command.result->'ceremony'->>'requestId'
                   AND command.signature_request_id::text=command.result->'ceremony'->>'signatureRequestId'
                   AND event.sequence=(command.result->'ceremony'->>'version')::bigint
                   AND event.source_digest::text=command.result->'ceremony'->>'sourceDigest'
                   AND event.occurred_at=(command.result->>'committedAt')::timestamptz
                   AND c.source_pin=command.result->'ceremony'->'source' AS journal_valid
              FROM apr_self_attestation_commands command
              JOIN apr_self_attestations c ON c.tenant_id=command.tenant_id AND c.signature_request_id=command.signature_request_id AND c.request_id=command.request_id
              JOIN apr_requests r ON r.tenant_id=c.tenant_id AND r.request_id=c.request_id AND r.deleted_at IS NULL
              JOIN apr_tenants t ON t.tenant_id=c.tenant_id AND t.lifecycle_state='ACTIVE'
              JOIN apr_self_attestation_events event ON event.tenant_id=c.tenant_id AND event.signature_request_id=c.signature_request_id
                   AND event.action=command.operation AND event.actor_user_id=command.actor_user_id AND event.sequence=(command.result->'ceremony'->>'version')::bigint
             WHERE command.tenant_id=:tenant AND command.actor_user_id=:actor AND command.operation=:op AND command.idempotency_key=:key
                   AND command.body_sha256=:sha AND (CASE WHEN command.operation='CREATE' THEN command.request_id ELSE command.signature_request_id END)=:target
                   AND c.owner_user_id=:actor AND c.signer_user_id=:actor AND c.signer_kind='SELF_ATTESTATION'
                   AND r.requester_user_id=:actor AND r.management_resource_set_key=c.resource_set_key
            """,p,(r,n)->{
                if(!r.getBoolean("body_valid") || !r.getBoolean("journal_valid"))throw unavailable();
                return new Stored(r.getObject("command_receipt_id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("signature_request_id",UUID.class),
                        r.getString("resource_set_key"),json.read(r.getString("source_pin"),SourcePin.class),Instant.parse(r.getString("committed_at")),r.getLong("sequence"),
                        State.valueOf(r.getString("result_state")),r.getLong("result_version"));
            });
        if(rows.size()!=1)throw hidden();var stored=rows.getFirst();
        if(stored.sequence()<0 || stored.sequence()>MAX_VERSION || stored.version()!=stored.sequence())throw unavailable();
        State expected=switch(query.originalOperation()){case CREATE->State.AWAITING_CONSENT;case CONSENT->State.CONSENTED;case SIGN->State.ATTESTED;case CANCEL->State.CANCELLED;};
        if(stored.state()!=expected)throw unavailable();
        var sourceCurrent=current.capture(seal.actor().tenantId(),seal.actor().userId(),stored.rs(),stored.request(),stored.ceremony(),stored.source());
        var result=new CommandReceipt(stored.receipt(),query.originalOperation(),stored.request(),stored.ceremony(),stored.committed(),stored.sequence(),stored.state(),stored.version(),sourceCurrent.matches());
        var source=new LinkedHashMap<String,Object>();source.put("requestId",stored.request().toString());source.put("ownerUserId",seal.actor().userId());source.put("resourceSetKey",stored.rs());
        source.put("receiptId",stored.receipt().toString());source.put("originalOperation",query.originalOperation().name());source.put("targetId",query.targetId().toString());
        source.put("idempotencyKey",query.idempotencyKey());source.put("bodySha256",query.bodySha256());source.put("eventSequence",stored.sequence());source.put("resultVersion",stored.version());
        source.put("resultState",stored.state().name());source.put("committedAt",stored.committed().toString());source.put("signatureRequestId",stored.ceremony().toString());
        source.put("sourceCurrent",sourceCurrent.matches());source.put("currentMetadataSha256",sourceCurrent.digest());return new Snapshot(result,stored.rs(),source);
    }
    private record Stored(UUID receipt,UUID request,UUID ceremony,String rs,SourcePin source,Instant committed,long sequence,State state,long version){ }
}
