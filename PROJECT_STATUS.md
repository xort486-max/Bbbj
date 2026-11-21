# JARVIS — PROJECT STATUS

> 최종 갱신: 2026-11-21 · 플랫폼: **Android** · 현재 단계: **PHASE 2 완료** · AI 엔진: **NVIDIA + Google (Claude API 제외)**

## 단계별 진행

| 단계 | 내용 | 상태 |
|---|---|---|
| PHASE 1 | 기본 프로젝트 · Compose 화면 · JARVIS 디자인 · 구조 설계 | ✅ 완료 (사용자 휴대폰 설치 확인) |
| PHASE 2 | NVIDIA · Google 연결, 라우팅, 오류 처리, 사용량 | ✅ 구현·테스트 완료 / NVIDIA 실호출 ✅ / Gemini 실호출 ⏳ 키 필요 |
| PHASE 3 | 마이크 권한, 한국어 STT, TTS, 음성 대화, 웨이크 워드 | 다음 단계 |
| PHASE 4 | 캘린더, 알람, 미리 알림, 파일, 앱 바로가기·어시스턴트 연동 | 대기 |
| PHASE 5 | 에이전트 Tool Use(NVIDIA 모델 함수 호출), 작업 계획, 프로젝트·코드 작성 | 대기 |
| PHASE 6 | 문서·대본, 이미지 생성 연동, FFmpeg 영상 합성 | 대기 |
| PHASE 7 | 사용량 최적화, 보안 점검, 오류·실기기 테스트, 배포 준비 | 대기 |

## 엔진 구성 변경 (2026-11-21)
- **Claude(Anthropic) API 제외** — 비용 문제 (사용자 결정). 코드·의존성 모두 제거 (git 기록에는 남아 있어 필요 시 복원 가능).
- 에이전트(기획·코드·문서·다단계): **NVIDIA `nvidia/nemotron-3-ultra-550b-a55b`** — 후보 3개를 실제 호출로 비교해 선택
  (품질 우수·약 6~8초. kimi-k3.1-pro는 60초로 너무 느림).
- 검색 분석(research): **Gemini Grounding**에 비교·분석 지시를 붙여 처리. Google을 못 쓰면 NVIDIA 에이전트가
  "검색 없이, 최신 정보가 아닐 수 있음" 안내와 함께 답한다.
- 화면 상태 "Claude 추론 중" → **"심층 추론 중"**(NVIDIA 에이전트 모델).

## PHASE 2 결과

### 구현됨
- **로컬 명령 분류기** (`:core` CommandClassifier): CHAT / SEARCH / RESEARCH / AGENT / DEVICE. 기기 안 규칙 기반, 비용 0.
- **백엔드 `/v1/assist`** (SSE 스트리밍): 경로별 엔진 실행, 대체 처리, 중복 차단, 에이전트 응답 캐시, 사용량 기록.
  - NVIDIA: 대화 모델(추론 끔, 0.3~1초)과 에이전트 모델(추론 켬) 두 모드, 재시도, 지원 종료 모델(410) 안내.
  - Google: Gemini Interactions API + google_search, 원문·출처·검색 제안 추출 (약관상 캐시 없음).
- **백엔드 `/v1/usage`**: 오늘의 엔진별 요청·오류·캐시·토큰·검색 수, 일일 한도.
- **앱**: 실시간 스트리밍 답변, 엔진별 상태 전환(NVIDIA 응답 중 / Google 검색 중 / 심층 추론 중), 출처 링크,
  Google 검색 제안 위젯, 사용량 표시, 검색 빠른 기능, 중단 버튼(스트림 연결 종료).

### 검증 결과
| 항목 | 방법 | 결과 |
|---|---|---|
| 백엔드 | pytest (모의 업스트림, 대체·한도·중복·캐시·약관 준수·본문 미저장) | ✅ 32개 통과 |
| **NVIDIA 대화 실제 API** | live_check + 실서버 `/v1/assist` | ✅ 한국어 0.3~0.7초, JARVIS 페르소나 |
| **NVIDIA 에이전트 실제 API** | live_check agent | ✅ 7.5초, 구체적 단계별 계획 |
| Gemini 실제 API | — | ⏳ 키 미설정 (모의 응답 테스트는 통과) |
| `:core` | 분류기·SSE(실제 HTTP, 취소)·모델 | ✅ 40개 통과 |
| `:app` | ViewModel + 화면 스냅샷 5종 | ✅ 21개 통과 |
| APK | 디버그·릴리스(R8) 빌드, Lint 오류 0 | ✅ |

### 비용 정리
- **Claude**: 사용 안 함 → 0원.
- **NVIDIA API Catalog**: build.nvidia.com 개발자 키로 사용 (무료 체험 크레딧·요청 한도는 계정 화면에서 확인 필요).
- **Gemini Grounding**: 월 5,000회 검색 무료, 이후 1,000회당 $14 + 토큰 요금 (문서 기준). 유료 결제 계정 필요할 수 있음.
- 한도가 걱정되면 `.env`의 `DAILY_LIMIT_GOOGLE`, `DAILY_LIMIT_NVIDIA`로 하루 요청 수 제한.

### 알려진 제한
- NVIDIA 스트리밍 응답은 토큰 사용량을 주지 않음 → 요청 수만 집계.
- 오래된 대화 요약(토큰 절약)은 PHASE 7.
- Google 검색 제안 위젯은 기기 WebView에서만 그려짐 (스냅샷 테스트에선 빈 영역).

## 사용자 설정 필요
1. **Gemini API Key** — 백엔드 `.env`의 `GOOGLE_API_KEY` (aistudio.google.com).
2. (선택) 클라우드 개발 환경에서 Gemini 실제 검증: 환경 설정 → Network secrets에 Gemini 키(헤더 `x-goog-api-key`).

## 다음 작업 (PHASE 3 — 음성)
- [ ] RECORD_AUDIO 권한, 마이크 사용 표시
- [ ] SpeechRecognizer 한국어/영어 인식 (가능하면 기기 내 인식), 인식 중 파형에 실제 음량 반영
- [ ] TextToSpeech 응답 읽기, 속도·음량 설정, 말하는 중 중단
- [ ] 음성 대화 모드 (말하기 → 응답 → 다시 듣기), "자비스" 웨이크 워드 (전경)
- [ ] 백그라운드 호출 대안: 앱 바로가기/기본 어시스턴트 지정 검토

## 변경 기록
- 2026-11-21: Claude API 제외 — 에이전트를 NVIDIA 대형 모델로, 검색 분석을 Gemini로 전환.
- 2026-11-21: PHASE 2 — AI 엔진 연결 (NVIDIA·Gemini Grounding·Claude), 분류기, 스트리밍, 사용량, 대체 처리.
- 2026-11-21: Android 전환 — `android/` 프로젝트(core·app), 테스트·스냅샷, 문서 갱신. `ios/` 보관.
- 2026-11-21: PHASE 1 (iOS) — 구조, JarvisCore, SwiftUI 화면, 백엔드 골격, 설계 문서.
