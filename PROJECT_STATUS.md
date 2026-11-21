# JARVIS iOS — PROJECT STATUS

> 최종 갱신: 2026-11-21 · 현재 단계: **PHASE 1 완료 (Mac 빌드 확인 대기)**

## 단계별 진행

| 단계 | 내용 | 상태 |
|---|---|---|
| PHASE 1 | 기본 프로젝트 · SwiftUI 화면 · JARVIS 디자인 · 구조 설계 | ✅ 코드 완료 / ⏳ Xcode 빌드 확인 필요 |
| PHASE 2 | NVIDIA · Google · Claude 연결, 라우팅, 오류 처리 | ⏸ Google 검색 제품 선택 필요 |
| PHASE 3 | 마이크 권한, 한국어 STT, TTS, 음성 대화, 웨이크 워드 | 대기 |
| PHASE 4 | 캘린더, 미리 알림, 파일, Siri 단축어, App Intents | 대기 |
| PHASE 5 | Claude Tool Use, 작업 계획, 프로젝트·코드 작성 | 대기 |
| PHASE 6 | 문서·대본, 이미지 생성 연동, FFmpeg 영상 합성 | 대기 |
| PHASE 7 | 사용량 최적화, 보안 점검, 오류·실기기 테스트, 배포 준비 | 대기 |

## PHASE 1 결과

### 구현됨
- **JarvisCore (Swift 패키지)**: 9개 상태 + 검증된 상태 전이(`JarvisStateMachine`), 대화 기록(최대 개수 유지·스트리밍 갱신),
  작업 보드(진행/완료/취소), 엔진·연결 상태 모델, 기능 구현 상태(`JarvisFeature`), 백엔드 클라이언트(`/health`, `/v1/engines`, HTTPS 강제).
- **iOS 앱 (SwiftUI, iOS 17+)**: 메인 화면 9개 구성요소 — 중앙 AI 코어(Canvas 애니메이션), 음성 파형, 작동 상태,
  실시간 대화 기록, 현재 작업 상태, AI 엔진 연결 상태, API 사용량 영역, 마이크 상태, 빠른 기능 메뉴. 설정 화면(백엔드 주소, Keychain 토큰, DEBUG 디자인 점검).
  앱 아이콘. 접근성 레이블, 동작 줄이기(Reduce Motion) 대응.
- **백엔드 (FastAPI)**: `/health`(공개), `/v1/engines`(Bearer 인증, 엔진별 설정 여부 — 키 값 미노출), 환경변수 설정, fail-closed 인증.

### 검증 결과 (이 개발 환경: Linux, Xcode 없음)
| 항목 | 방법 | 결과 |
|---|---|---|
| JarvisCore | Swift 6.0 (Docker) `swift test`, 경고=오류 | ✅ 28개 테스트 통과 |
| MainViewModel | 임시 SwiftPM 하네스로 Linux 컴파일 + 테스트 (Keychain만 대체 구현) | ✅ 6개 테스트 통과 |
| SwiftUI 뷰 파일 | `swiftc -parse` (구문 검사) | ✅ 통과 — **타입 검사는 Mac의 Xcode에서만 가능** |
| Info.plist / project.yml / Asset JSON | 파서 검증 | ✅ |
| 백엔드 | pytest | ✅ 11개 테스트 통과 |
| 백엔드 실서버 | uvicorn 실행 후 curl | ✅ 200 / 인증 없음·잘못된 토큰 401 |

### 아직 확인되지 않음 (사용자 Mac 필요)
- [ ] `xcodegen generate` → Xcode 빌드 (SwiftUI 타입 검사)
- [ ] iOS 시뮬레이터 실행 및 화면 확인 (설정 > 디자인 점검으로 9개 상태 확인)
- [ ] Xcode에서 `JARVIS` 스킴 테스트 (JARVISTests + JarvisCoreTests)
- [ ] 실기기에서 로컬 백엔드 접속 (같은 Wi-Fi, `http://<Mac IP>:8000`)

## 사용자 결정·설정 필요 사항

1. **Google 검색 제품 선택 (PHASE 2 착수 전 필수)** — Custom Search JSON API는 신규 고객 접수가 중단되었고
   2027-01-01 종료 예정. 선택지: Gemini API "Grounding with Google Search"(웹 전체, 출처 제공, 표시 요건 있음) /
   Vertex AI Search(지정 사이트만). 자세한 비교는 `docs/ARCHITECTURE.md` §4.
2. **NVIDIA 채팅 모델 ID** — build.nvidia.com에서 사용할 모델 선택 → `NVIDIA_MODEL`.
3. **API Key 발급** — NVIDIA, Google, Anthropic. 백엔드 `.env`에만 입력.
4. **번들 ID / Apple 개발 팀** — `ios/project.yml`의 `TODO(사용자)` 항목.
5. **백엔드 배포 위치** (PHASE 2~7) — 개발 중에는 Mac 로컬, 이후 HTTPS 서버(예: Cloud Run, Fly.io 등) 선택.

## 다음 작업 (PHASE 2)
- [ ] 백엔드 NVIDIA 모듈: Chat Completions 스트리밍(SSE), 재시도·오류 처리, 대화 기록
- [ ] 백엔드 Google 모듈: 선택한 제품 연동, 결과(제목·URL·설명·출처) 표준화, 캐시·중복 차단
- [ ] 백엔드 Claude 모듈: 공식 `anthropic` SDK, 검색 결과 분석·복잡한 명령
- [ ] JarvisCore 로컬 명령 분류기 + 라우터, 앱 스트리밍 표시
- [ ] 요청별 엔진·토큰 사용 기록 → API 사용량 영역 실제 값 표시

## 변경 기록
- 2026-11-21: PHASE 1 — 프로젝트 구조, JarvisCore, SwiftUI 메인/설정 화면, 백엔드 골격, 설계 문서.
