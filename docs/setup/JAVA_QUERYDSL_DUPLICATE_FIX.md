# QueryDSL "The type Q* is already defined" 오류 해결

## 원인
QueryDSL Q-class가 두 곳에 생성되어 중복 정의 오류 발생:
- `bin/generated-sources/annotations/` (IDE/Eclipse 스타일)
- `build/generated/sources/annotationProcessor/` (Gradle)

## 해결 방법 (순서대로 실행)

### 1) bin 폴더 삭제
```bash
rm -rf services/synapsex-service/bin
```

### 2) Java Language Server 완전 초기화
1. **Command Palette** (Cmd+Shift+P) → `Java: Clean Java Language Server Workspace`
2. **"Restart and delete"** 선택 (프로젝트 캐시 삭제)
3. 창이 재시작되면 **잠시 대기** (Gradle import 완료될 때까지)

### 3) Gradle clean으로 bin 재삭제
```bash
./gradlew :services:synapsex-service:clean
```

### 4) 설정 확인
`.vscode/settings.json`에 `files.exclude`, `search.exclude`, `problems.exclude`로 `**/bin` 제외되어 있어야 함.

### 5) 오류가 계속되면
- Cursor/VS Code **완전 종료** 후 재실행
- `./gradlew clean build` 실행 후 IDE 재오픈
