package com.dwp.services.synapsex.dto.workbench;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Aura agent_stream metadata_json 규격 (format_metadata).
 * <p>
 * Aura: core/agent_stream/metadata.py — format_metadata(title, reasoning, evidence=None, status="SUCCESS")
 * 반환: {"title", "reasoning", "evidence", "status"}. evidence 없으면 {}, status는 SUCCESS|WARNING|ERROR만 허용, 그 외는 SUCCESS로 정규화.
 * 사용처: core/agent_stream/writer.py _audit_event_to_agent_event (Audit → Agent 이벤트 변환 시 payload를 format_metadata로 생성).
 * </p>
 * FE에서 타임라인 아이템 파싱용.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkbenchTimelineMetadataDto {

    private String title;
    private String reasoning;
    /** evidence: 없으면 Aura에서 {}. 구조화된 객체/리스트 가능 */
    private Object evidence;
    /** SUCCESS | WARNING | ERROR (Aura에서 그 외 값은 SUCCESS로 정규화) */
    private String status;
    /** format_metadata 외 추가 키(확장) — 원본 metadata_json 그대로 전달용 */
    private Map<String, Object> extra;
}
