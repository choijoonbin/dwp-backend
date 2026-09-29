package com.dwp.services.auth.service;

import com.dwp.platform.contracts.hris.identity.v1.AuthPersonBindingV1;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationPortsV2.PeopleLookup;
import com.dwp.platform.contracts.hris.identity.v2.NativeHrisCurrentRoleEvidenceV2;
import com.dwp.platform.contracts.hris.identity.v2.NativeHrisCurrentRoleEvidenceV2.*;
import com.dwp.platform.contracts.hris.identity.v2.NativeHrisTargetReadEvidencePortsV2.CurrentAuthRoleEvidenceProvider;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;

/** UNWIRED native READ pilot. No Gateway transport, permission evaluation or People DB.
 * The current native truth provider is mandatory. Sequential refetch is not an atomic
 * distributed revoke fence or a mutation PEP. V108 privileged activation stays disabled. */
public final class NativeHrisCurrentRoleEvidenceReaderV2 implements CurrentAuthRoleEvidenceProvider {
    public enum Code { UNAVAILABLE, INVALID, STALE, SOURCE_CHANGED, LIMIT_EXCEEDED }
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Code code;
        private Rejected(Code code) { super("Native Auth role evidence rejected: " + code, null, false, false); this.code=code; }
        public Code code() { return code; }
    }
    private static final String ACTOR = """
            SELECT tenant_id,user_id,public_id,person_public_id,identity_plane,status,version,access_revision
            FROM public.com_users WHERE tenant_id=? AND user_id=?
            """;
    private static final String ROLES = """
            WITH input AS (SELECT ?::bigint tenant_id, ?::bigint user_id, ?::timestamptz as_of), origins AS (
              SELECT m.tenant_id,m.role_id,'DIRECT' kind,m.role_member_id source_id,NULL::uuid source_uuid,
                     NULL::bigint source_version,m.updated_at source_stamp,NULL::bigint group_id,
                     NULL::bigint group_version,NULL::timestamptz membership_stamp,NULL::bigint membership_id,NULL::timestamptz group_stamp,
                     NULL::timestamptz valid_from,NULL::timestamptz valid_to
              FROM public.com_role_members m JOIN input i ON i.tenant_id=m.tenant_id AND i.user_id=m.user_id
              UNION ALL
              SELECT a.tenant_id,a.role_id,'GROUP',a.group_role_assignment_id,NULL::uuid,a.version,a.updated_at,
                     g.group_id,g.version,m.updated_at,m.group_member_id,g.updated_at,a.valid_from,a.valid_to
              FROM public.com_group_role_assignments a JOIN input i ON i.tenant_id=a.tenant_id
              JOIN public.com_group_members m ON m.tenant_id=a.tenant_id AND m.group_id=a.group_id AND m.user_id=i.user_id
              JOIN public.com_groups g ON g.tenant_id=m.tenant_id AND g.group_id=m.group_id
              WHERE g.status='ACTIVE' AND a.lifecycle_state='ACTIVE' AND a.assignment_type='ACTIVE'
                AND a.scope_type='TENANT' AND a.scope_ref IS NULL
                AND (a.valid_from IS NULL OR a.valid_from<=i.as_of) AND (a.valid_to IS NULL OR a.valid_to>i.as_of)
              UNION ALL
              SELECT p.tenant_id,p.role_id,'PRIVILEGED',NULL::bigint,p.active_privileged_grant_id,NULL::bigint,
                     p.updated_at,NULL::bigint,NULL::bigint,NULL::timestamptz,NULL::bigint,NULL::timestamptz,p.activated_at,p.expires_at
              FROM public.com_active_privileged_grants p JOIN input i ON i.tenant_id=p.tenant_id AND i.user_id=p.user_id
              WHERE p.scope_type='TENANT' AND p.scope_ref IS NULL AND p.revoked_at IS NULL
                AND p.activated_at<=i.as_of AND p.expires_at>i.as_of
            ) SELECT o.*,r.code,r.version role_version,r.updated_at role_stamp FROM origins o
              JOIN public.com_roles r ON r.tenant_id=o.tenant_id AND r.role_id=o.role_id AND r.status='ACTIVE'
              ORDER BY r.role_id,o.kind,o.source_id,o.source_uuid LIMIT 101
            """;
    private final DataSource source;
    private final NativeHrisAuthIdentityEvidenceTruthV2.Provider truth;
    private final Clock clock;
    private final Duration lease;
    public NativeHrisCurrentRoleEvidenceReaderV2(DataSource source,
            NativeHrisAuthIdentityEvidenceTruthV2.Provider truth, Clock clock, Duration lease) {
        this.source=source;this.truth=truth;this.clock=clock;this.lease=lease;
    }
    @Override public NativeHrisCurrentRoleEvidenceV2 loadCurrent(PeopleLookup lookup) {
        if(source==null||truth==null||clock==null||lease==null)throw reject(Code.UNAVAILABLE);
        try {
            if(lookup==null||lookup.authority()==null||lookup.authority().actor()==null
                    ||lease.isNegative()||lease.isZero()||lease.compareTo(Duration.ofSeconds(30))>0)throw reject(Code.INVALID);
            var authority=lookup.authority();var actor=authority.actor();
            if(authority.authRevision()==null||authority.authRevision().value()==null
                    ||!authority.authRevision().value().matches("auth-[0-9a-f]{64}")
                    ||actor.tenantId()<=0||actor.userId()<=0||actor.principalPublicId()==null
                    ||actor.principalPublicId().equals(new UUID(0,0))||actor.userRowVersion()<0||actor.accessRevision()<0
                    ||actor.identityPlane()!=AuthPersonBindingV1.IdentityPlane.TENANT||actor.status()!=AuthPersonBindingV1.Status.ACTIVE)
                throw reject(Code.INVALID);
            Instant initial=now(lookup.capturedNow(),actor,authority.expiresAt());
            var first=read(actor,initial);Instant afterFirst=now(initial,actor,authority.expiresAt());
            var firstTruth=truth.loadCurrent(actor.tenantId(),actor.userId());
            Instant afterTruth=now(afterFirst,actor,authority.expiresAt());validate(firstTruth,first,lookup);
            var second=read(actor,afterTruth);Instant afterSecond=now(afterTruth,actor,authority.expiresAt());
            var secondTruth=truth.loadCurrent(actor.tenantId(),actor.userId());
            Instant afterSecondTruth=now(afterSecond,actor,authority.expiresAt());validate(secondTruth,second,lookup);
            var finalRows=read(actor,afterSecondTruth);Instant issued=now(afterSecondTruth,actor,authority.expiresAt());
            if(!first.equals(second)||!second.equals(finalRows)||!firstTruth.equals(secondTruth))throw reject(Code.SOURCE_CHANGED);
            Instant expiry=min(initial.plus(lease),actor.expiresAt(),authority.expiresAt());
            for(var role:second.roles()) if(role.validTo()!=null)expiry=min(expiry,role.validTo());
            if(!expiry.isAfter(issued))throw reject(Code.STALE);
            return new NativeHrisCurrentRoleEvidenceV2(actor.tenantId(),actor.userId(),actor.principalPublicId(),
                    actor.userRowVersion(),actor.accessRevision(),secondTruth.authRevision(),true,second.roles(),issued,expiry);
        }catch(Rejected denied){throw denied;}catch(Exception failure){throw reject(Code.UNAVAILABLE);}
    }
    private Instant now(Instant previous,AuthPersonBindingV1 actor,Instant authorityExpiry) {
        var current=clock.instant();
        if(current==null||previous==null||current.isBefore(previous)||actor.capturedAt()==null
                ||actor.expiresAt()==null||authorityExpiry==null||current.isBefore(actor.capturedAt())
                ||!current.isBefore(actor.expiresAt())||!current.isBefore(authorityExpiry))throw reject(Code.STALE);
        return current;
    }
    private record Actor(long tenant,long user,UUID principal,UUID person,String plane,String status,long version,long access) { }
    /** Exact extra native row stamps participate in current refetch; public ABI stays unchanged. */
    private record OriginStamps(String nativeRoleCode,Instant roleStamp,Long membershipId,Instant groupStamp) { }
    private record NativeRows(Actor actor,List<RoleSource> roles,List<OriginStamps> stamps) { }
    private NativeRows read(AuthPersonBindingV1 expected,Instant asOf) throws SQLException {
        try(var connection=source.getConnection()) {
            connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
            try {
                Actor actor;
                try(var statement=connection.prepareStatement(ACTOR)) {
                    statement.setQueryTimeout(5);statement.setLong(1,expected.tenantId());statement.setLong(2,expected.userId());
                    try(var rows=statement.executeQuery()) {
                        if(!rows.next())throw reject(Code.INVALID);
                        actor=new Actor(rows.getLong("tenant_id"),rows.getLong("user_id"),rows.getObject("public_id",UUID.class),
                                rows.getObject("person_public_id",UUID.class),rows.getString("identity_plane"),rows.getString("status"),
                                rows.getLong("version"),rows.getLong("access_revision"));
                        if(rows.next())throw reject(Code.INVALID);
                    }
                }
                if(!actor.equals(new Actor(expected.tenantId(),expected.userId(),expected.principalPublicId(),expected.personPublicId(),
                        "TENANT","ACTIVE",expected.userRowVersion(),expected.accessRevision())))throw reject(Code.INVALID);
                var roles=new ArrayList<RoleSource>();var stamps=new ArrayList<OriginStamps>();
                try(var statement=connection.prepareStatement(ROLES)) {
                    statement.setQueryTimeout(5);statement.setLong(1,expected.tenantId());statement.setLong(2,expected.userId());
                    statement.setTimestamp(3,Timestamp.from(asOf));
                    try(var rows=statement.executeQuery()) { while(rows.next()) {
                        if(roles.size()==100)throw reject(Code.LIMIT_EXCEEDED);
                        String nativeCode=rows.getString("code");
                        stamps.add(new OriginStamps(nativeCode,instant(rows,"role_stamp"),rows.getObject("membership_id",Long.class),instant(rows,"group_stamp")));
                        roles.add(new RoleSource(rows.getLong("role_id"),nativeCode==null?null:nativeCode.trim().toUpperCase(Locale.ROOT),rows.getLong("role_version"),
                                SourceKind.valueOf(rows.getString("kind")),rows.getObject("source_id",Long.class),
                                rows.getObject("source_uuid",UUID.class),rows.getObject("source_version",Long.class),
                                instant(rows,"source_stamp"),rows.getObject("group_id",Long.class),rows.getObject("group_version",Long.class),
                                instant(rows,"membership_stamp"),instant(rows,"valid_from"),instant(rows,"valid_to")));
                    } }
                }
                connection.commit();return new NativeRows(actor,List.copyOf(roles),List.copyOf(stamps));
            }catch(SQLException|RuntimeException error){connection.rollback();throw error;}
        }
    }
    private static void validate(NativeHrisAuthIdentityEvidenceTruthV2.Truth truth,NativeRows nativeRows,PeopleLookup lookup) {
        var actor=lookup.authority().actor();
        if(truth==null||truth.roles()==null||truth.tenantId()!=actor.tenantId()||truth.userId()!=actor.userId()
                ||!Objects.equals(truth.authRevision(),lookup.authority().authRevision().value()))throw reject(Code.INVALID);
        var roleCodes=new HashSet<String>();var ids=new HashMap<String,Long>();var sources=new HashSet<String>();
        for(var role:nativeRows.roles()) {
            if(role.roleId()<=0||role.roleVersion()<0||role.roleCode()==null||!role.roleCode().matches("[A-Z][A-Z0-9_.-]{0,49}")
                    ||role.roleCode().startsWith("PROVIDER_")
                    ||role.sourceStamp()==null||role.sourceKind()==null)throw reject(Code.INVALID);
            switch(role.sourceKind()) {
                case DIRECT -> {
                    if(role.sourceId()==null||role.sourceId()<=0||role.sourcePublicId()!=null||role.sourceVersion()!=null
                            ||role.groupId()!=null||role.groupVersion()!=null||role.membershipStamp()!=null
                            ||role.validFrom()!=null||role.validTo()!=null)throw reject(Code.INVALID);
                }
                case GROUP -> {
                    if(role.sourceId()==null||role.sourceId()<=0||role.sourcePublicId()!=null||role.sourceVersion()==null
                            ||role.sourceVersion()<0||role.groupId()==null||role.groupId()<=0||role.groupVersion()==null
                            ||role.groupVersion()<0||role.membershipStamp()==null)throw reject(Code.INVALID);
                }
                case PRIVILEGED -> {
                    if(role.sourceId()!=null||role.sourceVersion()!=null||role.sourcePublicId()==null
                            ||role.sourcePublicId().equals(new UUID(0,0))||role.groupId()!=null||role.groupVersion()!=null
                            ||role.membershipStamp()!=null||role.validFrom()==null||role.validTo()==null
                            ||!role.validTo().isAfter(role.validFrom()))throw reject(Code.INVALID);
                }
            }
            var prior=ids.putIfAbsent(role.roleCode(),role.roleId());if(prior!=null&&prior!=role.roleId())throw reject(Code.INVALID);
            String key=role.sourceKind()+":"+(role.sourceId()==null?role.sourcePublicId():role.sourceId());
            if(!sources.add(key))throw reject(Code.INVALID);
            roleCodes.add(role.roleCode());
        }
        if(!roleCodes.equals(truth.roles()))throw reject(Code.INVALID);
    }
    private static Instant instant(ResultSet rows,String column)throws SQLException {var value=rows.getTimestamp(column);return value==null?null:value.toInstant();}
    private static Instant min(Instant... values) {return Arrays.stream(values).min(Comparator.naturalOrder()).orElseThrow();}
    private static Rejected reject(Code code){return new Rejected(code);}
}
