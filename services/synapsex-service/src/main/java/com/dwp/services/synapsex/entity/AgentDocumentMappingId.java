package com.dwp.services.synapsex.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * agent_document_mapping 복합키
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class AgentDocumentMappingId implements Serializable {

    private Long agentId;
    private Long docId;
}
