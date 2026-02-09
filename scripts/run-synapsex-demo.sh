#!/usr/bin/env bash
# Phase2 DEMO 모드로 synapsex-service 기동
# Aura 미호출, 트리거 시 샘플 result/proposal 즉시 생성

set -e
cd "$(dirname "$0")/.."

export SYNAPSE_DEMO_MODE=true
./gradlew :services:synapsex-service:bootRun --args='--spring.profiles.active=demo'
