package com.dwp.services.synapsex.service.analysis;

import com.dwp.services.synapsex.entity.ThoughtChainLog;
import com.dwp.services.synapsex.repository.ThoughtChainLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * AI 사고 과정(Thought Chain) 로그 저장 — 시연 후 근거 조회용.
 * 샌드박스 요청 시 sandbox=true로 호출하면 DB 저장 생략(임시 세션). 계약: AGENT_STUDIO_NAMING_AND_SANDBOX_CONTRACT.md
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThoughtChainLogService {

    /** @see com.dwp.core.constant.HeaderConstants#X_SANDBOX */
    public static final String HEADER_SANDBOX = "X-Sandbox";

    private final ThoughtChainLogRepository thoughtChainLogRepository;

    /**
     * @param sandbox true면 DB 저장 생략(테스트/샌드박스 세션)
     */
    @Transactional
    public void saveLog(UUID runId, Long tenantId, Long caseId, String eventType, String data, boolean sandbox) {
        if (sandbox) {
            log.trace("Thought chain log skipped (sandbox session): runId={}", runId);
            return;
        }
        ThoughtChainLog log = ThoughtChainLog.builder()
                .runId(runId)
                .tenantId(tenantId)
                .caseId(caseId)
                .eventType(eventType != null ? eventType : "thought")
                .data(data)
                .createdAt(Instant.now())
                .build();
        thoughtChainLogRepository.save(log);
    }
}
