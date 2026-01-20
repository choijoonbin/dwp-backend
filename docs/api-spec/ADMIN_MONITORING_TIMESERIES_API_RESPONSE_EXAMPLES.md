# Timeseries API 실제 응답 예시

본 문서는 `/api/admin/monitoring/timeseries` API의 **실제 응답 구조**와 다양한 시나리오별 응답 예시를 제공합니다.

**최종 업데이트**: 2026-01-20  
**API 엔드포인트**: `GET /api/admin/monitoring/timeseries`

---

## 📋 응답 구조 개요

### 실제 응답 형식

**예상 형식** (프론트엔드 기대): `{ metric, interval, dataPoints: [{ timestamp, value }] }`  
**실제 응답 형식**: `{ interval, metric, labels: string[], values: number[] }`

**차이점**:
- `dataPoints` 배열 대신 `labels`와 `values` 두 개의 배열을 사용
- 같은 인덱스의 `labels[i]`와 `values[i]`가 쌍을 이룸
- 이 방식은 차트 라이브러리(Chart.js, ApexCharts 등)에서 직접 사용하기에 최적화됨

---

## 🔌 전체 응답 구조

### ApiResponse 래퍼

모든 응답은 `ApiResponse<T>` 형식으로 래핑됩니다:

```typescript
interface ApiResponse<T> {
  status: "SUCCESS" | "ERROR";
  message: string | null;
  errorCode: string | null;
  timestamp: string;  // ISO 8601 형식
  success: boolean;
  data: T;  // TimeseriesResponse
}
```

### TimeseriesResponse 구조

```typescript
interface TimeseriesResponse {
  interval: "DAY" | "HOUR";
  metric: "PV" | "UV" | "EVENT" | "API_TOTAL" | "API_ERROR";
  labels: string[];  // 시간 라벨 배열 (X축)
  values: number[];  // 값 배열 (Y축)
}
```

**중요**: `labels`와 `values` 배열의 길이는 항상 같으며, 같은 인덱스의 요소가 쌍을 이룹니다.

---

## 📤 실제 응답 예시

### 예시 1: 일별 API 에러 추이 (DAY, API_ERROR)

**요청**:
```bash
GET /api/admin/monitoring/timeseries?from=2026-01-19T11:19:00&to=2026-01-20T11:19:00&interval=DAY&metric=API_ERROR
Headers:
  Authorization: Bearer {JWT_TOKEN}
  X-Tenant-ID: 1
```

**실제 응답**:
```json
{
  "status": "SUCCESS",
  "message": null,
  "errorCode": null,
  "timestamp": "2026-01-20T11:19:00.123456",
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

**데이터 해석**:
- `labels[0]` = `"2026-01-19"` → `values[0]` = `15` (2026-01-19의 API 에러 수: 15건)
- `labels[1]` = `"2026-01-20"` → `values[1]` = `23` (2026-01-20의 API 에러 수: 23건)

---

### 예시 2: 시간별 페이지뷰 추이 (HOUR, PV)

**요청**:
```bash
GET /api/admin/monitoring/timeseries?from=2026-01-20T09:00:00&to=2026-01-20T11:00:00&interval=HOUR&metric=PV
Headers:
  Authorization: Bearer {JWT_TOKEN}
  X-Tenant-ID: 1
```

**실제 응답**:
```json
{
  "status": "SUCCESS",
  "message": null,
  "errorCode": null,
  "timestamp": "2026-01-20T11:19:00.123456",
  "success": true,
  "data": {
    "interval": "HOUR",
    "metric": "PV",
    "labels": [
      "2026-01-20 09:00",
      "2026-01-20 10:00",
      "2026-01-20 11:00"
    ],
    "values": [
      150,
      180,
      200
    ]
  }
}
```

**데이터 해석**:
- `labels[0]` = `"2026-01-20 09:00"` → `values[0]` = `150` (09:00~10:00 페이지뷰: 150건)
- `labels[1]` = `"2026-01-20 10:00"` → `values[1]` = `180` (10:00~11:00 페이지뷰: 180건)
- `labels[2]` = `"2026-01-20 11:00"` → `values[2]` = `200` (11:00~12:00 페이지뷰: 200건)

---

### 예시 3: 일별 이벤트 발생 추이 (DAY, EVENT) - 최근 7일

**요청**:
```bash
GET /api/admin/monitoring/timeseries?from=2026-01-13T00:00:00&to=2026-01-20T23:59:59&interval=DAY&metric=EVENT
Headers:
  Authorization: Bearer {JWT_TOKEN}
  X-Tenant-ID: 1
```

**실제 응답**:
```json
{
  "status": "SUCCESS",
  "message": null,
  "errorCode": null,
  "timestamp": "2026-01-20T11:19:00.123456",
  "success": true,
  "data": {
    "interval": "DAY",
    "metric": "EVENT",
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
    "values": [
      500,
      520,
      480,
      550,
      600,
      580,
      620,
      650
    ]
  }
}
```

---

### 예시 4: 일별 고유 방문자 수 (DAY, UV) - 최근 30일 (기본값)

**요청**:
```bash
GET /api/admin/monitoring/timeseries?interval=DAY&metric=UV
Headers:
  Authorization: Bearer {JWT_TOKEN}
  X-Tenant-ID: 1
```

**실제 응답** (일부만 표시):
```json
{
  "status": "SUCCESS",
  "message": null,
  "errorCode": null,
  "timestamp": "2026-01-20T11:19:00.123456",
  "success": true,
  "data": {
    "interval": "DAY",
    "metric": "UV",
    "labels": [
      "2025-12-21",
      "2025-12-22",
      "2025-12-23",
      "...",
      "2026-01-18",
      "2026-01-19",
      "2026-01-20"
    ],
    "values": [
      120,
      135,
      128,
      "...",
      150,
      145,
      160
    ]
  }
}
```

---

### 예시 5: 시간별 API 호출 총 수 (HOUR, API_TOTAL) - 최근 24시간

**요청**:
```bash
GET /api/admin/monitoring/timeseries?from=2026-01-19T11:00:00&to=2026-01-20T11:00:00&interval=HOUR&metric=API_TOTAL
Headers:
  Authorization: Bearer {JWT_TOKEN}
  X-Tenant-ID: 1
```

**실제 응답** (일부만 표시):
```json
{
  "status": "SUCCESS",
  "message": null,
  "errorCode": null,
  "timestamp": "2026-01-20T11:19:00.123456",
  "success": true,
  "data": {
    "interval": "HOUR",
    "metric": "API_TOTAL",
    "labels": [
      "2026-01-19 11:00",
      "2026-01-19 12:00",
      "2026-01-19 13:00",
      "...",
      "2026-01-20 09:00",
      "2026-01-20 10:00",
      "2026-01-20 11:00"
    ],
    "values": [
      1250,
      1380,
      1520,
      "...",
      1100,
      1280,
      1450
    ]
  }
}
```

---

### 예시 6: 빈 데이터 (데이터가 없는 기간)

**요청**:
```bash
GET /api/admin/monitoring/timeseries?from=2025-01-01T00:00:00&to=2025-01-07T23:59:59&interval=DAY&metric=PV
Headers:
  Authorization: Bearer {JWT_TOKEN}
  X-Tenant-ID: 1
```

**실제 응답**:
```json
{
  "status": "SUCCESS",
  "message": null,
  "errorCode": null,
  "timestamp": "2026-01-20T11:19:00.123456",
  "success": true,
  "data": {
    "interval": "DAY",
    "metric": "PV",
    "labels": [
      "2025-01-01",
      "2025-01-02",
      "2025-01-03",
      "2025-01-04",
      "2025-01-05",
      "2025-01-06",
      "2025-01-07"
    ],
    "values": [
      0,
      0,
      0,
      0,
      0,
      0,
      0
    ]
  }
}
```

**주의**: 데이터가 없는 경우에도 모든 날짜/시간에 대해 `labels`와 `values` 배열이 생성되며, 값은 `0`으로 채워집니다.

---

## 🔄 데이터 변환 가이드 (프론트엔드)

### 예상 형식으로 변환하기

프론트엔드에서 `dataPoints` 형식이 필요한 경우, 다음과 같이 변환할 수 있습니다:

```typescript
interface DataPoint {
  timestamp: string;
  value: number;
}

interface TransformedResponse {
  metric: string;
  interval: string;
  dataPoints: DataPoint[];
}

function transformTimeseriesResponse(
  response: ApiResponse<TimeseriesResponse>
): TransformedResponse {
  const { interval, metric, labels, values } = response.data;
  
  const dataPoints: DataPoint[] = labels.map((label, index) => ({
    timestamp: label,
    value: values[index]
  }));
  
  return {
    metric,
    interval,
    dataPoints
  };
}

// 사용 예시
this.monitoringService.getTimeseries({...}).subscribe(response => {
  const transformed = transformTimeseriesResponse(response);
  // transformed.dataPoints 사용
  // [
  //   { timestamp: "2026-01-19", value: 15 },
  //   { timestamp: "2026-01-20", value: 23 }
  // ]
});
```

---

## 📊 차트 라이브러리별 사용법

### Chart.js (권장)

```typescript
// 응답을 그대로 사용 가능
const chartData = {
  labels: response.data.labels,    // X축
  datasets: [{
    label: 'API 에러',
    data: response.data.values,     // Y축
    borderColor: 'rgb(255, 99, 132)'
  }]
};

new Chart(ctx, {
  type: 'line',
  data: chartData
});
```

### ApexCharts

```typescript
const chartOptions = {
  series: [{
    name: 'API 에러',
    data: response.data.values.map((value, index) => ({
      x: response.data.labels[index],
      y: value
    }))
  }],
  xaxis: {
    categories: response.data.labels
  }
};
```

### ECharts

```typescript
const chartOption = {
  xAxis: {
    type: 'category',
    data: response.data.labels
  },
  yAxis: {
    type: 'value'
  },
  series: [{
    data: response.data.values,
    type: 'line'
  }]
};
```

---

## ⚠️ 주의사항

1. **배열 길이 일치**: `labels`와 `values` 배열의 길이는 항상 같습니다.
2. **인덱스 매칭**: 같은 인덱스의 `labels[i]`와 `values[i]`가 쌍을 이룹니다.
3. **시간 순서**: `labels` 배열은 시간 순서대로 정렬되어 있습니다.
4. **빈 데이터**: 데이터가 없는 경우에도 모든 시간대에 대해 `0` 값이 반환됩니다.
5. **날짜 형식**:
   - `DAY` 간격: `YYYY-MM-DD` (예: `"2026-01-19"`)
   - `HOUR` 간격: `YYYY-MM-DD HH:mm` (예: `"2026-01-20 11:00"`)

---

## 🔍 에러 응답 예시

### 400 Bad Request (파라미터 오류)

```json
{
  "status": "ERROR",
  "message": "파라미터 형식이 올바르지 않습니다.",
  "errorCode": "E2001",
  "timestamp": "2026-01-20T11:19:00.123456",
  "success": false,
  "data": null
}
```

### 401 Unauthorized (인증 실패)

```json
{
  "status": "ERROR",
  "message": "인증이 필요합니다.",
  "errorCode": "E2000",
  "timestamp": "2026-01-20T11:19:00.123456",
  "success": false,
  "data": null
}
```

### 500 Internal Server Error (서버 오류)

```json
{
  "status": "ERROR",
  "message": "내부 서버 오류가 발생했습니다.",
  "errorCode": "E1000",
  "timestamp": "2026-01-20T11:19:00.123456",
  "success": false,
  "data": null
}
```

---

## 📚 요약

### 실제 응답 구조

```typescript
{
  success: true,
  data: {
    interval: "DAY" | "HOUR",
    metric: "PV" | "UV" | "EVENT" | "API_TOTAL" | "API_ERROR",
    labels: string[],  // 시간 라벨 (X축)
    values: number[]  // 값 (Y축)
  }
}
```

### 핵심 포인트

1. ✅ `labels`와 `values` 두 개의 배열 사용
2. ✅ 같은 인덱스의 요소가 쌍을 이룸 (`labels[i]` ↔ `values[i]`)
3. ✅ 차트 라이브러리에서 직접 사용 가능한 형식
4. ✅ 필요시 `dataPoints` 형식으로 변환 가능

---

**문서 작성일**: 2026-01-20  
**작성자**: DWP Backend Team
