package com.dwp.services.synapsex.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * FI Document / Open Item scope-filtered 조회.
 * TenantScopeResolver의 enabledBukrs/enabledWaers를 적용.
 * Scope가 비어있으면 빈 결과 반환 (fallback to all 금지).
 */
@Repository
@RequiredArgsConstructor
public class FiDocumentScopeRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * fi_doc_header 조회 (bukrs IN scope).
     * Scope 비어있으면 빈 리스트.
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> findDocHeadersByScope(Long tenantId, Collection<String> enabledBukrs, int limit) {
        if (enabledBukrs == null || enabledBukrs.isEmpty()) {
            return Collections.emptyList();
        }
        Query q = entityManager.createNativeQuery(
                "SELECT tenant_id, bukrs, belnr, gjahr, budat, waers, xblnr, status_code " +
                "FROM dwp_aura.fi_doc_header " +
                "WHERE tenant_id = :tid AND bukrs IN (:bukrs) " +
                "ORDER BY budat DESC LIMIT :lim");
        q.setParameter("tid", tenantId);
        q.setParameter("bukrs", enabledBukrs);
        q.setParameter("lim", limit);
        return q.getResultList();
    }

    /**
     * fi_open_item 조회 (bukrs IN scope AND currency IN scope).
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> findOpenItemsByScope(Long tenantId,
                                                Collection<String> enabledBukrs,
                                                Collection<String> enabledWaers,
                                                int limit) {
        if (enabledBukrs == null || enabledBukrs.isEmpty() || enabledWaers == null || enabledWaers.isEmpty()) {
            return Collections.emptyList();
        }
        Query q = entityManager.createNativeQuery(
                "SELECT tenant_id, bukrs, belnr, gjahr, buzei, item_type, open_amount, currency, due_date " +
                "FROM dwp_aura.fi_open_item " +
                "WHERE tenant_id = :tid AND bukrs IN (:bukrs) AND currency IN (:waers) " +
                "ORDER BY due_date ASC LIMIT :lim");
        q.setParameter("tid", tenantId);
        q.setParameter("bukrs", enabledBukrs);
        q.setParameter("waers", enabledWaers);
        q.setParameter("lim", limit);
        return q.getResultList();
    }

    /**
     * agent_case 조회 (tenant_id AND bukrs IN scope).
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> findCasesByScope(Long tenantId, Collection<String> enabledBukrs, int limit) {
        if (enabledBukrs == null || enabledBukrs.isEmpty()) {
            return Collections.emptyList();
        }
        Query q = entityManager.createNativeQuery(
                "SELECT c.case_id, c.tenant_id, c.bukrs, c.belnr, c.gjahr, c.buzei, c.case_type, c.severity, c.status, c.detected_at " +
                "FROM dwp_aura.agent_case c " +
                "WHERE c.tenant_id = :tid AND (c.bukrs IS NULL OR c.bukrs IN (:bukrs)) " +
                "ORDER BY c.detected_at DESC LIMIT :lim");
        q.setParameter("tid", tenantId);
        q.setParameter("bukrs", enabledBukrs);
        q.setParameter("lim", limit);
        return q.getResultList();
    }

    /**
     * agent_action 조회 (case 조인, case의 bukrs IN scope).
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> findActionsByScope(Long tenantId, Collection<String> enabledBukrs, int limit) {
        if (enabledBukrs == null || enabledBukrs.isEmpty()) {
            return Collections.emptyList();
        }
        Query q = entityManager.createNativeQuery(
                "SELECT a.action_id, a.tenant_id, a.case_id, a.action_type, a.status, a.planned_at, c.bukrs " +
                "FROM dwp_aura.agent_action a " +
                "JOIN dwp_aura.agent_case c ON a.case_id = c.case_id AND a.tenant_id = c.tenant_id " +
                "WHERE a.tenant_id = :tid AND (c.bukrs IS NULL OR c.bukrs IN (:bukrs)) " +
                "ORDER BY a.planned_at DESC LIMIT :lim");
        q.setParameter("tid", tenantId);
        q.setParameter("bukrs", enabledBukrs);
        q.setParameter("lim", limit);
        return q.getResultList();
    }
}
