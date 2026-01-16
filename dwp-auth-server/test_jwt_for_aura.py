#!/usr/bin/env python3
"""
DWP Backend에서 Aura-Platform용 JWT 토큰 생성 스크립트

사용법:
    python test_jwt_for_aura.py

환경 변수:
    JWT_SECRET: 공유 시크릿 키 (기본값: 개발용 키)
"""

from datetime import datetime, timedelta, timezone
from jose import jwt
import os
from dotenv import load_dotenv

# .env 파일 로드
load_dotenv()

# 환경 변수에서 시크릿 키 로드
SECRET_KEY = os.getenv(
    "JWT_SECRET", 
    "your_shared_secret_key_must_be_at_least_256_bits_long_for_HS256"
)
ALGORITHM = "HS256"

def generate_jwt_token(
    user_id: str = "backend_user_001",
    tenant_id: str = "tenant1",
    email: str = "user@dwp.com",
    role: str = "user",
    expires_hours: int = 1
) -> str:
    """
    JWT 토큰 생성
    
    Args:
        user_id: 사용자 ID
        tenant_id: 테넌트 ID
        email: 사용자 이메일
        role: 사용자 역할
        expires_hours: 만료 시간 (시간)
    
    Returns:
        JWT 토큰 문자열
    """
    # 현재 시간 (UTC)
    now = datetime.now(timezone.utc)
    expiration = now + timedelta(hours=expires_hours)
    
    # JWT payload 생성
    # ⚠️ 중요: exp와 iat는 Unix timestamp (초 단위 정수)로 변환해야 합니다!
    payload = {
        "sub": user_id,
        "tenant_id": tenant_id,
        "email": email,
        "role": role,
        "exp": int(expiration.timestamp()),  # ✅ Unix timestamp로 변환
        "iat": int(now.timestamp()),  # ✅ Unix timestamp로 변환
    }
    
    # 토큰 생성
    token = jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)
    return token

def verify_jwt_token(token: str) -> dict:
    """
    JWT 토큰 검증
    
    Args:
        token: JWT 토큰 문자열
    
    Returns:
        디코딩된 payload
    """
    try:
        decoded = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        return decoded
    except jwt.ExpiredSignatureError:
        raise ValueError("Token has expired")
    except jwt.JWTError as e:
        raise ValueError(f"Invalid token: {e}")

if __name__ == "__main__":
    import sys
    
    # 토큰만 출력하는 모드 (스크립트에서 사용)
    if len(sys.argv) > 1 and sys.argv[1] == "--token-only":
        token = generate_jwt_token()
        print(token)
        sys.exit(0)
    
    # 기본 모드: 상세 정보 출력
    print("=" * 60)
    print("DWP Backend - Aura-Platform JWT Token Generator")
    print("=" * 60)
    print()
    
    # 토큰 생성
    token = generate_jwt_token()
    print(f"✅ Generated JWT Token:")
    print(f"{token}")
    print()
    
    # 토큰 검증
    try:
        decoded = verify_jwt_token(token)
        print("✅ Token Verification Successful!")
        print()
        print("Decoded Payload:")
        for key, value in decoded.items():
            print(f"  {key}: {value}")
        print()
        
        # 만료 시간 확인
        exp_timestamp = decoded.get("exp")
        if exp_timestamp:
            exp_datetime = datetime.fromtimestamp(exp_timestamp, tz=timezone.utc)
            print(f"Token expires at: {exp_datetime}")
            print(f"Time until expiration: {exp_datetime - datetime.now(timezone.utc)}")
        
    except ValueError as e:
        print(f"❌ Token Verification Failed: {e}")
    
    print()
    print("=" * 60)
    print("Test with curl:")
    print(f"curl -X GET http://localhost:8080/api/aura/agents/health \\")
    print(f"  -H 'Authorization: Bearer {token}' \\")
    print(f"  -H 'X-Tenant-ID: tenant1' \\")
    print(f"  -H 'X-DWP-Source: FRONTEND'")
    print("=" * 60)
