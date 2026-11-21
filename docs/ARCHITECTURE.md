# JARVIS iOS — 기술 설계서

> 최종 수정: 2026-11-21 (PHASE 1)

## 1. 전체 구조

```
┌──────────────────────── iOS 앱 (SwiftUI) ────────────────────────┐
│                                                                   │
│  UI 계층        MainView · SettingsView · HUD 컴포넌트             │
│      │                                                            │
│  ViewModel      MainViewModel (@MainActor, @Observable)           │
│      │                                                            │
│  JarvisCore     상태 머신 · 대화 기록 · 작업 보드 · 백엔드 클라이언트 │
│  (Swift 패키지)  ↳ PHASE 2+: CommandClassifier · CommandRouter     │
│      │                                                            │
│  기기 서비스     Speech · AVSpeechSynthesizer · EventKit · Files    │
│  (PHASE 3~4)    · App Intents / Siri 단축어                        │
│      │                                                            │
│  Keychain       백엔드 앱 토큰만 저장 (AI API Key 없음)              │
└──────┬────────────────────────────────────────────────────────────┘
       │ HTTPS (Bearer 앱 토큰)
┌──────▼──────────────── JARVIS 백엔드 (Python FastAPI) ─────────────┐
│  인증 · 라우팅 · 캐싱 · 중복 차단 · 사용량 기록 · 일일 한도 · 로그     │
│  ├─ NVIDIA 모듈   → integrate.api.nvidia.com/v1 (Chat Completions)  │
│  ├─ Google 모듈   → 검색 제품 (§4 참고, PHASE 2 착수 전 선택)        │
│  ├─ Claude 모듈   → Anthropic Messages API + Tool Use               │
│  └─ 작업 엔진     → 프로젝트 작업 공간 · 문서 · FFmpeg 영상 합성       │
│  비밀값: 환경변수 / 서버 비밀 관리 기능                                │
└───────────────────────────────────────────────────────────────────┘
```

### 설계 원칙
1. **API Key는 백엔드에만.** 앱은 백엔드 주소와 앱 토큰만 안다.
2. **UI와 무관한 로직은 JarvisCore로.** 플랫폼 독립 → `swift test`로 Linux/macOS에서 검증 가능.
3. **정직한 표시.** 구현되지 않은 기능은 비활성화하고 예정 단계를 표시한다 (`JarvisFeature.isImplemented`).
   `configured`(설정값 존재)와 `connected`(실제 호출 성공)를 구분한다.
4. **상태 전이는 검증된다.** `JarvisStateMachine`이 허용되지 않은 전이를 막아 UI 표시와 실제 흐름을 일치시킨다.
5. **모든 기능은 모듈화**: 엔진·기기 서비스는 프로토콜 뒤에 두고 테스트에서 교체한다.

## 2. 요청 분배 (PHASE 2에서 구현)

```
사용자 입력(음성/텍스트)
   │
   ▼
로컬 명령 분류기 (기기 내, 규칙 + 키워드, 비용 0)
   ├─ 기기 기능 (일정, 미리 알림, 메모, URL 열기)  → 로컬 명령 엔진
   ├─ 일반 대화 / 간단 질문                         → NVIDIA
   ├─ 웹·뉴스·기술 검색                            → Google
   ├─ 검색 + 분석 요청                             → Google → Claude
   └─ 복잡한 명령 / 기획 / 코드 / 문서 / 다단계     → Claude (Tool Use)
```

Claude 호출 최소화 장치 (백엔드): 요청 캐시, 동일 요청 중복 차단, 대화 요약, 토큰 사용량 기록,
API별 일일 한도와 한도 초과 시 대체 처리(예: Claude 한도 초과 → 사용자에게 알리고 NVIDIA로 축약 응답).

## 3. 상태 모델

| 상태 | 표시 | 활성 엔진 |
|---|---|---|
| `idle` | 대기 중 | — |
| `listening` | 음성 인식 중 | — |
| `analyzing` | 사용자 명령 분석 중 | LOCAL |
| `nvidiaResponding` | NVIDIA 응답 중 | NVIDIA |
| `googleSearching` | Google 검색 중 | GOOGLE |
| `claudeReasoning` | Claude 추론 중 | CLAUDE |
| `executingTask` | 작업 실행 중 | LOCAL |
| `completed` | 작업 완료 | — |
| `error` | 오류 발생 | — |

허용 전이: `idle→listening|analyzing`, `listening→analyzing`, `analyzing→(각 엔진)|executingTask|completed`,
`googleSearching→claudeReasoning|nvidiaResponding|completed`, `claudeReasoning→googleSearching|executingTask|completed`,
`executingTask→claudeReasoning|completed`, `completed|error→listening|analyzing`. 모든 상태 → `error`, `idle`(중단).

## 4. Google 검색 — 제품 현황 (2026-11 확인)

| 선택지 | 범위 | 현황 / 비고 |
|---|---|---|
| Custom Search JSON API | 웹 전체 / 이미지 검색 | **신규 고객 접수 중단, 2027-01-01 종료 예정.** 기존 키가 있어도 6주 뒤 중단되므로 신규 개발에 부적합 |
| Vertex AI Search | 지정한 사이트·데이터 저장소 | Google 공식 대체 제품. 공개 웹 전체 검색 용도는 아님 |
| Gemini API — Grounding with Google Search | 웹 전체 (Google 검색 기반) | 응답에 `groundingMetadata`(검색어, 출처 URI·제목) 포함. **"Google Search Suggestions" 표시 등 사용 요건 준수 필요**, 검색 쿼리당 과금(Gemini 3). 이미지 검색 결과는 제공하지 않음 |

백엔드는 `GOOGLE_SEARCH_PROVIDER`로 제공 방식을 교체할 수 있게 설계했다. PHASE 2 착수 전 사용자 선택이 필요하다.

## 5. iOS 플랫폼 제약 (구현 시 준수)

- **웨이크 워드 "자비스"**: 앱이 전경에 있을 때만 상시 청취 가능. iOS는 서드파티 앱의 백그라운드 상시 마이크 청취를 허용하지 않는다.
  대체: Siri 단축어("Siri야, 자비스 열어"), App Intents, 동작 버튼/잠금 화면 컨트롤에 App Intent 연결.
- **음성 인식**: Speech 프레임워크, `requiresOnDeviceRecognition`은 지원 언어/기기에서만 사용. STT는 프로토콜로 추상화.
- **알람**: 시스템 시계 앱 알람은 조작 불가. iOS 26+ AlarmKit 또는 로컬 알림/미리 알림으로 대체 — PHASE 4에서 공식 문서 확인 후 결정.
- **다른 앱 조작 불가**: URL 스킴 열기, 공유 시트, 단축어 실행 요청만 사용.
- **권한 문구**: 마이크·음성 인식·캘린더·미리 알림·사진 권한 문구는 해당 기능 구현 단계에서 Info.plist에 추가.

## 6. 보안 및 승인

- 앱 ↔ 백엔드: HTTPS + Bearer 앱 토큰(상수 시간 비교, 미설정 시 fail closed). 앱은 HTTPS만 허용(로컬 개발망 예외).
- 사용자 확인 필요 작업: 외부 전송, 메시지/이메일 발송, 파일·일정 삭제, 결제, 개인정보 포함 작업, 되돌리기 어려운 작업.
  → PHASE 5에서 Claude 도구마다 `requiresConfirmation` 속성을 두고 앱에서 승인 UI를 띄운다.
- 모든 작업은 실행 기록(TaskBoard + 백엔드 로그)을 남기고 취소 가능한 작업은 취소를 제공한다.

## 7. 폴더 구조

```
Bbbj/
├── README.md                       # 빠른 시작
├── PROJECT_STATUS.md               # 진행 상황 · 남은 작업
├── docs/ARCHITECTURE.md            # 이 문서
├── ios/
│   ├── project.yml                 # XcodeGen 정의 → JARVIS.xcodeproj 생성
│   ├── JarvisApp/
│   │   ├── App/                    # @main, 미리보기 데이터
│   │   ├── Core/                   # Keychain, 앱 설정, 마이크 상태
│   │   ├── DesignSystem/           # 색상·글꼴·HUD 패널·배경
│   │   ├── Features/
│   │   │   ├── Main/               # 메인 화면 + ViewModel + 컴포넌트(코어, 파형, 패널…)
│   │   │   └── Settings/           # 백엔드 주소, 토큰, 디자인 점검
│   │   └── Resources/              # Assets, Info.plist
│   ├── JarvisAppTests/             # 앱 ViewModel 테스트
│   └── Packages/JarvisCore/        # 플랫폼 독립 핵심 로직 (SwiftPM)
│       ├── Sources/JarvisCore/{Models,State,Networking}
│       └── Tests/JarvisCoreTests
└── backend/
    ├── app/                        # FastAPI (config, security, routers)
    ├── tests/
    ├── requirements*.txt
    └── .env.example
```

PHASE 2 이후 추가 예정: `JarvisCore/Routing`(분류기·라우터), `backend/app/engines/{nvidia,google,claude}.py`,
`backend/app/usage/`(사용량·캐시), `JarvisApp/Services/{Speech,Calendar,Files}`, `JarvisApp/Intents/`.
