# JARVIS — PROJECT STATUS

> 최종 갱신: 2026-11-21 · 플랫폼: **Android** (iOS에서 전환) · 현재 단계: **PHASE 1 완료**

## 단계별 진행

| 단계 | 내용 | 상태 |
|---|---|---|
| PHASE 1 | 기본 프로젝트 · Compose 화면 · JARVIS 디자인 · 구조 설계 | ✅ 완료 (실기기 확인은 사용자) |
| PHASE 2 | NVIDIA · Google · Claude 연결, 라우팅, 오류 처리 | ⏸ Google 검색 제품 선택 필요 |
| PHASE 3 | 마이크 권한, 한국어 STT, TTS, 음성 대화, 웨이크 워드 | 대기 |
| PHASE 4 | 캘린더, 알람, 미리 알림, 파일, 앱 바로가기·어시스턴트 연동 | 대기 |
| PHASE 5 | Claude Tool Use, 작업 계획, 프로젝트·코드 작성 | 대기 |
| PHASE 6 | 문서·대본, 이미지 생성 연동, FFmpeg 영상 합성 | 대기 |
| PHASE 7 | 사용량 최적화, 보안 점검, 오류·실기기 테스트, 배포 준비 | 대기 |

## PHASE 1 결과 (Android)

### 구현됨
- **`:core` (순수 Kotlin)**: 9개 상태 + 검증된 전이(`JarvisStateMachine`), 대화 기록, 작업 보드, 엔진 연결 상태,
  기능 구현 상태(`JarvisFeature`), 백엔드 클라이언트(OkHttp, `/health`·`/v1/engines`, HTTPS 강제).
- **`:app` (Compose, Android 8.0+)**: 메인 화면 9개 구성요소 — 중앙 AI 코어(Canvas 애니메이션), 음성 파형, 작동 상태,
  실시간 대화 기록, 현재 작업, AI 엔진 연결 상태, API 사용량 영역, 마이크 상태, 빠른 기능 메뉴, 명령 입력.
  설정 화면(백엔드 주소, Keystore 암호화 토큰, 연결 테스트, 디버그 디자인 점검). 적응형 앱 아이콘.
  접근성 설명, "애니메이션 삭제" 설정 대응.
- **백엔드 (FastAPI)**: `/health`, `/v1/engines`(Bearer 인증, 키 미노출, fail closed). 변경 없음.

### 검증 결과 (이 개발 환경: Linux, Android SDK 37.2)
| 항목 | 방법 | 결과 |
|---|---|---|
| `:core` | JVM 단위 테스트 (실제 OkHttp ↔ MockWebServer 포함) | ✅ 28개 통과 |
| `:app` ViewModel | 단위 테스트 (코루틴 테스트 디스패처) | ✅ 9개 통과 |
| `:app` 화면 | Robolectric + Roborazzi 스냅샷 4종 렌더링 | ✅ 통과, `android/app/screenshots/` 이미지로 확인 |
| 디버그 APK | `assembleDebug` | ✅ |
| 릴리스 APK | `assembleRelease` (R8 축소, 1.7MB, 서명 전) | ✅ |
| Lint | `lintDebug` | ✅ 오류 0 · 의도된 경고 4 (디버그 HTTP, targetSdk, v26 아이콘 폴더, 구형 백업 속성) |
| 백엔드 | pytest 11개 + 실서버 curl | ✅ |
| 실기기·에뮬레이터 실행 | — | ⏳ 사용자 PC에서 확인 필요 (이 환경엔 가상화 미지원) |

## 사용자 결정·설정 필요 사항
1. **Google 검색 제품 선택 (PHASE 2 착수 전 필수)** — Custom Search JSON API는 2027-01-01 종료 예정.
   Gemini API "Grounding with Google Search"(웹 전체, 권장) / Vertex AI Search(지정 사이트만). `docs/ARCHITECTURE.md` §4.
2. **NVIDIA 채팅 모델 ID** — build.nvidia.com에서 선택 → `NVIDIA_MODEL`.
3. **API Key 발급** — NVIDIA, Google, Anthropic. 백엔드 `.env`에만 입력.
4. **패키지명** — `android/app/build.gradle.kts`의 `applicationId` (Play 배포 전 변경).
5. **백엔드 배포 위치** — 개발 중 Windows PC 로컬, 이후 HTTPS 서버.

## 다음 작업 (PHASE 2)
- [ ] 백엔드 NVIDIA 모듈: Chat Completions 스트리밍(SSE), 재시도·오류 처리, 대화 기록
- [ ] 백엔드 Google 모듈: 선택한 제품 연동, 결과 표준화, 캐시·중복 차단
- [ ] 백엔드 Claude 모듈: 공식 `anthropic` SDK, 검색 결과 분석·복잡한 명령
- [ ] `:core` 로컬 명령 분류기 + 라우터, 앱 스트리밍 표시
- [ ] 요청별 엔진·토큰 사용 기록 → API 사용량 영역 실제 값 표시

## 변경 기록
- 2026-11-21: Android 전환 — `android/` 프로젝트(core·app), 테스트·스냅샷, 문서 갱신. `ios/` 보관.
- 2026-11-21: PHASE 1 (iOS) — 구조, JarvisCore, SwiftUI 화면, 백엔드 골격, 설계 문서.
