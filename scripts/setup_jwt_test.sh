#!/bin/bash
#
# JWT 테스트 환경 설정 스크립트
#

set -e

echo "=========================================="
echo "JWT 테스트 환경 설정"
echo "=========================================="
echo ""

# Python 가상환경 확인
if [ ! -d "venv" ]; then
    echo "Python 가상환경 생성 중..."
    python3 -m venv venv
fi

# 가상환경 활성화
source venv/bin/activate

# 필요한 패키지 설치
echo "필요한 Python 패키지 설치 중..."
pip install --upgrade pip
pip install python-jose[cryptography] python-dotenv

echo ""
echo "✅ JWT 테스트 환경 설정 완료!"
echo ""
echo "다음 명령어로 JWT 토큰을 생성할 수 있습니다:"
echo "  cd dwp-auth-server"
echo "  python3 test_jwt_for_aura.py"
echo ""
