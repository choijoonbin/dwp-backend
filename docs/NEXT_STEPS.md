# 다음 단계: Aura-Platform 개발 및 통합 로드맵

## 📋 현재 완료 상태

### ✅ DWP Backend 인프라 (완료)
- [x] Gateway 라우팅 설정 (`/api/aura/**` → `http://localhost:8000`)
- [x] SSE 타임아웃 설정 (300초)
- [x] AgentTask 엔티티 및 Repository
- [x] AgentTask 관리 REST API
- [x] FeignClient 헤더 자동 전파
- [x] Redis Pub/Sub 이벤트 시스템
- [x] Gateway 통합 테스트 코드

### ✅ 포트 구성
| 서비스 | 포트 | 상태 |
|--------|------|------|
| Gateway | 8080 | ✅ 실행 중 |
| **Aura-Platform** | **8000** | ⏳ 개발 필요 |
| Main Service | 8081 | ✅ 실행 중 |
| Mail Service | 8082 | ✅ 실행 중 |
| Chat Service | 8083 | ✅ 실행 중 |
| Approval Service | 8084 | ✅ 실행 중 |

---

## 🚀 Phase 1: Aura-Platform 기본 구조 구축 (1-2주)

### 1.1 프로젝트 초기화
```bash
# 프로젝트 디렉토리 생성
mkdir aura-platform
cd aura-platform

# Python 가상환경 설정
python -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate

# 기본 패키지 설치
pip install fastapi uvicorn pydantic python-dotenv
pip install httpx redis aioredis
pip install openai langchain chromadb  # AI/벡터 DB
```

### 1.2 기본 API 구조
```python
# main.py
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="Aura-Platform", version="1.0.0")

# CORS 설정
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:3039", "http://localhost:8080"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.get("/health")
async def health_check():
    return {
        "status": "OK",
        "service": "aura-platform",
        "version": "1.0.0"
    }

@app.get("/info")
async def get_info():
    return {
        "name": "Aura-Platform",
        "description": "AI Agent for DWP",
        "capabilities": ["chat", "analysis", "automation"]
    }

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
```

### 1.3 DWP Backend 연동 클라이언트
```python
# clients/dwp_client.py
import httpx
from typing import Dict, Any

class DWPClient:
    def __init__(self, base_url: str = "http://localhost:8080"):
        self.base_url = base_url
        self.headers = {
            "X-DWP-Source": "AURA",
            "Content-Type": "application/json"
        }
    
    async def create_agent_task(
        self, 
        task_type: str, 
        user_id: str, 
        tenant_id: str,
        description: str,
        input_data: Dict[str, Any]
    ) -> Dict[str, Any]:
        """AgentTask 생성"""
        async with httpx.AsyncClient() as client:
            response = await client.post(
                f"{self.base_url}/api/main/agent/tasks",
                headers=self.headers,
                json={
                    "taskType": task_type,
                    "userId": user_id,
                    "tenantId": tenant_id,
                    "description": description,
                    "inputData": str(input_data)
                }
            )
            return response.json()
    
    async def update_task_progress(
        self, 
        task_id: str, 
        progress: int, 
        description: str
    ):
        """작업 진척도 업데이트"""
        async with httpx.AsyncClient() as client:
            await client.patch(
                f"{self.base_url}/api/main/agent/tasks/{task_id}/progress",
                headers=self.headers,
                json={
                    "progress": progress,
                    "description": description
                }
            )
    
    async def complete_task(self, task_id: str, result_data: str):
        """작업 완료"""
        async with httpx.AsyncClient() as client:
            await client.post(
                f"{self.base_url}/api/main/agent/tasks/{task_id}/complete",
                headers=self.headers,
                json={"result": result_data}
            )
```

### 1.4 테스트
```bash
# 서버 실행
python main.py

# 헬스체크 테스트 (Gateway 통해)
curl http://localhost:8080/api/aura/health

# 직접 접근 테스트
curl http://localhost:8000/health
```

---

## 🤖 Phase 2: AI 기능 구현 (2-3주)

### 2.1 LLM 통합
```python
# services/llm_service.py
from openai import AsyncOpenAI
import os

class LLMService:
    def __init__(self):
        self.client = AsyncOpenAI(api_key=os.getenv("OPENAI_API_KEY"))
    
    async def chat(self, messages: list, stream: bool = False):
        """채팅 완료"""
        response = await self.client.chat.completions.create(
            model="gpt-4",
            messages=messages,
            stream=stream
        )
        return response
    
    async def analyze_data(self, data: dict) -> str:
        """데이터 분석"""
        prompt = f"다음 데이터를 분석해주세요: {data}"
        response = await self.chat([
            {"role": "system", "content": "당신은 데이터 분석 전문가입니다."},
            {"role": "user", "content": prompt}
        ])
        return response.choices[0].message.content
```

### 2.2 SSE 스트리밍 응답
```python
# routers/chat.py
from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from services.llm_service import LLMService

router = APIRouter(prefix="/chat", tags=["chat"])
llm_service = LLMService()

@router.post("/stream")
async def chat_stream(message: str):
    """스트리밍 채팅"""
    async def generate():
        response = await llm_service.chat(
            messages=[{"role": "user", "content": message}],
            stream=True
        )
        async for chunk in response:
            if chunk.choices[0].delta.content:
                yield f"data: {chunk.choices[0].delta.content}\n\n"
    
    return StreamingResponse(generate(), media_type="text/event-stream")
```

### 2.3 벡터 DB 연동
```python
# services/vector_service.py
import chromadb
from chromadb.config import Settings

class VectorService:
    def __init__(self):
        self.client = chromadb.Client(Settings(
            chroma_db_impl="duckdb+parquet",
            persist_directory="./chroma_db"
        ))
        self.collection = self.client.get_or_create_collection("dwp_documents")
    
    def add_document(self, doc_id: str, text: str, metadata: dict):
        """문서 추가"""
        self.collection.add(
            ids=[doc_id],
            documents=[text],
            metadatas=[metadata]
        )
    
    def search(self, query: str, n_results: int = 5):
        """유사 문서 검색"""
        results = self.collection.query(
            query_texts=[query],
            n_results=n_results
        )
        return results
```

---

## 📡 Phase 3: Redis 이벤트 구독 (1주)

### 3.1 이벤트 리스너
```python
# services/event_listener.py
import redis.asyncio as redis
import json
from services.vector_service import VectorService

class EventListener:
    def __init__(self):
        self.redis = redis.from_url("redis://localhost:6379")
        self.vector_service = VectorService()
    
    async def subscribe(self):
        """DWP 이벤트 구독"""
        pubsub = self.redis.pubsub()
        await pubsub.subscribe("dwp:events:all")
        
        async for message in pubsub.listen():
            if message["type"] == "message":
                await self.handle_event(json.loads(message["data"]))
    
    async def handle_event(self, event: dict):
        """이벤트 처리"""
        event_type = event.get("eventType")
        
        if event_type == "MAIL_SENT":
            # 메일 내용을 벡터 DB에 저장
            mail_id = event["data"]["mailId"]
            subject = event["data"]["subject"]
            self.vector_service.add_document(
                doc_id=f"mail_{mail_id}",
                text=subject,
                metadata={"type": "mail", "tenant_id": event["tenantId"]}
            )
        
        elif event_type == "APPROVAL_CREATED":
            # 결재 문서를 벡터 DB에 저장
            approval_id = event["data"]["approvalId"]
            title = event["data"]["title"]
            self.vector_service.add_document(
                doc_id=f"approval_{approval_id}",
                text=title,
                metadata={"type": "approval", "tenant_id": event["tenantId"]}
            )
```

### 3.2 백그라운드 태스크
```python
# main.py에 추가
from contextlib import asynccontextmanager
from services.event_listener import EventListener

event_listener = EventListener()

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 시작 시
    import asyncio
    task = asyncio.create_task(event_listener.subscribe())
    yield
    # 종료 시
    task.cancel()

app = FastAPI(lifespan=lifespan)
```

---

## 🔐 Phase 4: 인증 및 권한 (1주)

### 4.1 JWT 검증
```python
# middleware/auth.py
from fastapi import Request, HTTPException
from jose import jwt, JWTError

async def verify_jwt(request: Request):
    """JWT 토큰 검증"""
    auth_header = request.headers.get("Authorization")
    if not auth_header or not auth_header.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing token")
    
    token = auth_header.split(" ")[1]
    try:
        payload = jwt.decode(
            token, 
            os.getenv("JWT_SECRET"), 
            algorithms=["HS256"]
        )
        request.state.user_id = payload.get("user_id")
        request.state.tenant_id = payload.get("tenant_id")
    except JWTError:
        raise HTTPException(status_code=401, detail="Invalid token")
```

### 4.2 멀티테넌시
```python
# middleware/tenant.py
from fastapi import Request

async def extract_tenant(request: Request):
    """테넌트 ID 추출"""
    tenant_id = request.headers.get("X-Tenant-ID")
    if not tenant_id:
        raise HTTPException(status_code=400, detail="Missing tenant ID")
    request.state.tenant_id = tenant_id
```

---

## 📊 Phase 5: 모니터링 및 최적화 (1주)

### 5.1 메트릭 수집
```python
# monitoring/metrics.py
from prometheus_client import Counter, Histogram
import time

# 메트릭 정의
task_counter = Counter('aura_tasks_total', 'Total tasks processed')
task_duration = Histogram('aura_task_duration_seconds', 'Task duration')

async def track_task(task_func):
    """작업 추적 데코레이터"""
    start_time = time.time()
    try:
        result = await task_func()
        task_counter.inc()
        return result
    finally:
        duration = time.time() - start_time
        task_duration.observe(duration)
```

### 5.2 로깅
```python
# config/logging.py
import logging
from logging.handlers import RotatingFileHandler

def setup_logging():
    logger = logging.getLogger("aura")
    logger.setLevel(logging.INFO)
    
    # 파일 핸들러
    handler = RotatingFileHandler(
        "logs/aura.log",
        maxBytes=10485760,  # 10MB
        backupCount=5
    )
    
    formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    handler.setFormatter(formatter)
    logger.addHandler(handler)
    
    return logger
```

---

## 🐳 Phase 6: Docker 컨테이너화 (1주)

### 6.1 Dockerfile
```dockerfile
# Dockerfile
FROM python:3.11-slim

WORKDIR /app

# 의존성 설치
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 소스 코드 복사
COPY . .

# 포트 노출
EXPOSE 8000

# 실행
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

### 6.2 docker-compose.yml 업데이트
```yaml
# dwp-backend/docker-compose.yml에 추가
services:
  # 기존 서비스들...
  
  aura-platform:
    build: ../aura-platform
    container_name: aura-platform
    ports:
      - "8000:8000"
    environment:
      - OPENAI_API_KEY=${OPENAI_API_KEY}
      - REDIS_URL=redis://redis:6379
      - DWP_GATEWAY_URL=http://dwp-gateway:8080
    depends_on:
      - redis
    networks:
      - dwp-network

networks:
  dwp-network:
    driver: bridge
```

---

## 📝 체크리스트

### Phase 1: 기본 구조 ✅
- [ ] FastAPI 프로젝트 초기화
- [ ] 헬스체크 엔드포인트 구현
- [ ] DWP Backend 클라이언트 구현
- [ ] Gateway 통합 테스트 통과

### Phase 2: AI 기능 🔄
- [ ] OpenAI API 연동
- [ ] SSE 스트리밍 구현
- [ ] 벡터 DB 설정
- [ ] 문서 검색 기능

### Phase 3: 이벤트 처리 ⏳
- [ ] Redis 구독 구현
- [ ] 이벤트 핸들러 작성
- [ ] 자동 인덱싱 구현

### Phase 4: 보안 ⏳
- [ ] JWT 검증 미들웨어
- [ ] 멀티테넌시 필터
- [ ] RBAC 권한 체크

### Phase 5: 모니터링 ⏳
- [ ] Prometheus 메트릭
- [ ] 구조화된 로깅
- [ ] 에러 추적

### Phase 6: 배포 ⏳
- [ ] Dockerfile 작성
- [ ] Docker Compose 통합
- [ ] CI/CD 파이프라인

---

## 🎯 우선순위

### 🔴 높음 (즉시 시작)
1. **Phase 1**: 기본 구조 구축 및 Gateway 연동
2. **Phase 2**: 기본 AI 채팅 기능

### 🟡 중간 (2주 내)
3. **Phase 3**: Redis 이벤트 구독
4. **Phase 4**: JWT 인증

### 🟢 낮음 (1개월 내)
5. **Phase 5**: 모니터링
6. **Phase 6**: Docker 배포

---

## 📚 참고 자료

### 공식 문서
- [FastAPI 공식 문서](https://fastapi.tiangolo.com/)
- [OpenAI API 문서](https://platform.openai.com/docs/)
- [LangChain 문서](https://python.langchain.com/)
- [ChromaDB 문서](https://docs.trychroma.com/)

### DWP Backend 문서
- [AI 에이전트 인프라 가이드](./AI_AGENT_INFRASTRUCTURE.md)
- [통합 테스트 가이드](./INTEGRATION_TEST_GUIDE.md)
- [README.md](../README.md)

---

## 💬 다음 단계 시작하기

```bash
# 1. Aura-Platform 프로젝트 생성
mkdir -p ../aura-platform
cd ../aura-platform

# 2. 기본 파일 생성
cat > main.py << 'EOF'
from fastapi import FastAPI

app = FastAPI(title="Aura-Platform")

@app.get("/health")
async def health():
    return {"status": "OK", "service": "aura-platform"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
EOF

# 3. 의존성 파일 생성
cat > requirements.txt << 'EOF'
fastapi==0.109.0
uvicorn[standard]==0.27.0
pydantic==2.5.3
python-dotenv==1.0.0
httpx==0.26.0
redis==5.0.1
openai==1.10.0
langchain==0.1.0
chromadb==0.4.22
EOF

# 4. 가상환경 및 설치
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt

# 5. 서버 실행
python main.py

# 6. 테스트 (새 터미널)
curl http://localhost:8080/api/aura/health
```

**준비 완료! 🚀**
