package com.dwp.services.synapsex.entity;

import lombok.*;

import java.io.Serializable;
import java.util.Objects;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentToolMappingId implements Serializable {

    private Long agentId;
    private Long toolId;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AgentToolMappingId that = (AgentToolMappingId) o;
        return Objects.equals(agentId, that.agentId) && Objects.equals(toolId, that.toolId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(agentId, toolId);
    }
}
