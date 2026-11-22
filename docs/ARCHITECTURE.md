# JARVIS (Android) — 기술 설계서

> 최종 수정: 2026-11-22 (PHASE 3 — 음성, "자비스" 상시 호출)

## 0. 플랫폼 전환 기록
- 2026-11-21: 개발 PC가 Windows라서 iOS(Xcode 필요)에서 **Android (Kotlin + Jetpack Compose)** 로 전환.
- 백엔드(FastAPI)와 설계(엔진 분리·라우팅·보안·상태 모델)는 그대로 유지. iOS 코드는 `ios/`에 보관.

## 1. 전체 구조

```
┌──────────────────────── Android 앱 (Kotlin · Compose) ─────────────────────┐
│  UI 계층        MainScreen · SettingsScreen · HUD 컴포넌트 (상태 없는 화면)  │
│      │                                                                      │
│  컨트롤러       JarvisController (앱 전역 — 화면·음성 서비스가 공유)        │
│      │                                                                      │
│  :core 모듈     상태 머신 · 대화 기록 · 작업 보드 · 백엔드 클라이언트(OkHttp) │
│  (순수 Kotlin)   CommandClassifier · AssistService(SSE 스트림)              │
│      │                                                                      │
│  음성           SpeechRecognizer · TextToSpeech · Vosk 호출어 · 음성 서비스  │
│  기기(PHASE 4)  CalendarContract · AlarmClock · 파일(SAF) · 공유 · 바로가기 │
│      │                                                                      │
│  저장소         DataStore(백엔드 주소) · Keystore AES-GCM(앱 토큰)           │
└──────┬──────────────────────────────────────────────────────────────────────┘
       │ HTTPS (Bearer 앱 토큰)
┌──────▼──────────────── JARVIS 백엔드 (Python FastAPI) ─────────────────────┐
│  인증 · 라우팅 · 캐싱 · 중복 차단 · 사용량 기록 · 일일 한도 · 로그             │
│  ├─ NVIDIA 모듈   → integrate.api.nvidia.com/v1 (대화 + 에이전트 모델)      │
│  ├─ Google 모듈   → Gemini API Grounding with Google Search (§4)            │
│  └─ 작업 엔진     → 프로젝트 작업 공간 · 문서 · FFmpeg 영상 합성               │
│  비밀값: 환경변수 / 서버 비밀 관리 기능                                        │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 설계 원칙
1. **API Key는 백엔드에만.** 앱은 백엔드 주소와 앱 토큰만 안다. 토큰은 Android Keystore 키로 암호화 저장.
2. **UI와 무관한 로직은 `:core`로.** Android 의존성 없음 → JVM 단위 테스트로 빠르게 검증.
3. **정직한 표시.** 구현되지 않은 기능은 예정 단계를 표시하고 실행하지 않는다 (`JarvisFeature.isImplemented`).
   `Configured`(설정값 존재)와 `Connected`(실제 호출 성공)를 구분한다.
4. **상태 전이는 검증된다.** `JarvisStateMachine`이 허용되지 않은 전이를 막는다.
5. **화면은 상태 없는 Composable** (`MainScreen(ui, actions)`) → 스냅샷 테스트로 디자인 회귀 확인.

## 2. 요청 분배 (PHASE 2 구현 완료)

```
사용자 입력 (텍스트 · PHASE 3부터 음성)
   ▼
로컬 명령 분류기 CommandClassifier (:core, 규칙 기반, 기기 안, 비용 0)
   ├─ DEVICE   일정·알람·메모 + 동작어       → 로컬 명령 엔진 (PHASE 4, 지금은 안내만)
   ├─ CHAT     일반 대화 (기본값)            → NVIDIA  nvidia/nemotron-3.5-lightning-30b-a3b
   ├─ SEARCH   최신 정보·뉴스·날씨·가격       → Google  Gemini Grounding (gemini-4-hydrogen)
   ├─ RESEARCH 검색 + 분석·비교·요약          → Google  Gemini Grounding + 비교·분석 지시
   └─ AGENT    기획·코드·문서·대본·다단계      → NVIDIA  nvidia/nemotron-3-ultra-550b-a55b (추론 모델)
   ▼
앱 → POST /v1/assist {route, text, history(최근 20개)} → SSE 스트림
     route · notice · stage · delta · sources · done · error
```

**Claude API를 쓰지 않는 이유 (2026-11-21 결정):** 비용. 에이전트 작업은 NVIDIA API Catalog의 대형 추론 모델이,
검색 분석은 Gemini Grounding이 맡는다. Gemini 약관상 근거 결과를 다른 AI에 넘겨 분석하는 것도 금지되어 있으므로,
검색+분석은 Gemini가 한 번에 처리하고 **원문 그대로 + 출처 + 검색 제안 위젯**으로 표시한다.

**비용·안정성 장치 (백엔드)**

| 장치 | 동작 |
|---|---|
| 로컬 분류기 | 애매하면 빠른 NVIDIA 소형 모델로. 복잡한 작업만 대형 추론 모델 |
| 중복 요청 차단 | 처리 중인 동일 요청(경로·입력·맥락) → 409 |
| 응답 캐시 | 에이전트(agent) 응답만 10분 캐시. Google 결과는 약관상 캐시 안 함 |
| 대화 기록 제한 | 최근 20개, 16,000자 이내만 전송 (요약은 PHASE 7) |
| 사용량 기록 | 요청별 엔진·모델·경로·토큰·검색 수·지연 시간 (SQLite, **본문 미저장**) → `GET /v1/usage` |
| 일일 한도 | `DAILY_LIMIT_*` 초과 시 research→NVIDIA 에이전트 대체 + 안내, 대체 불가면 429 |
| 엔진 미설정 | 같은 대체 규칙, 불가하면 503과 누락된 환경변수 이름 안내 |
| 재시도 | NVIDIA·Gemini: 429/5xx/네트워크 오류를 첫 응답 전까지 지수 백오프로 최대 3회. |
| 취소 | 앱에서 "중단" → 스트림 연결 종료 → 백엔드 처리 중단, 사용량에 cancelled 기록 |

## 3. 상태 모델

| 상태 | 표시 | 활성 엔진 |
|---|---|---|
| `Idle` | 대기 중 | — |
| `Listening` | 음성 인식 중 | — |
| `Analyzing` | 사용자 명령 분석 중 | LOCAL |
| `NvidiaResponding` | NVIDIA 응답 중 | NVIDIA |
| `GoogleSearching` | Google 검색 중 | GOOGLE |
| `Reasoning` | 심층 추론 중 | NVIDIA (에이전트 모델) |
| `ExecutingTask` | 작업 실행 중 | LOCAL |
| `Completed` | 작업 완료 | — |
| `Error` | 오류 발생 | — |

허용 전이: `Idle→Listening|Analyzing`, `Listening→Analyzing`, `Analyzing→(각 엔진)|ExecutingTask|Completed`,
`GoogleSearching→Reasoning|NvidiaResponding|Completed`, `Reasoning→GoogleSearching|ExecutingTask|Completed`,
`ExecutingTask→Reasoning|Completed`, `Completed|Error→Listening|Analyzing`. 모든 상태 → `Error`, `Idle`(중단).

## 4. Google 검색 — 제품 현황 (2026-11 확인)

| 선택지 | 범위 | 현황 / 비고 |
|---|---|---|
| Custom Search JSON API | 웹 전체 / 이미지 | **신규 고객 접수 중단, 2027-01-01 종료 예정** — 신규 개발에 부적합 |
| **(선택됨) Gemini API — Grounding with Google Search** | 웹 전체 | 아래 행 참고. 2026-11-21 PHASE 2에서 구현 |
| Vertex AI Search | 지정한 사이트·데이터 저장소 | Google 공식 대체 제품. 공개 웹 전체 검색 용도 아님 |
| Gemini API — Grounding with Google Search | 웹 전체 | `groundingMetadata`(검색어, 출처 URI·제목) 제공. **Google Search Suggestions 표시 요건**, 쿼리당 과금(Gemini 3). 이미지 검색 결과 없음 |

Interactions API (`POST /v1beta/interactions`, `Api-Revision: 2026-05-20`, `store: false`)를 사용한다.
약관 준수: 결과 원문 그대로 표시 · 검색 제안 위젯(HTML) 함께 표시 · 링크는 외부 브라우저로 바로 열기(추적 없음) ·
캐시/서버 저장 없음 · 다른 AI에 전달하지 않음. 이미지 검색 결과는 제공되지 않는다.

## 5. Android 플랫폼 기능과 제약 (각 단계 구현 전 공식 문서로 재확인)

| 기능 | 방식 | 비고 |
|---|---|---|
| 웨이크 워드 "자비스" | ✅ `JarvisVoiceService`(microphone 포그라운드 서비스, 상시 알림) + Vosk 오프라인 한국어 모델로 기기 내 감지 → 감지 후 SpeechRecognizer로 명령 인식 | 앱 화면이 보일 때만 시작 가능, 재부팅 후 자동 시작 불가(Android 14+) |
| 기본 디지털 어시스턴트 | `VoiceInteractionService` 구현 → 사용자가 설정에서 기본 어시스턴트로 지정 | 전원/홈 버튼 길게 누르기로 호출 가능. PHASE 3~4에서 검토 |
| 음성 인식 | ✅ `SpeechRecognizer` (ko-KR/en-US), Android 12+ 기기 내 인식 우선 | `VoiceInput` 인터페이스로 추상화 |
| 음성 출력 | ✅ `TextToSpeech` (속도·음량, 중단) | 한국어 음성은 설치된 TTS 엔진에 따라 다름 |
| 일정 | `CalendarContract` (READ/WRITE_CALENDAR 권한) | |
| 알람 | `AlarmClock.ACTION_SET_ALARM` 인텐트 | iOS와 달리 시계 앱 알람 생성 가능 |
| 미리 알림 | 시스템 공용 API 없음 → 앱 내 알림 + 알림 예약 | 또는 Google Tasks API(OAuth) |
| 파일·문서 | 앱 전용 저장소 + SAF(문서 선택기) | |
| 사진 | Photo Picker | 전체 사진 권한 불필요 |
| 공유·URL | `ACTION_SEND`, `ACTION_VIEW` | |
| 다른 앱 조작 | 공개 인텐트만 사용 | 접근성 서비스로 다른 앱을 조작하지 않음 |

## 6. 보안 및 승인

- 앱 ↔ 백엔드: HTTPS + Bearer 앱 토큰(서버 상수 시간 비교, 미설정 시 fail closed).
- 앱: 릴리스 빌드는 HTTPS만 허용(`network_security_config`). 디버그 빌드만 로컬 개발 서버 HTTP 허용 + 코드에서 localhost/사설망으로 제한.
- 토큰: Keystore AES-256-GCM, 백업·기기 이전 제외(`allowBackup=false`, `data_extraction_rules`).
- 사용자 확인 필요 작업: 외부 전송, 메시지/이메일 발송, 파일·일정 삭제, 결제, 개인정보 포함 작업, 되돌리기 어려운 작업
  → PHASE 5에서 에이전트 도구마다 `requiresConfirmation` 속성을 두고 앱 승인 UI 표시.
- 모든 작업은 실행 기록(TaskBoard + 백엔드 로그), 취소 가능한 작업은 취소 제공.

## 7. 폴더 구조

```
Bbbj/
├── README.md · PROJECT_STATUS.md · docs/ARCHITECTURE.md
├── android/                         # ← 현재 개발 대상
│   ├── settings.gradle.kts · build.gradle.kts · gradle/libs.versions.toml · gradlew(.bat)
│   ├── core/                        # 순수 Kotlin 모듈
│   │   ├── src/main/kotlin/com/jarvis/core/{model,state,network}
│   │   └── src/test/kotlin/…        # 상태 머신·모델·백엔드 클라이언트 테스트
│   └── app/                         # Android 앱
│       ├── src/main/kotlin/com/jarvis/app/
│       │   ├── MainActivity.kt · JarvisApplication.kt (수동 DI)
│       │   ├── data/                # SettingsStore(DataStore), TokenStore(Keystore)
│       │   └── ui/{theme,main,main/components,settings}
│       ├── src/main/res/            # 아이콘, 테마, network_security_config
│       ├── src/debug/res/           # 디버그 전용 HTTP 허용 설정
│       ├── src/test/kotlin/…        # ViewModel 테스트, 화면 스냅샷 테스트
│       └── screenshots/             # Roborazzi 스냅샷
├── backend/                         # FastAPI (공용)
└── ios/                             # 보관 (개발 중단)
```

PHASE 2 추가: `core/…/routing`(분류기), `core/…/network/AssistService`(SSE), `backend/app/engines/{nvidia,gemini_search}.py`,
`backend/app/services/{usage,guard,container}.py`, `backend/app/routers/{assist,usage}.py`, `backend/scripts/live_check.py`.
PHASE 3 이후: `app/…/voice`(STT·TTS·웨이크워드), `app/…/device`(캘린더·알람·파일).

## 8. 기술 스택 (2026-11 기준 최신 안정 버전)

AGP 9.5.2 · Gradle 9.9.2 · Kotlin 2.4.21 · Compose BOM 2026.11.01 (Material3 1.5.0) · compileSdk 37.2 · targetSdk 36 · minSdk 26 ·
OkHttp 5.5.1 · kotlinx.serialization 1.13.0 · Coroutines 1.11.0 · DataStore 1.3.0 · Robolectric 4.18.1 · Roborazzi 1.79.0
백엔드: Python 3.13 · FastAPI 0.165 · httpx2 2.16.2
