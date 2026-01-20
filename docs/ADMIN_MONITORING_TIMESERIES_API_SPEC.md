# Admin Monitoring Timeseries API 명세서

본 문서는 Admin 모니터링 대시보드에서 **시계열 차트(Line Chart)**를 그리기 위한 API 명세를 정의합니다.

**최종 업데이트**: 2026-01-20  
**버전**: P1-2 (Timeseries 고도화)

---

## 📋 API 개요

### 기능 설명

`/api/admin/monitoring/timeseries` API는 **시간대별로 집계된 모니터링 데이터**를 반환합니다. 
이 데이터는 프론트엔드에서 **시계열 차트(Line Chart)**를 그리는 데 사용됩니다.

### 주요 용도

1. **페이지뷰(PV) 추이 차트**: 일별/시간별 페이지뷰 수 추이
2. **API 에러 추이 차트**: 일별/시간별 API 에러 발생 추이
3. **이벤트 발생 추이 차트**: 일별/시간별 사용자 이벤트 발생 추이
4. **API 호출량 추이 차트**: 일별/시간별 API 호출 수 추이
5. **방문자 수(UV) 추이 차트**: 일별/시간별 고유 방문자 수 추이

---

## 🔌 API 엔드포인트

### 시계열 데이터 조회

- **엔드포인트**: `GET /api/admin/monitoring/timeseries`
- **설명**: 지정된 기간과 간격으로 집계된 시계열 데이터를 조회합니다.
- **인증**: JWT 토큰 필요 (ADMIN 권한 필요)
- **헤더**:
  - `Authorization: Bearer {JWT_TOKEN}` (필수)
  - `X-Tenant-ID: {tenantId}` (필수)

### 쿼리 파라미터

| 파라미터 | 타입 | 필수 여부 | 기본값 | 설명 | 예시 |
|---------|------|----------|--------|------|------|
| `from` | string | 아니오 | 현재 시간 - 30일 | 시작 일시 (ISO 8601 또는 `YYYY-MM-DD HH:mm:ss`) | `2026-01-19T11:19:00` 또는 `2026-01-19 11:19:00` |
| `to` | string | 아니오 | 현재 시간 | 종료 일시 (ISO 8601 또는 `YYYY-MM-DD HH:mm:ss`) | `2026-01-20T11:19:00` 또는 `2026-01-20 11:19:00` |
| `interval` | string | 아니오 | `DAY` | 집계 간격 (`DAY` 또는 `HOUR`) | `DAY`, `HOUR` |
| `metric` | string | 아니오 | `PV` | 집계 지표 | `PV`, `UV`, `EVENT`, `API_TOTAL`, `API_ERROR` |

### 지원되는 지표 (metric)

| 지표 값 | 설명 | 데이터 소스 |
|---------|------|------------|
| `PV` | 페이지뷰 수 (Page View Count) | `sys_page_view_events` 또는 `sys_page_view_daily_stats` |
| `UV` | 고유 방문자 수 (Unique Visitor Count) | `sys_page_view_events` 또는 `sys_page_view_daily_stats` |
| `EVENT` | 이벤트 발생 수 (Event Count) | `sys_event_logs` |
| `API_TOTAL` | API 호출 총 수 (Total API Calls) | `sys_api_call_histories` |
| `API_ERROR` | API 에러 수 (API Error Count) | `sys_api_call_histories` (status >= 400) |

### 지원되는 간격 (interval)

| 간격 값 | 설명 | 라벨 형식 | 사용 시나리오 |
|---------|------|----------|-------------|
| `DAY` | 일별 집계 | `YYYY-MM-DD` (예: `2026-01-19`) | 장기 추이 분석 (7일, 30일, 90일) |
| `HOUR` | 시간별 집계 | `YYYY-MM-DD HH:mm` (예: `2026-01-19 11:00`) | 단기 추이 분석 (24시간, 7일) |

---

## 📤 응답 형식

### 성공 응답 (200 OK)

```json
{
  "status": "SUCCESS",
  "message": null,
  "errorCode": null,
  "timestamp": "2026-01-20T11:19:00.000",
  "success": true,
  "data": {
    "interval": "DAY",
    "metric": "API_ERROR",
    "labels": [
      "2026-01-19",
      "2026-01-20"
    ],
    "values": [
      15,
      23
    ]
  }
}
```

### 응답 필드 설명

| 필드 | 타입 | 설명 |
|------|------|------|
| `interval` | string | 집계 간격 (`DAY` 또는 `HOUR`) |
| `metric` | string | 집계 지표 (`PV`, `UV`, `EVENT`, `API_TOTAL`, `API_ERROR`) |
| `labels` | string[] | 시간 라벨 배열 (X축 라벨로 사용) |
| `values` | number[] | 값 배열 (Y축 값으로 사용) |

**중요**: `labels`와 `values` 배열의 길이는 항상 같으며, 같은 인덱스의 요소가 쌍을 이룹니다.
- `labels[0]` = `"2026-01-19"` → `values[0]` = `15` (2026-01-19의 API 에러 수: 15건)
- `labels[1]` = `"2026-01-20"` → `values[1]` = `23` (2026-01-20의 API 에러 수: 23건)

---

## 📝 요청 예시

### 1. 일별 API 에러 추이 조회 (최근 7일)

```bash
GET /api/admin/monitoring/timeseries?from=2026-01-13T00:00:00&to=2026-01-20T23:59:59&interval=DAY&metric=API_ERROR
Headers:
  Authorization: Bearer {JWT_TOKEN}
  X-Tenant-ID: 1
```

**응답 예시**:
```json
{
  "success": true,
  "data": {
    "interval": "DAY",
    "metric": "API_ERROR",
    "labels": [
      "2026-01-13",
      "2026-01-14",
      "2026-01-15",
      "2026-01-16",
      "2026-01-17",
      "2026-01-18",
      "2026-01-19",
      "2026-01-20"
    ],
    "values": [10, 15, 12, 18, 20, 15, 15, 23]
  }
}
```

### 2. 시간별 페이지뷰 추이 조회 (최근 24시간)

```bash
GET /api/admin/monitoring/timeseries?from=2026-01-19T11:00:00&to=2026-01-20T11:00:00&interval=HOUR&metric=PV
Headers:
  Authorization: Bearer {JWT_TOKEN}
  X-Tenant-ID: 1
```

**응답 예시**:
```json
{
  "success": true,
  "data": {
    "interval": "HOUR",
    "metric": "PV",
    "labels": [
      "2026-01-19 11:00",
      "2026-01-19 12:00",
      "2026-01-19 13:00",
      ...
      "2026-01-20 10:00",
      "2026-01-20 11:00"
    ],
    "values": [150, 180, 200, ..., 170, 190]
  }
}
```

### 3. 일별 이벤트 발생 추이 조회 (기본값: 최근 30일)

```bash
GET /api/admin/monitoring/timeseries?interval=DAY&metric=EVENT
Headers:
  Authorization: Bearer {JWT_TOKEN}
  X-Tenant-ID: 1
```

**응답 예시**:
```json
{
  "success": true,
  "data": {
    "interval": "DAY",
    "metric": "EVENT",
    "labels": [
      "2025-12-21",
      "2025-12-22",
      ...
      "2026-01-19",
      "2026-01-20"
    ],
    "values": [500, 520, ..., 600, 650]
  }
}
```

---

## 🎨 프론트엔드 활용 가이드

### 1. 차트 라이브러리 선택

**권장 라이브러리**:
- **Chart.js** (가장 인기, 가볍고 사용하기 쉬움)
- **ng2-charts** (Angular용 Chart.js 래퍼)
- **ApexCharts** (고급 기능, 애니메이션 지원)
- **ECharts** (Apache ECharts, 강력한 기능)

### 2. TypeScript 인터페이스 정의

```typescript
// API 응답 타입 정의
interface TimeseriesResponse {
  interval: 'DAY' | 'HOUR';
  metric: 'PV' | 'UV' | 'EVENT' | 'API_TOTAL' | 'API_ERROR';
  labels: string[];
  values: number[];
}

interface ApiResponse<T> {
  success: boolean;
  data: T;
  status: string;
  message: string | null;
  errorCode: string | null;
  timestamp: string;
}
```

### 3. API 호출 서비스 (Angular 예시)

```typescript
import { Injectable } from '@angular/core';
import { HttpClient, HttpParams, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

@Injectable({
  providedIn: 'root'
})
export class MonitoringService {
  private apiUrl = '/api/admin/monitoring';

  constructor(private http: HttpClient) {}

  /**
   * 시계열 데이터 조회
   */
  getTimeseries(params: {
    from?: Date | string;
    to?: Date | string;
    interval?: 'DAY' | 'HOUR';
    metric?: 'PV' | 'UV' | 'EVENT' | 'API_TOTAL' | 'API_ERROR';
  }): Observable<TimeseriesResponse> {
    const httpParams = new HttpParams()
      .set('interval', params.interval || 'DAY')
      .set('metric', params.metric || 'PV')
      .set('from', this.formatDateTime(params.from))
      .set('to', this.formatDateTime(params.to));

    const headers = new HttpHeaders({
      'Authorization': `Bearer ${this.getToken()}`,
      'X-Tenant-ID': this.getTenantId().toString()
    });

    return this.http.get<ApiResponse<TimeseriesResponse>>(
      `${this.apiUrl}/timeseries`,
      { params: httpParams, headers }
    ).pipe(
      map(response => response.data)
    );
  }

  /**
   * 날짜/시간 형식 변환 (ISO 8601)
   */
  private formatDateTime(date?: Date | string): string {
    if (!date) return '';
    const d = typeof date === 'string' ? new Date(date) : date;
    return d.toISOString().slice(0, 19); // '2026-01-19T11:19:00'
  }

  private getToken(): string {
    // JWT 토큰 가져오기
    return localStorage.getItem('jwt_token') || '';
  }

  private getTenantId(): number {
    // 테넌트 ID 가져오기
    return parseInt(localStorage.getItem('tenant_id') || '1', 10);
  }
}
```

### 4. 차트 컴포넌트 구현 (Angular + Chart.js 예시)

```typescript
import { Component, OnInit, OnDestroy } from '@angular/core';
import { Chart, ChartConfiguration, ChartData, ChartType } from 'chart.js';
import { MonitoringService } from './monitoring.service';

@Component({
  selector: 'app-monitoring-chart',
  template: `
    <div class="chart-container">
      <canvas id="timeseriesChart"></canvas>
    </div>
    <div class="controls">
      <select [(ngModel)]="selectedMetric" (change)="loadChart()">
        <option value="PV">페이지뷰</option>
        <option value="UV">방문자 수</option>
        <option value="EVENT">이벤트</option>
        <option value="API_TOTAL">API 호출</option>
        <option value="API_ERROR">API 에러</option>
      </select>
      <select [(ngModel)]="selectedInterval" (change)="loadChart()">
        <option value="DAY">일별</option>
        <option value="HOUR">시간별</option>
      </select>
      <button (click)="loadChart()">새로고침</button>
    </div>
  `
})
export class MonitoringChartComponent implements OnInit, OnDestroy {
  chart: Chart | null = null;
  selectedMetric: 'PV' | 'UV' | 'EVENT' | 'API_TOTAL' | 'API_ERROR' = 'API_ERROR';
  selectedInterval: 'DAY' | 'HOUR' = 'DAY';

  constructor(private monitoringService: MonitoringService) {}

  ngOnInit() {
    this.loadChart();
  }

  ngOnDestroy() {
    if (this.chart) {
      this.chart.destroy();
    }
  }

  loadChart() {
    // 기간 계산 (예: 최근 7일)
    const to = new Date();
    const from = new Date();
    from.setDate(from.getDate() - 7);

    this.monitoringService.getTimeseries({
      from,
      to,
      interval: this.selectedInterval,
      metric: this.selectedMetric
    }).subscribe({
      next: (data) => {
        this.updateChart(data);
      },
      error: (error) => {
        console.error('차트 데이터 로드 실패:', error);
      }
    });
  }

  updateChart(data: TimeseriesResponse) {
    const ctx = document.getElementById('timeseriesChart') as HTMLCanvasElement;
    
    if (this.chart) {
      this.chart.destroy();
    }

    const chartConfig: ChartConfiguration = {
      type: 'line',
      data: {
        labels: data.labels,
        datasets: [{
          label: this.getMetricLabel(data.metric),
          data: data.values,
          borderColor: this.getMetricColor(data.metric),
          backgroundColor: this.getMetricColor(data.metric, 0.1),
          tension: 0.4, // 부드러운 곡선
          fill: true
        }]
      },
      options: {
        responsive: true,
        plugins: {
          legend: {
            display: true,
            position: 'top'
          },
          title: {
            display: true,
            text: `${this.getMetricLabel(data.metric)} 추이 (${this.selectedInterval === 'DAY' ? '일별' : '시간별'})`
          }
        },
        scales: {
          y: {
            beginAtZero: true
          }
        }
      }
    };

    this.chart = new Chart(ctx, chartConfig);
  }

  private getMetricLabel(metric: string): string {
    const labels: Record<string, string> = {
      'PV': '페이지뷰',
      'UV': '방문자 수',
      'EVENT': '이벤트',
      'API_TOTAL': 'API 호출',
      'API_ERROR': 'API 에러'
    };
    return labels[metric] || metric;
  }

  private getMetricColor(metric: string, alpha: number = 1): string {
    const colors: Record<string, string> = {
      'PV': `rgba(54, 162, 235, ${alpha})`,      // 파란색
      'UV': `rgba(75, 192, 192, ${alpha})`,      // 청록색
      'EVENT': `rgba(255, 206, 86, ${alpha})`,    // 노란색
      'API_TOTAL': `rgba(153, 102, 255, ${alpha})`, // 보라색
      'API_ERROR': `rgba(255, 99, 132, ${alpha})`   // 빨간색
    };
    return colors[metric] || `rgba(0, 0, 0, ${alpha})`;
  }
}
```

### 5. 사용 예시 시나리오

#### 시나리오 1: API 에러 추이 모니터링 대시보드

```typescript
// 대시보드 컴포넌트에서
ngOnInit() {
  // 최근 7일간의 API 에러 추이 조회
  this.monitoringService.getTimeseries({
    from: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000),
    to: new Date(),
    interval: 'DAY',
    metric: 'API_ERROR'
  }).subscribe(data => {
    // 차트 업데이트
    this.updateErrorChart(data);
  });
}
```

#### 시나리오 2: 실시간 페이지뷰 모니터링 (시간별)

```typescript
// 실시간 모니터링 컴포넌트에서
startRealTimeMonitoring() {
  setInterval(() => {
    // 최근 24시간의 페이지뷰 추이 조회
    this.monitoringService.getTimeseries({
      from: new Date(Date.now() - 24 * 60 * 60 * 1000),
      to: new Date(),
      interval: 'HOUR',
      metric: 'PV'
    }).subscribe(data => {
      this.updatePageViewChart(data);
    });
  }, 60000); // 1분마다 갱신
}
```

#### 시나리오 3: 다중 지표 비교 차트

```typescript
// 여러 지표를 동시에 표시
loadMultiMetricChart() {
  const metrics: Array<'PV' | 'UV' | 'API_ERROR'> = ['PV', 'UV', 'API_ERROR'];
  const datasets = [];

  metrics.forEach(metric => {
    this.monitoringService.getTimeseries({
      from: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000),
      to: new Date(),
      interval: 'DAY',
      metric
    }).subscribe(data => {
      datasets.push({
        label: this.getMetricLabel(metric),
        data: data.values,
        borderColor: this.getMetricColor(metric)
      });
      
      // 모든 데이터가 로드되면 차트 업데이트
      if (datasets.length === metrics.length) {
        this.updateMultiMetricChart(datasets, data.labels);
      }
    });
  });
}
```

---

## ⚠️ 주의사항

1. **날짜 형식**: ISO 8601 형식(`YYYY-MM-DDTHH:mm:ss`)을 권장합니다. 공백 형식 사용 시 URL 인코딩 필요합니다.
2. **기본값**: `from`과 `to`를 생략하면 자동으로 **최근 30일** 데이터를 조회합니다.
3. **간격 선택**:
   - `DAY`: 장기 추이 분석에 적합 (7일, 30일, 90일)
   - `HOUR`: 단기 추이 분석에 적합 (24시간, 7일)
4. **데이터 정렬**: `labels`와 `values` 배열은 시간 순서대로 정렬되어 반환됩니다.
5. **빈 데이터**: 해당 기간에 데이터가 없는 경우, `values` 배열의 모든 값이 `0`으로 반환됩니다.

---

## 🔍 에러 처리

### 400 Bad Request (파라미터 오류)

```json
{
  "status": "ERROR",
  "message": "파라미터 형식이 올바르지 않습니다.",
  "errorCode": "E2001",
  "timestamp": "2026-01-20T11:19:00.000",
  "success": false
}
```

### 401 Unauthorized (인증 실패)

```json
{
  "status": "ERROR",
  "message": "인증이 필요합니다.",
  "errorCode": "E2000",
  "timestamp": "2026-01-20T11:19:00.000",
  "success": false
}
```

### 500 Internal Server Error (서버 오류)

```json
{
  "status": "ERROR",
  "message": "내부 서버 오류가 발생했습니다.",
  "errorCode": "E1000",
  "timestamp": "2026-01-20T11:19:00.000",
  "success": false
}
```

---

## 📊 차트 구현 팁

### 1. 반응형 디자인

```typescript
options: {
  responsive: true,
  maintainAspectRatio: false, // 컨테이너 크기에 맞춤
  plugins: {
    legend: {
      display: true,
      position: 'top'
    }
  }
}
```

### 2. 날짜 포맷팅 (X축 라벨)

```typescript
// DAY 간격: "2026-01-19" → "1/19"
// HOUR 간격: "2026-01-19 11:00" → "11:00"
const formatLabel = (label: string, interval: string) => {
  if (interval === 'DAY') {
    const date = new Date(label);
    return `${date.getMonth() + 1}/${date.getDate()}`;
  } else {
    return label.split(' ')[1]; // "11:00"
  }
};
```

### 3. 툴팁 커스터마이징

```typescript
options: {
  plugins: {
    tooltip: {
      callbacks: {
        label: (context) => {
          return `${context.dataset.label}: ${context.parsed.y.toLocaleString()}`;
        }
      }
    }
  }
}
```

---

## 📚 참고 자료

- [Chart.js 공식 문서](https://www.chartjs.org/docs/latest/)
- [ng2-charts (Angular + Chart.js)](https://valor-software.com/ng2-charts/)
- [ApexCharts 공식 문서](https://apexcharts.com/)
- [ECharts 공식 문서](https://echarts.apache.org/)

---

**문서 작성일**: 2026-01-20  
**작성자**: DWP Backend Team
