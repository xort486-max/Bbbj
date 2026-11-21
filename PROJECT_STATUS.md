# JARVIS — PROJECT STATUS

> 최종 갱신: 2026-11-21 · 플랫폼: **Android** · 현재 단계: **PHASE 2 완료 (Gemini·Claude 실호출 검증은 키 설정 후)**

## 단계별 진행

| 단계 | 내용 | 상태 |
|---|---|---|
| PHASE 1 | 기본 프로젝트 · Compose 화면 · JARVIS 디자인 · 구조 설계 | ✅ 완료 (사용자 휴대폰 설치 확인) |
| PHASE 2 | NVIDIA · Google · Claude 연결, 라우팅, 오류 처리, 사용량 | ✅ 구현·테스트 완료 / NVIDIA 실호출 ✅ / Gemini·Claude 실호출 ⏳ 키 필요 |
| PHASE 3 | 마이크 권한, 한국어 STT, TTS, 음성 대화, 웨이크 워드 | 다음 단계 |
| PHASE 4 | 캘린더, 알람, 미리 알림, 파일, 앱 바로가기·어시스턴트 연동 | 대기 |
| PHASE 5 | Claude Tool Use, 작업 계획, 프로젝트·코드 작성 | 대기 |
| PHASE 6 | 문서·대본, 이미지 생성 연동, FFmpeg 영상 합성 | 대기 |
| PHASE 7 | 사용량 최적화, 보안 점검, 오류·실기기 테스트, 배포 준비 | 대기 |

## PHASE 2 결과

### 구현됨
- **로컬 명령 분류기** (`:core` CommandClassifier): CHAT / SEARCH / RESEARCH / AGENT / DEVICE. 기기 안 규칙 기반, 비용 0.
- **백엔드 `/v1/assist`** (SSE 스트리밍): 경로별 엔진 실행, 대체 처리, 중복 차단, Claude 응답 캐시, 사용량 기록.
  - NVIDIA 대화 엔진: Chat Completions 스트리밍, 재시도, 지원 종료 모델(410) 안내, 추론 모드 설정.
  - Google 검색 엔진: Gemini Interactions API + google_search, 원문·출처·검색 제안 추출 (약관상 캐시 없음).
  - Claude 엔진: 공식 SDK 스트리밍, `fallbacks: "default"`, 웹 검색 도구(research), pause_turn 이어받기, 거절 처리.
- **백엔드 `/v1/usage`**: 오늘의 엔진별 요청·오류·캐시·토큰·검색 수, 일일 한도.
- **앱**: 실시간 스트리밍 답변, 엔진별 상태 전환(NVIDIA 응답 중/Google 검색 중/Claude 추론 중), 출처 링크,
  Google 검색 제안 위젯, 사용량 표시, 검색 빠른 기능(다음 입력을 검색으로), 중단 버튼(스트림 연결 종료).

### 설계 변경 (약관 확인 결과)
- Gemini 근거 결과는 **캐시·수정·다른 AI 분석 금지**, 검색 제안과 함께 표시 의무 → "검색 결과 분석"은
  Gemini 결과를 Claude에 넘기지 않고 **Claude 웹 검색 도구**로 처리. 요청 캐시는 Claude 응답에만 적용.
- NVIDIA 기본 모델: 문서에 남아 있던 `qwen/qwen3-next-80b-a3b-instruct`가 실제로는 2026-07-27 지원 종료(410) →
  실제 모델 목록 조회·한국어 응답 시험 후 `nvidia/nemotron-3.5-lightning-30b-a3b`(추론 모드 끔)로 변경.

### 검증 결과
| 항목 | 방법 | 결과 |
|---|---|---|
| 백엔드 | pytest (엔진별 모의 업스트림, 대체·한도·중복·캐시·약관 준수·본문 미저장) | ✅ 34개 통과 |
| **NVIDIA 실제 API** | live_check + 실서버 `/v1/assist` 스트리밍 | ✅ 한국어 응답 0.3~0.7초, JARVIS 페르소나 확인 |
| 대체 처리 실서버 | Claude 미설정 상태 agent 요청 | ✅ NVIDIA로 대체 + 안내 |
| Gemini 실제 API | — | ⏳ 키 미설정 (모의 응답 테스트는 통과) |
| Claude 실제 API | — | ⏳ 키 미설정 (SDK 스트림 모의 테스트는 통과) |
| `:core` | 분류기·SSE(실제 HTTP, 취소)·모델 | ✅ 40개 통과 |
| `:app` | ViewModel(대화·검색·실패·대체·취소·기록) + 화면 스냅샷 5종 | ✅ 21개 통과 |
| APK | 디버그·릴리스(R8) 빌드, Lint | ✅ |

### 알려진 제한
- NVIDIA 스트리밍 응답은 토큰 사용량을 주지 않음 (문서에 사용량 옵션 없음) → 요청 수만 집계.
- 오래된 대화 요약(토큰 절약)은 PHASE 7.
- Google 검색 제안 위젯은 기기 WebView에서만 그려짐 (스냅샷 테스트에선 빈 영역).

## 사용자 설정 필요
1. **Gemini API Key** — 백엔드 `.env`의 `GOOGLE_API_KEY` (aistudio.google.com).
2. **Anthropic API Key** — 백엔드 `.env`의 `ANTHROPIC_API_KEY` (platform.claude.com).
3. 개발 환경(클라우드 세션)에서 실제 검증을 원하면: 환경 설정 → Network secrets에 Gemini 키(헤더 `x-goog-api-key`),
   Claude 키는 환경 변수 `ANTHROPIC_API_KEY`(같은 환경의 모든 세션에서 읽을 수 있음)로 추가.

## 다음 작업 (PHASE 3 — 음성)
- [ ] RECORD_AUDIO 권한, 마이크 사용 표시
- [ ] SpeechRecognizer 한국어/영어 인식 (가능하면 기기 내 인식), 인식 중 파형에 실제 음량 반영
- [ ] TextToSpeech 응답 읽기, 속도·음량 설정, 말하는 중 중단
- [ ] 음성 대화 모드 (말하기 → 응답 → 다시 듣기), "자비스" 웨이크 워드 (전경)
- [ ] 백그라운드 호출 대안: 앱 바로가기/기본 어시스턴트 지정 검토

## 변경 기록
- 2026-11-21: PHASE 2 — AI 엔진 연결 (NVIDIA·Gemini Grounding·Claude), 분류기, 스트리밍, 사용량, 대체 처리.
- 2026-11-21: Android 전환 — `android/` 프로젝트(core·app), 테스트·스냅샷, 문서 갱신. `ios/` 보관.
- 2026-11-21: PHASE 1 (iOS) — 구조, JarvisCore, SwiftUI 화면, 백엔드 골격, 설계 문서.
