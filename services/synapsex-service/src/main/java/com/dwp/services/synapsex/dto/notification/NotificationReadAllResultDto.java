package com.dwp.services.synapsex.dto.notification;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** PATCH /read-all 응답: 읽음 처리된 건수 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationReadAllResultDto {
    private int markedCount;
}
