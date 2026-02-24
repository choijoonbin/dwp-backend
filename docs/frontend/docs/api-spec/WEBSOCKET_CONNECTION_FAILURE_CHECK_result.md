# WebSocket 연결 실패 원인 확인 요청 — 백엔드 답변

**제목**: WebSocket 연결 실패 원인 확인 요청 (`/ws/notifications/{session}/{server}/websocket`)  
**상태**: 답변 완료  
**대상**: 프론트엔드 팀 전달용

---

## 1. WebSocket 업그레이드 지원 여부

### 1.1 지원 여부

**네.**  
`GET/WS /ws/notifications/{sessionId}/{serverId}/websocket` 에 대해 **HTTP 101 Switching Protocols** 로 WebSocket 업그레이드를 지원하도록 구성되어 있습니다.

- **경로**: `Path=/ws/**` && `Header=Upgrade, websocket` → **ws://** 백엔드(SynapseX)로 프록시
- **Gateway**: `WebsocketRoutingFilter`가 101 핸드셰이크 처리
- **Upgrade/Connection 헤더**: 제거·차단하지 않음. `RequiredHeaderFilter` 등에서 `/ws/` 는 헤더 변형 없이 통과시키며, 로그에 `WebSocket upgrade request forwarded path=... (Upgrade header passed to downstream, WebsocketRoutingFilter will handle 101)` 가 찍히면 정상 전달된 것입니다.

설정 위치: `dwp-gateway`  
- `application.yml` / `application-dev.yml` / `application-prod.yml`  
  - `synapsex-ws-notifications`: `Path=/ws/**`, `Header=Upgrade, websocket`, `uri: ws://...`  
  - `synapsex-ws-notifications-http`: `Path=/ws/**` (Upgrade 없음) → `uri: http://...` (SockJS /info, xhr_streaming 등)

### 1.2 현재 연결 실패(500)의 직접 원인

**Gateway가 Tomcat으로 기동될 때** WebSocket 업그레이드 요청이 **500 Internal Server Error** 를 반환합니다.

- **원인**: Spring Cloud Gateway의 WebSocket 업그레이드는 **Reactor Netty** 전제입니다.  
  Gateway가 **Tomcat**으로 기동되면, 업그레이드 시 `org.apache.catalina.connector.ResponseFacade` 를 `reactor.netty.http.server.HttpServerResponse` 로 캐스팅하려다 **ClassCastException** 이 발생합니다.
- **증상**:  
  - 로그에 `nio-8080-exec-*` (Tomcat 스레드) 와  
    `ClassCastException: ... ResponseFacade cannot be cast to ... HttpServerResponse`  
  - 클라이언트는 WebSocket 연결 실패 후, SockJS가 정리하면서 **code 1000 (Normal closure)** 로 보이는 동작이 이어질 수 있습니다.

**필수 조치 (백엔드 운영 측)**  
- Gateway는 **Netty만** 사용해 기동해야 합니다.  
- **권장 실행 방법**:  
  `./gradlew :dwp-gateway:clean :dwp-gateway:bootRun`  
- IDE에서 실행 시: Run Configuration의 classpath를 **dwp-gateway 모듈만** 사용하도록 설정 (다른 모듈/spring-boot-starter-web 포함 시 Tomcat이 올라와 동일 500 발생).
- Gateway 기동 로그에 **Tomcat 경고**가 있으면 (`StartupValidator` 에서 “Tomcat is on classpath …”) 위 실행 방식/classpath를 점검해야 합니다.

**정리**:  
- 101 업그레이드 **지원 여부**: 지원함.  
- **실제 실패 원인**: Gateway가 Tomcat으로 기동되어 WebSocket 업그레이드 시 500이 나는 것. Netty만 사용하도록 기동하면 해당 500은 제거됩니다.

---

## 2. 연결 직후 1000으로 닫는 로직 여부

### 2.1 백엔드에서 1000으로 닫는 코드 여부

**없습니다.**  
백엔드(SynapseX)에는 “연결 직후 code 1000 (Normal closure)로 명시적으로 닫는” 로직이 없습니다.

- **STOMP CONNECT**:  
  - `WebSocketConfig` 의 `ChannelInterceptor` 에서 **CONNECT 시 토큰 검증 실패로 거절하지 않음** 이라고 명시되어 있습니다.  
  - 로그: `STOMP CONNECT allowed sessionId=... principal=... hasAuthorization=... (Token 검증 실패 시 여기서 거절하지 않음 — 연결 유지, FE에서 tenantId 필터링)`  
  - 즉, Bearer 검증 실패 시에도 **백엔드가 여기서 연결을 끊거나 1000을 보내지 않습니다.** 연결은 유지하고, FE에서 tenantId 등으로 필터링하는 설계입니다.
- **DISCONNECT / 에러**:  
  - `afterSendCompletion` 에서 예외 시 `log.warn("STOMP afterSendCompletion error ... (Token 검증 실패로 DISCONNECT 가능성 ...)")` 로그만 남기며, **명시적으로 1000으로 닫는 코드는 없습니다.**

### 2.2 Bearer 검증 실패 시 동작

- **현재**: CONNECT 시 **검증 실패로 거절하지 않음** → 서버가 인증 실패로 **직접** WebSocket을 닫거나 1000을 보내지 않음.  
- **1000이 보이는 경우**:  
  - 위 1.2와 같이 **Gateway에서 500**이 나면, 브라우저 WebSocket이 실패한 뒤 **SockJS 클라이언트**가 정리 과정에서 **1000 (Normal closure)** 로 정리할 수 있습니다.  
  - 즉, “연결 직후 1000” 은 **서버가 1000을 보내서**가 아니라, **먼저 WebSocket 연결이 실패(500)** 하고, 그 다음 클라이언트 측에서 정리되면서 1000으로 보일 가능성이 큽니다.

**정리**:  
- 백엔드에는 “연결 직후 1000으로 닫는” 로직 없음.  
- Bearer 검증 실패 시에도 백엔드는 여기서 연결을 끊지 않음.  
- 1000은 주로 **Gateway 500 → WebSocket 실패 → SockJS 정리** 흐름에서 나오는 것으로 보는 것이 타당합니다.

---

## 3. CORS / Origin

### 3.1 Gateway

- **`/ws/**`**: Gateway에서는 **CORS 검사/추가를 하지 않습니다** (config=null 로 스킵).  
  → WebSocket 요청이 CORS로 막히지 않으며, 다운스트림(SynapseX)에서 처리합니다.

### 3.2 SynapseX (백엔드)

- **WebSocket 엔드포인트** (`/ws/notifications`):  
  - `setAllowedOriginPatterns(...)` 사용.  
  - 기본값:  
    `notification.websocket.allowed-origins`:  
    `${NOTIFICATION_WS_ORIGINS:${CORS_ALLOWED_ORIGINS:http://localhost:4200,http://localhost:3000,http://localhost:5173}}`  
  - 따라서 **`http://localhost:5173`** 은 기본 허용 목록에 포함되어 있으며, **WebSocket 연결을 Origin 때문에 막지 않습니다.**

**정리**:  
- Gateway는 `/ws/` 에 대해 CORS로 막지 않음.  
- SynapseX는 `http://localhost:5173` 등을 기본 허용.  
- 다른 Origin을 쓰는 경우에는 `NOTIFICATION_WS_ORIGINS` 또는 `CORS_ALLOWED_ORIGINS` 에 해당 Origin을 추가하면 됩니다.

---

## 4. 프론트 로그 해석 요약

- **“WebSocket connection to 'ws://localhost:8080/ws/notifications/035/qvqgy4ve/websocket' failed”**  
  → Gateway에서 해당 요청이 **500**으로 응답했을 가능성이 큼 (Tomcat 기동 시 ClassCastException).

- **이어서 “websocket closed { code: 1000, reason: 'Normal closure', wasClean: true }”**  
  → 서버가 1000을 보내서가 아니라, **위 실패 후 SockJS 클라이언트가 정리하면서** 1000으로 보일 수 있음.

- **두 가지가 반복**  
  → Gateway를 **Netty만 사용해** 기동하면 500이 사라지고, WebSocket 연결이 성공할 가능성이 높습니다.

---

## 5. 조치 요약 (백엔드/운영)

| 항목 | 조치 |
|------|------|
| WebSocket 101 | 이미 지원됨. Gateway가 **Netty로만** 기동되는지 확인. |
| 500 제거 | `./gradlew :dwp-gateway:clean :dwp-gateway:bootRun` 또는 IDE에서 **dwp-gateway 모듈만** classpath 사용. |
| 1000 | 서버에서 “연결 직후 1000으로 닫는” 로직 없음. 500 실패 후 클라이언트 정리로 보는 것이 타당. |
| CORS/Origin | Gateway는 `/ws/` CORS 스킵. SynapseX는 `http://localhost:5173` 등 기본 허용. |

---

## 6. 참고 문서

- `docs/integration/WEBSOCKET_NOTIFICATION_PIPELINE_ALIGNMENT.md` — Gateway 라우팅, 101, CONNECT 정책, 로그 의미 정리.
