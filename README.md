# JARVIS iOS

음성으로 대화하고, 검색하고, 계획하고, 실제 기능을 실행하는 개인용 AI 비서 (iOS · SwiftUI).

- 대화: NVIDIA API Catalog · 검색: Google · 추론/에이전트: Claude API
- API Key는 백엔드에만 저장: `iOS 앱 ↔ HTTPS 백엔드 ↔ AI 제공자`

설계: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) · 진행 상황: [`PROJECT_STATUS.md`](PROJECT_STATUS.md)

## 빠른 시작

### 1. 백엔드 (Mac 또는 서버)

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env        # 값 입력 (최소: JARVIS_APP_TOKEN)
pytest                      # 테스트
uvicorn app.main:app --reload --env-file .env --host 0.0.0.0 --port 8000
```

확인: `curl http://localhost:8000/health`

### 2. iOS 앱 (Mac + Xcode 16 이상)

```bash
brew install xcodegen
cd ios
xcodegen generate           # JARVIS.xcodeproj 생성
open JARVIS.xcodeproj
```

1. `project.yml`의 번들 ID를 바꾸거나 Xcode > Signing & Capabilities에서 팀 선택
2. iPhone 시뮬레이터에서 실행 (⌘R), 테스트 (⌘U)
3. 앱 설정(⚙︎)에서 백엔드 주소와 앱 토큰(`JARVIS_APP_TOKEN` 값) 입력
   - 시뮬레이터: `http://localhost:8000`
   - 실기기: 같은 Wi-Fi에서 `http://<Mac의 IP>:8000` (첫 접속 시 로컬 네트워크 권한 허용)

### 3. 핵심 로직만 테스트 (Xcode 없이)

```bash
cd ios/Packages/JarvisCore && swift test
```
