package com.dwp.services.auth.workflowplanning;

import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Counts complete current TENANT memberships; IDs remain private vector material, never response population. */
@Repository
public class PlanningRoleRepository {
    private final JdbcTemplate jdbc; private final RoleMemberRepository members; private final PlanningJson json;
    private final PlanningRoleSources sources;
    public PlanningRoleRepository(JdbcTemplate jdbc,RoleMemberRepository members,PlanningJson json,PlanningRoleSources sources) {
        this.jdbc=jdbc; this.members=members; this.json=json; this.sources=sources;
    }
    public Snapshot current(long tenant,JsonNode codes) {
        var roles=new ArrayList<Role>(); var vector=new ArrayList<Object>(); var ids=new java.util.HashSet<Long>();
        if (!codes.isArray() || codes.isEmpty() || codes.size()>64) throw denied();
        String previous=""; Instant deadline=null;
        for (var value:codes) {
            if (!value.isTextual() || !value.textValue().matches("[A-Z][A-Z0-9_]{1,49}") || value.textValue().startsWith("PROVIDER_")
                    || value.textValue().compareTo(previous)<=0) throw denied(); String code=value.textValue(); previous=code;
            var found=jdbc.query("SELECT role_id,version FROM com_roles WHERE tenant_id=? AND code=? AND status='ACTIVE'",
                    (row,index)->new Role(code,row.getLong("role_id"),row.getLong("version"),0),tenant,code);
            if (found.size()!=1 || !ids.add(found.getFirst().roleId())) throw denied(); var role=found.getFirst();
            var before=sources.current(tenant,role.roleId());
            long count=members.countEffectiveActiveUsers(tenant,role.roleId()); if (count<0 || count>1000) throw unavailable();
            var complete=members.enumerateEffectiveActiveUsers(tenant,role.roleId());
            if (complete==null || complete.size()!=count || complete.size()>1000) throw unavailable();
            long prior=0; for(Long user:complete) { if(user==null || user<=prior) throw unavailable(); prior=user; }
            if(members.countEffectiveActiveUsers(tenant,role.roleId())!=count) throw changed();
            var currentRole=jdbc.query("SELECT role_id,version FROM com_roles WHERE tenant_id=? AND code=? AND status='ACTIVE'",
                    (row,index)->new Role(code,row.getLong("role_id"),row.getLong("version"),0),tenant,code);
            var after=sources.current(tenant,role.roleId());
            if(!found.equals(currentRole) || !before.equals(after)) throw changed();
            if(after.deadline()!=null && (deadline==null || after.deadline().isBefore(deadline))) deadline=after.deadline();
            var counted=new Role(code,role.roleId(),role.roleVersion(),(int)count); roles.add(counted);
            vector.add(Map.of("role",counted,"completeMemberIds",List.copyOf(complete),"sources",after.vector()));
        }
        return new Snapshot(roles,json.tree(vector),deadline);
    }
    public record Role(String roleCode,long roleId,long roleVersion,int activeMemberCount) { }
    public record Snapshot(List<Role> roles,JsonNode vector,Instant deadline) {
        public Snapshot { roles=List.copyOf(roles); vector=vector.deepCopy(); }
        @Override public JsonNode vector() { return vector.deepCopy(); }
    }
}
