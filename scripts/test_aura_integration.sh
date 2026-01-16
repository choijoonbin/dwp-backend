#!/bin/bash
#
# Aura-Platform 통합 테스트 스크립트
#
# 이 스크립트는 다음을 수행합니다:
# 1. JWT 토큰 생성
# 2. Gateway를 통한 Aura-Platform 헬스체크 테스트
# 3. JWT 토큰을 포함한 인증 테스트
#

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 설정
GATEWAY_URL="http://localhost:8080"
AURA_HEALTH_ENDPOINT="${GATEWAY_URL}/api/aura/agents/health"
JWT_SCRIPT_PATH="dwp-auth-server/test_jwt_for_aura.py"

echo "=========================================="
echo "Aura-Platform 통합 테스트 시작"
echo "=========================================="
echo ""

# 1. JWT 토큰 생성
echo -e "${YELLOW}[1/3] JWT 토큰 생성 중...${NC}"
if [ ! -f "$JWT_SCRIPT_PATH" ]; then
    echo -e "${RED}❌ JWT 생성 스크립트를 찾을 수 없습니다: $JWT_SCRIPT_PATH${NC}"
    exit 1
fi

# Python 스크립트 실행 및 토큰 추출 (--token-only 모드 사용)
JWT_TOKEN=$(cd dwp-auth-server && python3 test_jwt_for_aura.py --token-only 2>/dev/null | head -1 | tr -d ' \n')

if [ -z "$JWT_TOKEN" ]; then
    echo -e "${RED}❌ JWT 토큰 생성 실패${NC}"
    exit 1
fi

echo -e "${GREEN}✅ JWT 토큰 생성 완료${NC}"
echo "Token (first 50 chars): ${JWT_TOKEN:0:50}..."
echo ""

# 2. Gateway를 통한 헬스체크 (인증 없이)
echo -e "${YELLOW}[2/3] Gateway를 통한 Aura-Platform 헬스체크 테스트 (인증 없이)...${NC}"
HEALTH_RESPONSE=$(curl -s -w "\n%{http_code}" "${AURA_HEALTH_ENDPOINT}" || echo -e "\n000")

HTTP_CODE=$(echo "$HEALTH_RESPONSE" | tail -1)
BODY=$(echo "$HEALTH_RESPONSE" | head -n -1)

if [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "401" ]; then
    echo -e "${GREEN}✅ Gateway 라우팅 정상 (HTTP $HTTP_CODE)${NC}"
    echo "Response: $BODY"
else
    echo -e "${RED}❌ Gateway 라우팅 실패 (HTTP $HTTP_CODE)${NC}"
    echo "Response: $BODY"
    echo ""
    echo "가능한 원인:"
    echo "  1. Gateway가 실행 중이지 않음 (포트 8080)"
    echo "  2. Aura-Platform이 실행 중이지 않음 (포트 8000)"
    echo "  3. 라우팅 설정 오류"
    exit 1
fi
echo ""

# 3. JWT 토큰을 포함한 인증 테스트
echo -e "${YELLOW}[3/3] JWT 토큰을 포함한 인증 테스트...${NC}"
AUTH_RESPONSE=$(curl -s -w "\n%{http_code}" \
    -H "Authorization: Bearer $JWT_TOKEN" \
    -H "X-Tenant-ID: tenant1" \
    -H "X-DWP-Source: FRONTEND" \
    "${AURA_HEALTH_ENDPOINT}" || echo -e "\n000")

AUTH_HTTP_CODE=$(echo "$AUTH_RESPONSE" | tail -1)
AUTH_BODY=$(echo "$AUTH_RESPONSE" | head -n -1)

if [ "$AUTH_HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✅ 인증 성공 (HTTP $AUTH_HTTP_CODE)${NC}"
    echo "Response: $AUTH_BODY"
    echo ""
    echo -e "${GREEN}=========================================="
    echo "✅ 모든 테스트 통과!"
    echo "==========================================${NC}"
elif [ "$AUTH_HTTP_CODE" = "401" ]; then
    echo -e "${RED}❌ 인증 실패 (HTTP 401 Unauthorized)${NC}"
    echo "Response: $AUTH_BODY"
    echo ""
    echo "가능한 원인:"
    echo "  1. JWT 토큰이 유효하지 않음"
    echo "  2. Aura-Platform에서 JWT 검증 실패"
    echo "  3. Gateway에서 Authorization 헤더가 전파되지 않음"
    echo ""
    echo "디버깅 정보:"
    echo "  - JWT Token: ${JWT_TOKEN:0:50}..."
    echo "  - Endpoint: $AURA_HEALTH_ENDPOINT"
    echo "  - Gateway URL: $GATEWAY_URL"
    exit 1
else
    echo -e "${RED}❌ 예상치 못한 응답 (HTTP $AUTH_HTTP_CODE)${NC}"
    echo "Response: $AUTH_BODY"
    exit 1
fi

echo ""
echo "테스트 완료!"
