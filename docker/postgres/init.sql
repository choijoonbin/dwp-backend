-- DWP Backend MSA 프로젝트 데이터베이스 초기화 스크립트

-- Auth Server 데이터베이스
CREATE DATABASE dwp_auth;

-- Main Service 데이터베이스
CREATE DATABASE dwp_main;

-- Mail Service 데이터베이스
CREATE DATABASE dwp_mail;

-- Chat Service 데이터베이스
CREATE DATABASE dwp_chat;

-- Approval Service 데이터베이스
CREATE DATABASE dwp_approval;

-- Aura Service 데이터베이스
CREATE DATABASE dwp_aura;

-- 각 데이터베이스에 대한 권한 부여
GRANT ALL PRIVILEGES ON DATABASE dwp_auth TO dwp_user;
GRANT ALL PRIVILEGES ON DATABASE dwp_main TO dwp_user;
GRANT ALL PRIVILEGES ON DATABASE dwp_mail TO dwp_user;
GRANT ALL PRIVILEGES ON DATABASE dwp_chat TO dwp_user;
GRANT ALL PRIVILEGES ON DATABASE dwp_approval TO dwp_user;
GRANT ALL PRIVILEGES ON DATABASE dwp_aura TO dwp_user;

-- 연결 확인용 메시지
\echo 'DWP Backend databases initialized successfully!'
\echo 'Databases created: dwp_auth, dwp_main, dwp_mail, dwp_chat, dwp_approval, dwp_aura'
