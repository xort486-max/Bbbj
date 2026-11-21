import sys
from pathlib import Path

# `backend/`를 import 경로에 추가 → `from app...` 사용 가능
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
