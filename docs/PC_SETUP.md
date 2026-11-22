# JARVIS — Windows PC에서 할 일

PC는 **백엔드 서버**를 실행합니다. 휴대폰 앱이 이 서버를 통해 NVIDIA(대화·에이전트)와 Google(검색)을 사용합니다.
API Key는 PC의 `.env` 파일에만 들어가고, 앱에는 들어가지 않습니다.

---

## 0. 한 번만 설치 (10분)

| 프로그램 | 받는 곳 | 주의 |
|---|---|---|
| Python 3.10 이상 | https://www.python.org/downloads/ | 설치 첫 화면에서 **"Add python.exe to PATH" 체크** |
| Git (선택) | https://git-scm.com/download/win | 없으면 GitHub에서 ZIP으로 받아도 됨 |

## 1. API Key 준비

| 키 | 발급 | 용도 |
|---|---|---|
| NVIDIA | https://build.nvidia.com → 로그인 → API Keys | 대화, 기획·코드 (에이전트) |
| Google Gemini | https://aistudio.google.com → Get API key | 웹 검색, 검색 분석 |

키는 아무에게도 보내지 말고 아래 `.env` 파일에만 넣으세요.

## 2. 코드 받기

PowerShell(시작 메뉴 → "PowerShell")에서:

```powershell
cd $HOME\Documents
git clone -b claude/jarvis-ios-ai-assistant-2gcue9 https://github.com/xort486-max/Bbbj.git
cd Bbbj\backend
```

Git이 없으면: GitHub 저장소 페이지 → 브랜치 `claude/jarvis-ios-ai-assistant-2gcue9` 선택 → **Code → Download ZIP** → 압축 해제 후
PowerShell에서 `cd 압축푼폴더\backend`.

이미 받아 두었다면 최신으로 갱신: `git pull`

## 3. 백엔드 설치 (처음 한 번)

```powershell
py -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements-dev.txt
copy .env.example .env
```

- `Activate.ps1` 실행이 막히면 먼저: `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` → `Y`
- 활성화되면 줄 앞에 `(.venv)`가 붙습니다.

## 4. `.env` 채우기

```powershell
py -c "import secrets; print(secrets.token_urlsafe(32))"
notepad .env
```

| 항목 | 넣을 값 |
|---|---|
| `JARVIS_APP_TOKEN` | 위 명령으로 나온 긴 문자열 (앱에도 같은 값을 입력) |
| `NVIDIA_API_KEY` | NVIDIA 키 |
| `GOOGLE_API_KEY` | Gemini 키 |

나머지는 그대로 두면 됩니다. 저장 후 닫기.

## 5. 점검

```powershell
pytest                           # 자동 테스트 (무료) → "32 passed"
python -m scripts.live_check     # 실제 AI 호출 (아주 소액) → 항목마다 "✅ 성공"
```

`❌ 실패`가 나오면 그 메시지를 그대로 보내 주세요.

## 6. 서버 실행 (앱을 쓸 때마다)

```powershell
cd $HOME\Documents\Bbbj\backend
.venv\Scripts\Activate.ps1
uvicorn app.main:app --env-file .env --host 0.0.0.0 --port 8000
```

- **이 창은 앱을 쓰는 동안 계속 열어 두세요.** 닫으면 서버가 꺼집니다. 끄려면 `Ctrl + C`.
- Windows 방화벽 창이 뜨면 **개인 네트워크 허용**.
- PC 주소 확인 (다른 PowerShell 창에서): `ipconfig` → Wi-Fi 항목의 **IPv4 주소** (예: `192.168.0.10`)

## 7. 휴대폰 앱 연결

1. 폰과 PC를 **같은 Wi-Fi**에 연결
2. 폰 브라우저에서 `http://<PC IPv4 주소>:8000/health` 열기 → `{"status":"ok"...}` 보이면 통신 OK
3. JARVIS 앱 → ⚙ 설정
   - 백엔드 주소: `http://<PC IPv4 주소>:8000` → **주소 저장**
   - 앱 인증 토큰: `.env`의 `JARVIS_APP_TOKEN` 값 → **토큰 저장**
   - **연결 테스트** → 백엔드 "연결됨", NVIDIA·GOOGLE "설정됨"

## 문제 해결

| 증상 | 해결 |
|---|---|
| `py`를 찾을 수 없음 | Python 설치 시 PATH 체크 안 함 → Python 재설치 |
| 폰에서 `/health`가 안 열림 | 같은 Wi-Fi인지 확인 / 방화벽: 설정 → 개인 정보 및 보안 → Windows 보안 → 방화벽 → "앱 허용"에서 Python 허용 / 공용 네트워크면 개인으로 변경 |
| 앱에 "토큰이 올바르지 않습니다" | 앱 토큰과 `.env`의 `JARVIS_APP_TOKEN`이 같은지 확인 |
| "설정 필요: GOOGLE_API_KEY" | `.env`에 키 입력 후 서버 재시작 (`Ctrl+C` → 다시 `uvicorn ...`) |
| live_check에서 NVIDIA 410 | 모델 지원 종료 → `.env`의 `NVIDIA_MODEL` 변경 (알려 주시면 대체 모델 찾아드림) |
| live_check google: "할당량이 없습니다 / exceeded your current quota" | Gemini의 Google 검색 그라운딩은 **유료 서비스**라 무료 등급 키로는 쓸 수 없음 → https://aistudio.google.com 에서 결제(Billing) 설정 후 Tier 1로 전환. 검색 월 5,000회 무료, 이후 1,000회당 $14 + 토큰 요금. Google Cloud에서 예산 알림 설정 권장 |
| 서버 시작 시 `ZoneInfoNotFoundError` | 최신 코드로 `git pull` 후 `pip install -r requirements-dev.txt` (tzdata 설치) |
| PC IP가 바뀜 | 공유기 재시작 시 바뀔 수 있음 → `ipconfig`로 다시 확인 후 앱 설정 수정 |
